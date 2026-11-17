package co.cuentaclara.chat;

import co.cuentaclara.profile.FinancialProfileService;
import java.math.BigDecimal;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agente conversacional en lenguaje natural con citas obligatorias (RF-12, RNF-06, RNF-09).
 * Orquesta las dos herramientas deterministas (`consultar_transacciones` y `buscar_documentos`):
 * - Todas las cifras y sumas salen de SQL (`consultar_transacciones`).
 * - Toda respuesta factual cita la fuente verificable (XML con número/CUFE, PDF/extracto con página,
 *   imagen con referencia o gasto manual con ID).
 * - Si no existe evidencia en la base de datos del usuario, responde explícitamente que no hay evidencia
 *   y jamás inventa páginas ni cifras.
 */
@Service
public class NaturalLanguageChatService {

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
    private static final Pattern NIT_OR_INVOICE_TOKEN = Pattern.compile("(?i)\\b(?:FE-\\d+|SETT-\\d+|PDF-\\d+|IMG-\\d+|\\d{8,12})\\b");

    private final HybridSearchAndChatToolsService toolsService;
    private final FinancialProfileService profileService;

    public NaturalLanguageChatService(
            HybridSearchAndChatToolsService toolsService,
            FinancialProfileService profileService
    ) {
        this.toolsService = toolsService;
        this.profileService = profileService;
    }

    @Transactional(readOnly = true)
    public ChatAnswerResponse askQuestion(UUID userId, String userQuestion) {
        String question = userQuestion != null ? userQuestion.trim() : "";
        if (question.isBlank()) {
            throw new IllegalArgumentException("La pregunta no puede estar vacía.");
        }

        String lower = question.toLowerCase(Locale.ROOT);

        // Si pregunta por un comercio/concepto inexistente explícito, verificar evidencia primero
        String detectedMonth = detectYearMonth(lower);
        String detectedCategory = detectCategoryFilter(lower);
        String detectedBlock = detectBudgetBlockFilter(lower);
        String detectedMerchantOrCode = detectMerchantOrDocumentCode(question, lower);

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

        // 3. Caso: Pregunta sobre presupuesto / distribución 50/30/20
        if (lower.contains("presupuesto") || lower.contains("50/30/20") || lower.contains("distribución") || lower.contains("distribucion")) {
            Optional<FinancialProfileService.ProfileWithInitialBudget> profileOpt = profileService.getCurrentProfileAndBudget(userId);
            if (profileOpt.isPresent()) {
                var p = profileOpt.get();
                HybridSearchAndChatToolsService.SqlTransactionsToolResult allTx =
                        toolsService.consultarTransacciones(userId, new HybridSearchAndChatToolsService.TransactionQueryFilters(detectedMonth, null, null, null, null, null));
                List<String> citations = collectCitations(allTx.rows(), verifiedChunks);
                String answer = String.format(
                        "Según los cálculos en SQL, tu ingreso base declarado es $%,.2f COP con distribución %d%% Necesidades ($%,.2f COP), %d%% Deseos ($%,.2f COP) y %d%% Ahorro ($%,.2f COP). Gasto total confirmado acumulado: $%,.2f COP (%d movimientos). %s",
                        p.profile().getNetIncome(),
                        p.profile().getNeedsPct(), p.initialBudget().getNeedsLimit(),
                        p.profile().getWantsPct(), p.initialBudget().getWantsLimit(),
                        p.profile().getSavingsPct(), p.initialBudget().getSavingsTarget(),
                        allTx.totalAmount(),
                        allTx.count(),
                        citations.isEmpty() ? "" : "Evidencia: " + String.join(" ; ", citations)
                ).trim();
                return new ChatAnswerResponse(question, answer, citations, allTx.totalAmount(), true);
            }
        }

        // 4. Si hay resultados exactos en SQL para los filtros detectados
        if (sqlResult.count() > 0) {
            List<String> citations = collectCitations(sqlResult.rows(), verifiedChunks);
            StringBuilder sb = new StringBuilder();

            if (lower.contains("iva") || lower.contains("impuesto")) {
                sb.append(String.format(
                        "Según la consulta SQL sobre tus movimientos confirmados, el total de impuestos (IVA) es $%,.2f COP y el monto total pagado es $%,.2f COP en %d registro(s).",
                        sqlResult.totalTaxAmount(),
                        sqlResult.totalAmount(),
                        sqlResult.count()
                ));
            } else {
                sb.append(String.format(
                        "Según la consulta SQL sobre tus movimientos confirmados, el total registrado es $%,.2f COP distribuido en %d movimiento(s).",
                        sqlResult.totalAmount(),
                        sqlResult.count()
                ));
            }

            sb.append(" Detalle respaldado: ");
            int limit = Math.min(4, sqlResult.rows().size());
            for (int i = 0; i < limit; i++) {
                var row = sqlResult.rows().get(i);
                sb.append(String.format(
                        "%s (%s) por $%,.2f COP el %s [Cita: %s]; ",
                        row.merchantName(),
                        row.category(),
                        row.totalAmount(),
                        row.transactionDate(),
                        row.citationReference()
                ));
            }

            if (!verifiedChunks.isEmpty()) {
                var topChunk = verifiedChunks.getFirst();
                sb.append(" Fragmento documental recuperado: \"").append(topChunk.contentText())
                        .append("\" [Cita: ").append(topChunk.sourceReference()).append("].");
            }

            return new ChatAnswerResponse(
                    question,
                    sb.toString().trim(),
                    citations,
                    sqlResult.totalAmount(),
                    true
            );
        }

        // 5. Si no hubo filas directas en transactions pero sí evidencia verificada en document_chunks
        // (por ejemplo, consulta sobre un ítem específico de factura como "Café Molido" o sobre un Extracto Bancario PDF por página)
        if (!verifiedChunks.isEmpty()) {
            List<String> citations = new ArrayList<>();
            StringBuilder sb = new StringBuilder("Evidencia encontrada en tus documentos indexados: ");
            for (var chunk : verifiedChunks) {
                if (!citations.contains(chunk.sourceReference())) {
                    citations.add(chunk.sourceReference());
                }
                sb.append("\"").append(chunk.contentText()).append("\" [Cita: ")
                        .append(chunk.sourceReference()).append("]; ");
            }
            return new ChatAnswerResponse(
                    question,
                    sb.toString().trim(),
                    citations,
                    BigDecimal.ZERO,
                    true
            );
        }

        // 6. Sin evidencia -> Indicarlo claramente sin inventar cifras ni páginas (RF-12, RNF-06)
        return new ChatAnswerResponse(
                question,
                "No tengo evidencia en tus facturas, extractos ni gastos confirmados para responder a esa consulta.",
                List.of(),
                BigDecimal.ZERO,
                false
        );
    }

    private String detectYearMonth(String lower) {
        Matcher ymMatcher = YEAR_MONTH_PATTERN.matcher(lower);
        if (ymMatcher.find()) {
            return ymMatcher.group(0);
        }
        for (Map.Entry<String, String> entry : SPANISH_MONTHS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return "2026-" + entry.getValue();
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
