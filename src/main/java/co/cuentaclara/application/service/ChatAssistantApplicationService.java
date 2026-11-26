package co.cuentaclara.application.service;

import co.cuentaclara.domain.model.ChatAnswerResponse;
import co.cuentaclara.domain.model.HybridChunkEvidence;
import co.cuentaclara.domain.model.ProfileWithInitialBudget;
import co.cuentaclara.domain.model.SqlTransactionsToolResult;
import co.cuentaclara.domain.model.TransactionEvidenceRow;
import co.cuentaclara.domain.model.TransactionQueryFilters;
import co.cuentaclara.domain.port.in.ChatAssistantUseCase;
import co.cuentaclara.domain.port.in.FinancialProfileUseCase;
import co.cuentaclara.domain.port.out.AiCacheAndCostGuardPort;
import co.cuentaclara.domain.port.out.EmbeddingPort;
import co.cuentaclara.domain.port.out.FinancialAdvisorAiPort;
import co.cuentaclara.domain.port.out.HybridVectorSearchPort;
import co.cuentaclara.domain.port.out.JsonSerializationPort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatAssistantApplicationService implements ChatAssistantUseCase {

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

    private final HybridVectorSearchPort hybridSearchPort;
    private final FinancialProfileUseCase profileUseCase;
    private final FinancialAdvisorAiPort advisorAiPort;
    private final JsonSerializationPort jsonSerializationPort;
    private final AiCacheAndCostGuardPort costGuardAndCache;
    private final EmbeddingPort embeddingProvider;
    private final TransactionRunnerPort transactionRunner;
    private final boolean geminiEnabled;

    public ChatAssistantApplicationService(
            HybridVectorSearchPort hybridSearchPort,
            FinancialProfileUseCase profileUseCase,
            FinancialAdvisorAiPort advisorAiPort,
            JsonSerializationPort jsonSerializationPort,
            AiCacheAndCostGuardPort costGuardAndCache,
            EmbeddingPort embeddingProvider,
            TransactionRunnerPort transactionRunner,
            boolean geminiEnabled
    ) {
        this.hybridSearchPort = hybridSearchPort;
        this.profileUseCase = profileUseCase;
        this.advisorAiPort = advisorAiPort;
        this.jsonSerializationPort = jsonSerializationPort;
        this.costGuardAndCache = costGuardAndCache;
        this.embeddingProvider = embeddingProvider;
        this.transactionRunner = transactionRunner;
        this.geminiEnabled = geminiEnabled;
    }

    @Override
    public ChatAnswerResponse askQuestion(UUID userId, String userQuestion) {
        return transactionRunner.runInReadOnlyTransaction(() -> {
            String question = userQuestion != null ? userQuestion.trim() : "";
            if (question.isBlank()) {
                throw new IllegalArgumentException("La pregunta no puede estar vacía.");
            }

            String lower = question.toLowerCase(Locale.ROOT);
            boolean aiQuotaAvailable = costGuardAndCache == null || costGuardAndCache.tryConsumeDailyAiCall(userId);

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
                TransactionQueryFilters aiFilters = extractFiltersWithGemini(question);
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

            TransactionQueryFilters sqlFilters = new TransactionQueryFilters(
                    detectedMonth, null, null, detectedCategory, detectedBlock, detectedMerchantOrCode
            );
            SqlTransactionsToolResult sqlResult = hybridSearchPort.consultarTransacciones(userId, sqlFilters);
            List<HybridChunkEvidence> docChunks = hybridSearchPort.buscarDocumentos(userId, question, 6);
            List<HybridChunkEvidence> verifiedChunks = filterRelevantChunks(lower, docChunks);

            if (isBudgetOverviewQuestion) {
                Optional<ProfileWithInitialBudget> profileOpt = profileUseCase.getCurrentProfileAndBudget(userId);
                if (profileOpt.isPresent()) {
                    var p = profileOpt.get();
                    SqlTransactionsToolResult allTx = hybridSearchPort.consultarTransacciones(
                            userId, new TransactionQueryFilters(detectedMonth, null, null, null, null, null)
                    );
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
                            ? synthesizeGroundedAnswerWithGemini(question, allTx, verifiedChunks, deterministicAnswer)
                            : deterministicAnswer;
                    return new ChatAnswerResponse(question, finalAnswer, citations, allTx.totalAmount(), true);
                }
            }

            if (sqlResult.count() > 0) {
                List<String> citations = collectCitations(sqlResult.rows(), verifiedChunks);
                StringBuilder sb = new StringBuilder();

                if (lower.contains("iva") || lower.contains("impuesto")) {
                    sb.append(String.format(
                            "En tus movimientos confirmados tienes registrado un total de IVA e impuestos de $%,.2f COP, con un monto total pagado de $%,.2f COP en %d comprobante(s).",
                            sqlResult.totalTaxAmount(), sqlResult.totalAmount(), sqlResult.count()
                    ));
                } else if (detectedCategory != null) {
                    sb.append(String.format(
                            "Encontré un total de $%,.2f COP registrado en la categoría de %s, correspondiente a %d movimiento(s).",
                            sqlResult.totalAmount(), detectedCategory, sqlResult.count()
                    ));
                } else if (detectedBlock != null) {
                    String blockName = "NEEDS".equalsIgnoreCase(detectedBlock) ? "Necesidades" : "Deseos";
                    sb.append(String.format(
                            "En tus gastos del bloque de %s tienes un total acumulado de $%,.2f COP en %d movimiento(s).",
                            blockName, sqlResult.totalAmount(), sqlResult.count()
                    ));
                } else if (detectedMerchantOrCode != null) {
                    sb.append(String.format(
                            "Encontré compras confirmadas para %s por un valor total de $%,.2f COP en %d comprobante(s).",
                            detectedMerchantOrCode.toUpperCase(Locale.ROOT), sqlResult.totalAmount(), sqlResult.count()
                    ));
                } else {
                    sb.append(String.format(
                            "Revisé tus registros confirmados y encontré un total de $%,.2f COP en %d movimiento(s).",
                            sqlResult.totalAmount(), sqlResult.count()
                    ));
                }

                sb.append(" Los movimientos destacados son: ");
                int limit = Math.min(4, sqlResult.rows().size());
                for (int i = 0; i < limit; i++) {
                    var row = sqlResult.rows().get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(String.format(
                            "%s (%s) por $%,.2f COP el %s",
                            row.merchantName(), row.category(), row.totalAmount(), row.transactionDate()
                    ));
                }
                sb.append(".");

                if (!verifiedChunks.isEmpty()) {
                    sb.append(" Además, en el comprobante se detalla: \"").append(verifiedChunks.getFirst().contentText()).append("\".");
                }

                String deterministicAnswer = sb.toString().trim();
                String finalAnswer = aiQuotaAvailable
                        ? synthesizeGroundedAnswerWithGemini(question, sqlResult, verifiedChunks, deterministicAnswer)
                        : deterministicAnswer;

                return new ChatAnswerResponse(question, finalAnswer, citations, sqlResult.totalAmount(), true);
            }

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
                        ? synthesizeGroundedAnswerWithGemini(question, sqlResult, verifiedChunks, deterministicAnswer)
                        : deterministicAnswer;
                return new ChatAnswerResponse(question, finalAnswer, citations, BigDecimal.ZERO, true);
            }

            return new ChatAnswerResponse(
                    question,
                    "No tengo evidencia en tus facturas, extractos ni gastos confirmados para responder a esa consulta. Si tienes el comprobante, puedes subirlo en la sección de Cargar y Confirmar.",
                    List.of(),
                    BigDecimal.ZERO,
                    false
            );
        });
    }

    @Override
    public SqlTransactionsToolResult consultarTransacciones(UUID userId, TransactionQueryFilters filters) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                hybridSearchPort.consultarTransacciones(userId, filters)
        );
    }

    @Override
    public List<HybridChunkEvidence> buscarDocumentos(UUID userId, String queryText, int topK) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                hybridSearchPort.buscarDocumentos(userId, queryText, topK)
        );
    }

    private boolean isGeminiActive() {
        return geminiEnabled && advisorAiPort != null && advisorAiPort.isGeminiConfigured();
    }

    private TransactionQueryFilters extractFiltersWithGemini(String question) {
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
            String jsonText = advisorAiPort.generateJsonCompletion(systemPrompt, "Pregunta: " + question);
            return jsonSerializationPort.parseChatIntentFilters(jsonText).orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private String synthesizeGroundedAnswerWithGemini(
            String question,
            SqlTransactionsToolResult sqlResult,
            List<HybridChunkEvidence> verifiedChunks,
            String deterministicFallback
    ) {
        if (!isGeminiActive()) {
            return deterministicFallback;
        }
        String systemPrompt = """
                Eres el asistente financiero verificado de CuentaClara (Colombia, COP).
                Responde en español claro, cordial y conciso basándote EXCLUSIVAMENTE en los datos verificados.
                REGLAS INQUEBRANTABLES (RNF-06):
                1. Usa únicamente los montos, conteos, fechas, comercios, categorías y extractos presentes en la evidencia.
                2. Nunca inventes cifras, comercios, porcentajes, facturas ni páginas que no aparezcan en el resumen.
                3. Conserva las cifras exactas en pesos colombianos ($ ... COP) y menciona las referencias si ayudan al usuario.
                4. Máximo 3 a 5 frases directas sin markdown complejo.
                """;
        try {
            StringBuilder evidenceText = new StringBuilder();
            evidenceText.append("Pregunta: ").append(question).append("\n");
            evidenceText.append("Total SQL: ").append(sqlResult.totalAmount()).append(" COP (").append(sqlResult.count()).append(" registros)\n");
            for (var r : sqlResult.rows().stream().limit(6).toList()) {
                evidenceText.append("- ").append(r.merchantName()).append(" | ").append(r.transactionDate())
                        .append(" | ").append(r.category()).append(" | ").append(r.totalAmount())
                        .append(" COP | Cita: ").append(r.citationReference()).append("\n");
            }
            for (var c : verifiedChunks.stream().limit(4).toList()) {
                evidenceText.append("- Chunk ").append(c.sourceReference()).append(": ").append(c.contentText()).append("\n");
            }
            evidenceText.append("Borrador verificado: ").append(deterministicFallback);

            String response = advisorAiPort.generateGroundedResponse(systemPrompt, evidenceText.toString());
            if (response != null && !response.isBlank()) {
                return response.trim();
            }
        } catch (Exception ignored) {
        }
        return deterministicFallback;
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

    private List<HybridChunkEvidence> filterRelevantChunks(String lowerQuestion, List<HybridChunkEvidence> rawChunks) {
        List<HybridChunkEvidence> out = new ArrayList<>();
        String[] words = lowerQuestion.replaceAll("[¿?¡!.,;:()\"']", " ").split("\\s+");

        boolean semanticRecallEnabled = embeddingProvider != null && embeddingProvider.isRealSemanticEmbeddingActive();
        for (var chunk : rawChunks) {
            if (chunk.matchedLexically()) {
                out.add(chunk);
                continue;
            }
            if (semanticRecallEnabled && chunk.vectorScore() >= 0.35) {
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

    private List<String> collectCitations(List<TransactionEvidenceRow> rows, List<HybridChunkEvidence> chunks) {
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
}
