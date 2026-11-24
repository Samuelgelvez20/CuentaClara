package co.cuentaclara.chat;

import co.cuentaclara.profile.FinancialProfileService;
import co.cuentaclara.rag.AiCostGuardAndCacheService;
import co.cuentaclara.report.GeminiFinancialAdvisorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agente conversacional en lenguaje natural con citas obligatorias y respaldo LLM Gemini (RF-12, RNF-06, RNF-09).
 * Orquesta las dos herramientas deterministas (`consultar_transacciones` y `buscar_documentos`):
 * - Todas las cifras y sumas salen de SQL (`consultar_transacciones`).
 * - Toda respuesta factual cita la fuente verificable (XML con número/CUFE, PDF/extracto con página,
 *   imagen con referencia o gasto manual con ID).
 * - Cuando Gemini está configurado, interpreta filtros abiertos y redacta respuestas conversacionales
 *   estrictamente ancladas a la evidencia SQL/documental; si falta evidencia, rechaza sin inventar cifras.
 */
@Service
public class NaturalLanguageChatService {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageChatService.class);

    private static final Map<String, String> SPANISH_MONTHS = Map.ofEntries(
            Map.entry("enero", "01"),
            Map.entry("febrero", "02"),
            Map.entry("marzo", "03"),
            Map.entry("abril", "04"),
            Map.entry("mayo", "05"),
            Map.entry("junio", "06"),
            Map.entry("julio", "07"),
            Map.entry("agosto", "08"),
            Map.entry("septiembre", "09"),
            Map.entry("octubre", "10"),
            Map.entry("noviembre", "11"),
            Map.entry("diciembre", "12")
    );

    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("\\b(20\\d{2})-(0[1-9]|1[0-2])\\b");
    private static final Pattern EXPLICIT_YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern NIT_OR_INVOICE_TOKEN = Pattern.compile("(?i)\\b(?:FE-\\d+|SETT-\\d+|PDF-\\d+|IMG-\\d+|\\d{8,12})\\b");

    private final HybridSearchAndChatToolsService toolsService;
    private final FinancialProfileService profileService;
    private final GeminiFinancialAdvisorService geminiAdvisor;
    private final ObjectMapper objectMapper;
    private final boolean geminiEnabled;

    @Autowired(required = false)
    private AiCostGuardAndCacheService costGuardAndCache;

    public NaturalLanguageChatService(
            HybridSearchAndChatToolsService toolsService,
            FinancialProfileService profileService
    ) {
        this(toolsService, profileService, null, new ObjectMapper(), false);
    }

    @Autowired
    public NaturalLanguageChatService(
            HybridSearchAndChatToolsService toolsService,
            FinancialProfileService profileService,
            @Autowired(required = false) GeminiFinancialAdvisorService geminiAdvisor,
            ObjectMapper objectMapper,
            @Value("${cuentaclara.chat.gemini-enabled:true}") boolean geminiEnabled
    ) {
        this.toolsService = toolsService;
        this.profileService = profileService;
        this.geminiAdvisor = geminiAdvisor;
        this.objectMapper = objectMapper;
        this.geminiEnabled = geminiEnabled;
    }

    @Transactional(readOnly = true)
    public ChatAnswerResponse askQuestion(UUID userId, String userQuestion) {
        String question = userQuestion != null ? userQuestion.trim() : "";
        if (question.isBlank()) {
            throw new IllegalArgumentException("La pregunta no puede estar vacía.");
        }

        String lower = question.toLowerCase(Locale.ROOT);
        boolean aiQuotaAvailable = costGuardAndCache == null || costGuardAndCache.tryConsumeDailyAiCall(userId);

        // Si pregunta por un comercio/concepto específico, resolver filtros deterministas + Gemini LLM cuando aplique
        String detectedMonth = detectYearMonth(lower);
        String detectedCategory = detectCategoryFilter(lower);
        String detectedBlock = detectBudgetBlockFilter(lower);
        String detectedMerchantOrCode = detectMerchantOrDocumentCode(question, lower);

        boolean isBudgetOverviewQuestion = lower.contains("presupuesto")
                || lower.contains("50/30/20")
                || lower.contains("distribución")
                || lower.contains("distribucion");

        if (!isBudgetOverviewQuestion && aiQuotaAvailable && isGeminiActive()
                && (detectedMerchantOrCode == null || (detectedMonth == null && detectedCategory == null))) {
            GeminiExtractedFilters aiFilters = extractFiltersWithGemini(question);
            if (aiFilters != null) {
                if (detectedMonth == null && aiFilters.yearMonth() != null) {
                    detectedMonth = aiFilters.yearMonth();
                }
                if (detectedCategory == null && aiFilters.category() != null) {
                    detectedCategory = aiFilters.category();
                }
                if (detectedBlock == null && aiFilters.budgetBlock() != null) {
                    detectedBlock = aiFilters.budgetBlock();
                }
                if (detectedMerchantOrCode == null && aiFilters.merchantKeyword() != null) {
                    detectedMerchantOrCode = aiFilters.merchantKeyword();
                }
            }
        }

        // 1. Invocar herramienta SQL determinista consultar_transacciones(filtros)
        HybridSearchAndChatToolsService.TransactionQueryFilters sqlFilters =
                new HybridSearchAndChatToolsService.TransactionQueryFilters(
                        detectedMonth,
                        null,
                        null,
                        detectedCategory,
                        detectedBlock,
                        detectedMerchantOrCode
                );
        HybridSearchAndChatToolsService.SqlTransactionsToolResult sqlResult =
                toolsService.consultarTransacciones(userId, sqlFilters);

        // 2. Invocar herramienta de búsqueda híbrida (pgvector + tsvector con RRF) buscar_documentos(texto)
        List<HybridSearchAndChatToolsService.HybridChunkEvidence> docChunks =
                toolsService.buscarDocumentos(userId, question, 6);

        // Si el usuario preguntó por un comercio/tema específico que no arrojó filas en SQL,
        // verificar si hay coincidencia léxica real en los fragmentos documentales (ej. un ítem de factura o un extracto PDF)
        boolean hasSpecificEntityFilter = detectedMerchantOrCode != null || detectedCategory != null || isSpecificTopicQuery(lower);
        List<HybridSearchAndChatToolsService.HybridChunkEvidence> verifiedChunks = filterRelevantChunks(lower, docChunks);

        // 3. Caso: Pregunta sobre presupuesto / distribución de presupuesto
        if (isBudgetOverviewQuestion) {
            Optional<FinancialProfileService.ProfileWithInitialBudget> profileOpt = profileService.getCurrentProfileAndBudget(userId);
            if (profileOpt.isPresent()) {
                var p = profileOpt.get();
                HybridSearchAndChatToolsService.SqlTransactionsToolResult allTx =
                        toolsService.consultarTransacciones(userId, new HybridSearchAndChatToolsService.TransactionQueryFilters(detectedMonth, null, null, null, null, null));
                List<String> citations = collectCitations(allTx.rows(), verifiedChunks);
                String deterministicAnswer = String.format(
                        "¡Hola! Tu ingreso base mensual registrado es de $%,.2f COP. Según tu distribución %d/%d/%d, tienes asignado un %d%% a Necesidades ($%,.2f COP), un %d%% a Deseos ($%,.2f COP) y un %d%% a Ahorro ($%,.2f COP). Hasta el momento llevas un gasto acumulado confirmado de $%,.2f COP en %d movimiento(s).",
                        p.profile().getNetIncome(),
                        p.profile().getNeedsPct(),
                        p.profile().getWantsPct(),
                        p.profile().getSavingsPct(),
                        p.profile().getNeedsPct(), p.initialBudget().getNeedsLimit(),
                        p.profile().getWantsPct(), p.initialBudget().getWantsLimit(),
                        p.profile().getSavingsPct(), p.initialBudget().getSavingsTarget(),
                        allTx.totalAmount(),
                        allTx.count()
                ).trim();
                String finalAnswer = aiQuotaAvailable
                        ? synthesizeGroundedAnswerWithGemini(
                        question,
                        Map.of(
                                "netIncomeCop", p.profile().getNetIncome(),
                                "distribution", p.profile().getNeedsPct() + "/" + p.profile().getWantsPct() + "/" + p.profile().getSavingsPct(),
                                "needsLimitCop", p.initialBudget().getNeedsLimit(),
                                "wantsLimitCop", p.initialBudget().getWantsLimit(),
                                "savingsTargetCop", p.initialBudget().getSavingsTarget(),
                                "confirmedSpentCop", allTx.totalAmount(),
                                "confirmedCount", allTx.count()
                        ),
                        allTx,
                        verifiedChunks,
                        deterministicAnswer
                )
                        : deterministicAnswer;
                return new ChatAnswerResponse(question, finalAnswer, citations, allTx.totalAmount(), true);
            }
        }

        // 4. Si hay resultados exactos en SQL para los filtros detectados
        if (sqlResult.count() > 0) {
            List<String> citations = collectCitations(sqlResult.rows(), verifiedChunks);
            StringBuilder sb = new StringBuilder();

            if (lower.contains("iva") || lower.contains("impuesto")) {
                sb.append(String.format(
                        "En tus movimientos confirmados tienes registrado un total de IVA e impuestos de $%,.2f COP, con un monto total pagado de $%,.2f COP en %d comprobante(s).",
                        sqlResult.totalTaxAmount(),
                        sqlResult.totalAmount(),
                        sqlResult.count()
                ));
            } else if (detectedCategory != null) {
                sb.append(String.format(
                        "Encontré un total de $%,.2f COP registrado en la categoría de %s, correspondiente a %d movimiento(s).",
                        sqlResult.totalAmount(),
                        detectedCategory,
                        sqlResult.count()
                ));
            } else if (detectedBlock != null) {
                String blockName = "NEEDS".equalsIgnoreCase(detectedBlock) ? "Necesidades" : "Deseos";
                sb.append(String.format(
                        "En tus gastos del bloque de %s tienes un total acumulado de $%,.2f COP en %d movimiento(s).",
                        blockName,
                        sqlResult.totalAmount(),
                        sqlResult.count()
                ));
            } else if (detectedMerchantOrCode != null) {
                sb.append(String.format(
                        "Encontré compras confirmadas para %s por un valor total de $%,.2f COP en %d comprobante(s).",
                        detectedMerchantOrCode.toUpperCase(Locale.ROOT),
                        sqlResult.totalAmount(),
                        sqlResult.count()
                ));
            } else {
                sb.append(String.format(
                        "Revisé tus registros confirmados y encontré un total de $%,.2f COP en %d movimiento(s).",
                        sqlResult.totalAmount(),
                        sqlResult.count()
                ));
            }

            sb.append(" Los movimientos destacados son: ");
            int limit = Math.min(4, sqlResult.rows().size());
            for (int i = 0; i < limit; i++) {
                var row = sqlResult.rows().get(i);
                if (i > 0) sb.append(", ");
                sb.append(String.format(
                        "%s (%s) por $%,.2f COP el %s",
                        row.merchantName(),
                        row.category(),
                        row.totalAmount(),
                        row.transactionDate()
                ));
            }
            sb.append(".");

            // Si hay fragmento verificado de documento que añade contexto (ej. un ítem o descripción de factura)
            if (!verifiedChunks.isEmpty()) {
                var topChunk = verifiedChunks.getFirst();
                sb.append(" Además, en el comprobante se detalla: \"").append(topChunk.contentText()).append("\".");
            }

            String deterministicAnswer = sb.toString().trim();
            String finalAnswer = aiQuotaAvailable
                    ? synthesizeGroundedAnswerWithGemini(
                    question,
                    Map.of(
                            "yearMonthFilter", detectedMonth != null ? detectedMonth : "ALL",
                            "categoryFilter", detectedCategory != null ? detectedCategory : "ALL",
                            "budgetBlockFilter", detectedBlock != null ? detectedBlock : "ALL",
                            "merchantFilter", detectedMerchantOrCode != null ? detectedMerchantOrCode : "ALL"
                    ),
                    sqlResult,
                    verifiedChunks,
                    deterministicAnswer
            )
                    : deterministicAnswer;

            return new ChatAnswerResponse(
                    question,
                    finalAnswer,
                    citations,
                    sqlResult.totalAmount(),
                    true
            );
        }

        // 5. Si no hubo filas directas en transactions pero sí evidencia verificada en document_chunks
        // (por ejemplo, consulta sobre un ítem específico de factura como "Café Molido" o sobre un Extracto Bancario PDF por página)
        if (!verifiedChunks.isEmpty()) {
            List<String> citations = new ArrayList<>();
            StringBuilder sb = new StringBuilder("Encontré la información en tus documentos indexados: ");
            for (int i = 0; i < verifiedChunks.size(); i++) {
                var chunk = verifiedChunks.get(i);
                if (!citations.contains(chunk.sourceReference())) {
                    citations.add(chunk.sourceReference());
                }
                if (i > 0) sb.append(" ");
                sb.append("\"").append(chunk.contentText()).append("\".");
            }
            String deterministicAnswer = sb.toString().trim();
            String finalAnswer = aiQuotaAvailable
                    ? synthesizeGroundedAnswerWithGemini(
                    question,
                    Map.of("documentMatchesOnly", verifiedChunks.size()),
                    sqlResult,
                    verifiedChunks,
                    deterministicAnswer
            )
                    : deterministicAnswer;
            return new ChatAnswerResponse(
                    question,
                    finalAnswer,
                    citations,
                    BigDecimal.ZERO,
                    true
            );
        }

        // 6. Sin evidencia -> Indicarlo claramente sin inventar cifras ni páginas (RF-12, RNF-06)
        return new ChatAnswerResponse(
                question,
                "No tengo evidencia en tus facturas, extractos ni gastos confirmados para responder a esa consulta. Si tienes el comprobante, puedes subirlo en la sección de Cargar y Confirmar.",
                List.of(),
                BigDecimal.ZERO,
                false
        );
    }

    private boolean isGeminiActive() {
        return geminiEnabled && geminiAdvisor != null && geminiAdvisor.isGeminiConfigured();
    }

    private GeminiExtractedFilters extractFiltersWithGemini(String question) {
        String systemPrompt = """
                Eres un extractor de parámetros SQL para finanzas personales de CuentaClara.
                Devuelve ÚNICAMENTE un JSON válido con las llaves exactas:
                {"yearMonth": "YYYY-MM o null", "category": "string o null", "budgetBlock": "NEEDS | WANTS | null", "merchantKeyword": "string o null"}
                Reglas estrictas:
                - Si mencionan un mes en español sin año, usa el año actual %d. Si indican un año (ej. marzo 2025), usa ese año exacto (2025-03).
                - merchantKeyword solo si nombran un comercio, banco, servicio o código específico; nunca pongas meses ni categorías ahí.
                - Usa null cuando no esté explícito en la pregunta.
                """.formatted(Year.now().getValue());
        try {
            String jsonText = geminiAdvisor.generateJsonCompletion(systemPrompt, "Pregunta: " + question);
            if (jsonText == null || jsonText.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(jsonText.trim());
            String ym = textOrNull(root, "yearMonth");
            if (ym != null && !YEAR_MONTH_PATTERN.matcher(ym).matches()) {
                ym = null;
            }
            String cat = textOrNull(root, "category");
            String block = textOrNull(root, "budgetBlock");
            if (block != null && !"NEEDS".equalsIgnoreCase(block) && !"WANTS".equalsIgnoreCase(block)) {
                block = null;
            }
            String merchant = textOrNull(root, "merchantKeyword");
            return new GeminiExtractedFilters(ym, cat, block != null ? block.toUpperCase(Locale.ROOT) : null, merchant);
        } catch (Exception ex) {
            log.debug("Extracción de filtros con Gemini omitida: {}", ex.getMessage());
            return null;
        }
    }

    private String synthesizeGroundedAnswerWithGemini(
            String question,
            Map<String, Object> queryMetadata,
            HybridSearchAndChatToolsService.SqlTransactionsToolResult sqlResult,
            List<HybridSearchAndChatToolsService.HybridChunkEvidence> verifiedChunks,
            String deterministicFallback
    ) {
        if (!isGeminiActive()) {
            return deterministicFallback;
        }
        String systemPrompt = """
                Eres el asistente financiero verificado de CuentaClara (Colombia, COP).
                Responde en español claro, cordial y conciso basándote EXCLUSIVAMENTE en los datos JSON verificados.
                REGLAS INQUEBRANTABLES (RNF-06):
                1. Usa únicamente los montos, conteos, fechas, comercios, categorías y extractos presentes en la evidencia SQL o chunks.
                2. Nunca inventes cifras, comercios, porcentajes, facturas ni páginas que no aparezcan en el JSON.
                3. Conserva las cifras exactas en pesos colombianos ($ ... COP) y menciona las referencias si ayudan al usuario.
                4. Máximo 3 a 5 frases directas sin markdown complejo.
                """;
        try {
            List<Map<String, Object>> rowSamples = sqlResult.rows().stream()
                    .limit(6)
                    .map(r -> Map.<String, Object>of(
                            "merchant", r.merchantName(),
                            "date", r.transactionDate().toString(),
                            "category", r.category(),
                            "totalCop", r.totalAmount(),
                            "taxCop", r.taxAmount(),
                            "citation", r.citationReference() != null ? r.citationReference() : ""
                    ))
                    .toList();
            List<Map<String, Object>> chunkSamples = verifiedChunks.stream()
                    .limit(4)
                    .map(c -> Map.<String, Object>of(
                            "reference", c.sourceReference() != null ? c.sourceReference() : "",
                            "page", c.pageNumber() != null ? c.pageNumber() : 1,
                            "snippet", c.contentText()
                    ))
                    .toList();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", question);
            payload.put("metadata", queryMetadata);
            payload.put("sqlSummary", Map.of(
                    "totalCop", sqlResult.totalAmount(),
                    "taxCop", sqlResult.totalTaxAmount(),
                    "count", sqlResult.count(),
                    "rows", rowSamples
            ));
            payload.put("documentChunks", chunkSamples);
            payload.put("verifiedDraft", deterministicFallback);

            String response = geminiAdvisor.generateGroundedResponse(
                    systemPrompt,
                    objectMapper.writeValueAsString(payload)
            );
            if (response != null && !response.isBlank()) {
                return response.trim();
            }
        } catch (Exception ex) {
            log.warn("No se pudo sintetizar respuesta con Gemini; usando borrador determinista: {}", ex.getMessage());
        }
        return deterministicFallback;
    }

    private String textOrNull(JsonNode root, String field) {
        if (!root.hasNonNull(field)) {
            return null;
        }
        String val = root.get(field).asText("").trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) ? null : val;
    }

    private record GeminiExtractedFilters(
            String yearMonth,
            String category,
            String budgetBlock,
            String merchantKeyword
    ) {
    }

    private String detectYearMonth(String lower) {
        Matcher ymMatcher = YEAR_MONTH_PATTERN.matcher(lower);
        if (ymMatcher.find()) {
            return ymMatcher.group(0);
        }
        String explicitYear = null;
        Matcher yearMatcher = EXPLICIT_YEAR_PATTERN.matcher(lower);
        if (yearMatcher.find()) {
            explicitYear = yearMatcher.group(1);
        }
        String resolvedYear = explicitYear != null ? explicitYear : String.valueOf(Year.now().getValue());
        for (Map.Entry<String, String> entry : SPANISH_MONTHS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return resolvedYear + "-" + entry.getValue();
            }
        }
        return null;
    }

    private String detectCategoryFilter(String lower) {
        if (lower.contains("domicilio") || lower.contains("restaurante") || lower.contains("comida fuera")) {
            return "Domicilios";
        }
        if (lower.contains("alimentación") || lower.contains("alimentacion") || lower.contains("mercado") || lower.contains("supermercado")) {
            return "Alimentación";
        }
        if (lower.contains("servicio") || lower.contains("energía") || lower.contains("energia") || lower.contains("internet") || lower.contains("acueducto")) {
            return "Servicios";
        }
        if (lower.contains("salud") || lower.contains("droguería") || lower.contains("drogueria") || lower.contains("medicina") || lower.contains("medicamento")) {
            return "Salud";
        }
        if (lower.contains("transporte") || lower.contains("gasolina") || lower.contains("combustible")) {
            return "Transporte";
        }
        if (lower.contains("vivienda") || lower.contains("arriendo") || lower.contains("alquiler")) {
            return "Vivienda";
        }
        if (lower.contains("panadería") || lower.contains("panaderia") || lower.contains("antojos")) {
            return "Panadería";
        }
        return null;
    }

    private String detectBudgetBlockFilter(String lower) {
        if (lower.contains("bloque de necesidades") || lower.contains("gastos esenciales") || lower.contains("en necesidades")) {
            return "NEEDS";
        }
        if (lower.contains("bloque de deseos") || lower.contains("en deseos")) {
            return "WANTS";
        }
        return null;
    }

    private String detectMerchantOrDocumentCode(String originalQuestion, String lower) {
        Matcher codeMatcher = NIT_OR_INVOICE_TOKEN.matcher(originalQuestion);
        if (codeMatcher.find()) {
            return codeMatcher.group(0);
        }

        List<String> knownMerchants = List.of(
                "éxito", "exito", "carulla", "d1", "ara", "olímpica", "olimpica", "alkosto",
                "rappi", "uber eats", "crepes", "corral", "juan valdez", "tostao",
                "epm", "enel", "vanti", "claro", "movistar", "tigo",
                "cruz verde", "farmatodo", "terpel", "primax"
        );
        for (String merchant : knownMerchants) {
            if (lower.contains(merchant)) {
                return merchant;
            }
        }
        return null;
    }

    private boolean isSpecificTopicQuery(String lower) {
        return !(lower.contains("total") && (lower.contains("mes") || lower.contains("octubre") || lower.contains("gasté") || lower.contains("gaste"))
                && !lower.contains("bitcoin") && !lower.contains("cripto") && !lower.contains("viaje a marte") && !lower.contains("joyería") && !lower.contains("joyeria"));
    }

    private List<HybridSearchAndChatToolsService.HybridChunkEvidence> filterRelevantChunks(
            String lowerQuestion,
            List<HybridSearchAndChatToolsService.HybridChunkEvidence> rawChunks
    ) {
        // Si la pregunta menciona términos que claramente deben coincidir léxicamente si no hubo match en categorías
        List<HybridSearchAndChatToolsService.HybridChunkEvidence> out = new ArrayList<>();
        String[] words = lowerQuestion.replaceAll("[¿?¡!.,;:()\"']", " ").split("\\s+");

        for (var chunk : rawChunks) {
            if (chunk.matchedLexically()) {
                out.add(chunk);
                continue;
            }
            String chunkLower = (chunk.contentText() + " " + chunk.sourceReference()).toLowerCase(Locale.ROOT);
            boolean sharesMeaningfulWord = false;
            for (String w : words) {
                if (w.length() >= 4
                        && !List.of("cuanto", "cuánto", "gaste", "gasté", "gasto", "gastos", "factura", "facturas", "octubre", "total", "como", "donde", "cual", "tengo", "pague", "pagué").contains(w)
                        && chunkLower.contains(w)) {
                    sharesMeaningfulWord = true;
                    break;
                }
            }
            if (sharesMeaningfulWord) {
                out.add(chunk);
            }
        }
        return out;
    }

    private List<String> collectCitations(
            List<HybridSearchAndChatToolsService.TransactionEvidenceRow> rows,
            List<HybridSearchAndChatToolsService.HybridChunkEvidence> chunks
    ) {
        Set<String> citations = new LinkedHashSet<>();
        for (var row : rows) {
            if (row.citationReference() != null && !row.citationReference().isBlank()) {
                citations.add(row.citationReference());
            }
        }
        for (var chunk : chunks) {
            if (chunk.sourceReference() != null && !chunk.sourceReference().isBlank()) {
                citations.add(chunk.sourceReference());
            }
        }
        return new ArrayList<>(citations);
    }

    public record ChatAnswerResponse(
            String question,
            String answer,
            List<String> citations,
            BigDecimal sqlComputedTotal,
            boolean hasEvidence
    ) {
    }
}
