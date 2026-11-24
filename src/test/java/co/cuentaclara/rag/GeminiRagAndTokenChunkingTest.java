package co.cuentaclara.rag;

import co.cuentaclara.chat.HybridSearchAndChatToolsService;
import co.cuentaclara.chat.NaturalLanguageChatService;
import co.cuentaclara.document.ocr.StructuredInvoiceTextExtractor;
import co.cuentaclara.report.GeminiFinancialAdvisorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiRagAndTokenChunkingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Bloque 3 (RF-07): Chunking por tokens (~500-800 tokens) con superposición contextual")
    void shouldSplitLongTextIntoOverlappingTokenChunks() {
        EmbeddingProvider dummyEmbedding = new EmbeddingProvider() {
            @Override
            public float[] generateEmbedding(String text) {
                return new float[0];
            }

            @Override
            public String modelName() {
                return "stub";
            }

            @Override
            public String modelVersion() {
                return "v1";
            }
        };
        RagIndexingAndCategorizationService ragService = new RagIndexingAndCategorizationService(
                null, null, dummyEmbedding, objectMapper
        );

        // Construir extracto extenso (~1.400 tokens)
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 320; i++) {
            sb.append("Movimiento ").append(i)
              .append(" Supermercado Colombia factura FE-").append(i)
              .append(" valor 45000 COP. ");
        }
        String longText = sb.toString();
        int totalTokens = ragService.estimateTokenCount(longText);
        assertThat(totalTokens).isGreaterThan(1200);

        List<String> chunks = ragService.splitIntoOverlappingWindows(longText, 600, 90);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(3);

        for (String chunk : chunks) {
            int chunkTokens = ragService.estimateTokenCount(chunk);
            assertThat(chunkTokens).isBetween(80, 620);
        }

        // Verificar superposición entre chunk 0 y chunk 1
        String endOfFirst = chunks.get(0).substring(Math.max(0, chunks.get(0).length() - 60));
        assertThat(chunks.get(1)).contains(endOfFirst.trim().split("\\s+")[1]);
    }

    @Test
    @DisplayName("Bloque 3 (RF-04): StructuredInvoiceTextExtractor usa fallback JSON de Gemini en recibos no convencionales")
    void shouldRecoverUnstructuredReceiptViaGeminiJsonFallback() {
        GeminiFinancialAdvisorService stubGemini = new GeminiFinancialAdvisorService(objectMapper, "fake-key", "gemini-2.5-flash") {
            @Override
            protected String callGemini(String systemInstruction, String userText, double temperature, boolean jsonMode) {
                assertThat(jsonMode).isTrue();
                return """
                        {
                          "merchantName": "VETERINARIA HUELLITAS S.A.S.",
                          "merchantNit": "901444555",
                          "invoiceNumber": "VET-778",
                          "issueDate": "2025-03-14",
                          "subtotalAmount": 100000,
                          "taxAmount": 19000,
                          "totalAmount": 119000,
                          "items": [{"description": "Consulta y vacuna mascota", "quantity": 1, "amount": 100000}]
                        }
                        """;
            }
        };

        StructuredInvoiceTextExtractor extractor = new StructuredInvoiceTextExtractor(objectMapper, stubGemini);
        // Tirilla desordenada sin etiquetas explícitas "Comercio:", "Total:" ni "Factura:"
        String messyReceipt = """
                Gracias por su visita el 14/03/2025
                Atencion veterinaria integral
                Pago tarjeta debito exitoso por 119 mil pesos IVA incluido
                """;

        var parsed = extractor.extractFromRawText(messyReceipt);
        assertThat(parsed.merchantName()).isEqualTo("VETERINARIA HUELLITAS S.A.S.");
        assertThat(parsed.merchantNit()).isEqualTo("901444555");
        assertThat(parsed.invoiceNumber()).isEqualTo("VET-778");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("119000");
        assertThat(parsed.items()).hasSize(1);
    }

    @Test
    @DisplayName("Bloque 3 (RF-12, RNF-06): NaturalLanguageChatService integra Gemini para filtros abiertos (año 2025, comercio libre), rechaza sin evidencia y hace fallback si Gemini falla")
    void shouldIntegrateGeminiQueryUnderstandingGroundedSynthesisAndFallback() {
        UUID userId = UUID.randomUUID();
        var vetRow = new HybridSearchAndChatToolsService.TransactionEvidenceRow(
                UUID.randomUUID(),
                "VETERINARIA HUELLITAS S.A.S.",
                "901444555",
                "VET-778",
                LocalDate.of(2025, 3, 14),
                new BigDecimal("100000.00"),
                new BigDecimal("19000.00"),
                new BigDecimal("119000.00"),
                "Salud",
                "NEEDS",
                "Vacuna",
                "XML:VET-778|COMERCIO:VETERINARIA HUELLITAS S.A.S."
        );

        HybridSearchAndChatToolsService tools = new HybridSearchAndChatToolsService(null, null) {
            @Override
            public SqlTransactionsToolResult consultarTransacciones(UUID uid, TransactionQueryFilters f) {
                if ("2025-03".equals(f.yearMonth())
                        && f.merchantKeyword() != null
                        && f.merchantKeyword().toLowerCase().contains("huellitas")) {
                    return new SqlTransactionsToolResult(
                            new BigDecimal("119000.00"),
                            new BigDecimal("19000.00"),
                            1,
                            List.of(vetRow)
                    );
                }
                return new SqlTransactionsToolResult(BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of());
            }

            @Override
            public List<HybridChunkEvidence> buscarDocumentos(UUID uid, String queryText, int topK) {
                return List.of();
            }
        };

        AtomicInteger geminiCalls = new AtomicInteger(0);
        GeminiFinancialAdvisorService stubGemini = new GeminiFinancialAdvisorService(objectMapper, "fake-key", "gemini-2.5-flash") {
            @Override
            protected String callGemini(String systemInstruction, String userText, double temperature, boolean jsonMode) {
                geminiCalls.incrementAndGet();
                if (jsonMode) {
                    if (userText.contains("Marte")) {
                        return "{\"yearMonth\": null, \"category\": null, \"budgetBlock\": null, \"merchantKeyword\": \"Marte\"}";
                    }
                    return """
                            {"yearMonth": "2025-03", "category": null, "budgetBlock": null, "merchantKeyword": "Huellitas"}
                            """;
                }
                return "En marzo de 2025 registraste $ 119.000,00 COP en VETERINARIA HUELLITAS S.A.S. (factura VET-778).";
            }
        };

        NaturalLanguageChatService chatService = new NaturalLanguageChatService(tools, null, stubGemini, objectMapper, true);

        // 1. Consulta abierta con año 2025 y comercio no incluido en listas fijas -> Gemini extrae filtros + redacta grounded
        var okResponse = chatService.askQuestion(userId, "¿Cuánto pagué en Huellitas en marzo 2025?");
        assertThat(okResponse.hasEvidence()).isTrue();
        assertThat(okResponse.sqlComputedTotal()).isEqualByComparingTo("119000.00");
        assertThat(okResponse.answer()).contains("119.000").contains("VETERINARIA HUELLITAS");
        assertThat(okResponse.citations()).containsExactly("XML:VET-778|COMERCIO:VETERINARIA HUELLITAS S.A.S.");
        assertThat(geminiCalls.get()).isEqualTo(2);

        // 2. Consulta sin evidencia -> debe responder honestamente sin alucinar cifras
        var noEvidence = chatService.askQuestion(userId, "¿Cuánto gasté en viajes espaciales a Marte?");
        assertThat(noEvidence.hasEvidence()).isFalse();
        assertThat(noEvidence.citations()).isEmpty();
        assertThat(noEvidence.answer()).contains("No tengo evidencia");

        // 3. Si Gemini lanza excepción HTTP 429 al sintetizar -> fallback limpio determinista
        GeminiFinancialAdvisorService failingSynthGemini = new GeminiFinancialAdvisorService(objectMapper, "fake-key", "gemini-2.5-flash") {
            @Override
            protected String callGemini(String systemInstruction, String userText, double temperature, boolean jsonMode) {
                if (jsonMode) {
                    return "{\"yearMonth\": \"2025-03\", \"category\": null, \"budgetBlock\": null, \"merchantKeyword\": \"Huellitas\"}";
                }
                throw new IllegalStateException("HTTP 429 Rate limit");
            }
        };
        NaturalLanguageChatService fallbackChat = new NaturalLanguageChatService(tools, null, failingSynthGemini, objectMapper, true);
        var fallbackResp = fallbackChat.askQuestion(userId, "¿Cuánto pagué en Huellitas en marzo 2025?");
        assertThat(fallbackResp.hasEvidence()).isTrue();
        assertThat(fallbackResp.sqlComputedTotal()).isEqualByComparingTo("119000.00");
        assertThat(fallbackResp.answer()).contains("VETERINARIA HUELLITAS");
    }
}
