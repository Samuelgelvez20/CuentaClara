package co.cuentaclara.rag;

import co.cuentaclara.application.service.ChatAssistantApplicationService;
import co.cuentaclara.application.service.RagIndexingApplicationService;
import co.cuentaclara.domain.model.DocumentSourceType;
import co.cuentaclara.domain.model.HybridChunkEvidence;
import co.cuentaclara.domain.model.SqlTransactionsToolResult;
import co.cuentaclara.domain.model.TransactionEvidenceRow;
import co.cuentaclara.domain.model.TransactionQueryFilters;
import co.cuentaclara.domain.port.out.EmbeddingPort;
import co.cuentaclara.domain.port.out.HybridVectorSearchPort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import co.cuentaclara.infrastructure.adapter.out.ai.GeminiFinancialAdvisorAdapter;
import co.cuentaclara.infrastructure.adapter.out.document.parser.PdfAndOcrExtractionService;
import co.cuentaclara.infrastructure.adapter.out.document.parser.StructuredInvoiceTextExtractor;
import co.cuentaclara.infrastructure.adapter.out.persistence.JacksonJsonAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiRagAndTokenChunkingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonJsonAdapter jsonAdapter = new JacksonJsonAdapter(objectMapper);
    private final TransactionRunnerPort passthroughTx = new TransactionRunnerPort() {
        @Override
        public <T> T runInTransaction(java.util.function.Supplier<T> action) {
            return action.get();
        }

        @Override
        public <T> T runInReadOnlyTransaction(java.util.function.Supplier<T> action) {
            return action.get();
        }
    };

    @Test
    @DisplayName("Bloque 3 (RF-07): Chunking por tokens (~500-800 tokens) con superposición contextual")
    void shouldSplitLongTextIntoOverlappingTokenChunks() {
        RagIndexingApplicationService ragService = new RagIndexingApplicationService(
                null, null, null, null, passthroughTx
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
        GeminiFinancialAdvisorAdapter stubGemini = new GeminiFinancialAdvisorAdapter(objectMapper, "fake-key", "gemini-2.5-flash") {
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
        var vetRow = new TransactionEvidenceRow(
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

        HybridVectorSearchPort tools = new HybridVectorSearchPort() {
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
        GeminiFinancialAdvisorAdapter stubGemini = new GeminiFinancialAdvisorAdapter(objectMapper, "fake-key", "gemini-2.5-flash") {
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

        ChatAssistantApplicationService chatService = new ChatAssistantApplicationService(
                tools, null, stubGemini, jsonAdapter, null, null, passthroughTx, true
        );

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
        GeminiFinancialAdvisorAdapter failingSynthGemini = new GeminiFinancialAdvisorAdapter(objectMapper, "fake-key", "gemini-2.5-flash") {
            @Override
            protected String callGemini(String systemInstruction, String userText, double temperature, boolean jsonMode) {
                if (jsonMode) {
                    return "{\"yearMonth\": \"2025-03\", \"category\": null, \"budgetBlock\": null, \"merchantKeyword\": \"Huellitas\"}";
                }
                throw new IllegalStateException("HTTP 429 Rate limit");
            }
        };
        ChatAssistantApplicationService fallbackChat = new ChatAssistantApplicationService(
                tools, null, failingSynthGemini, jsonAdapter, null, null, passthroughTx, true
        );
        var fallbackResp = fallbackChat.askQuestion(userId, "¿Cuánto pagué en Huellitas en marzo 2025?");
        assertThat(fallbackResp.hasEvidence()).isTrue();
        assertThat(fallbackResp.sqlComputedTotal()).isEqualByComparingTo("119000.00");
        assertThat(fallbackResp.answer()).contains("VETERINARIA HUELLITAS");
    }

    @Test
    @DisplayName("Semantic recall activo permite fragmentos por similitud vectorial sin coincidencia literal exacta")
    void shouldAllowPureSemanticMatchesOnlyWhenRealSemanticEmbeddingActive() {
        UUID userId = UUID.randomUUID();
        var semanticChunk = new HybridChunkEvidence(
                UUID.randomUUID(),
                "DOCUMENT_CONTENT",
                2,
                "PDF:POL-10|PÁGINA:2",
                "Cláusula de cobertura odontológica y urgencias ambulatorias incluidas.",
                0.0161,
                false,
                0.78
        );

        HybridVectorSearchPort tools = new HybridVectorSearchPort() {
            @Override
            public SqlTransactionsToolResult consultarTransacciones(UUID uid, TransactionQueryFilters f) {
                return new SqlTransactionsToolResult(BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of());
            }

            @Override
            public List<HybridChunkEvidence> buscarDocumentos(UUID uid, String queryText, int topK) {
                return List.of(semanticChunk);
            }
        };

        EmbeddingPort offlineEmbedding = new EmbeddingPort() {
            @Override public float[] generateEmbedding(String text) { return new float[0]; }
            @Override public String modelName() { return "local-semantic-v1"; }
            @Override public String modelVersion() { return "v1"; }
            @Override public boolean isRealSemanticEmbeddingActive() { return false; }
        };
        ChatAssistantApplicationService offlineChat = new ChatAssistantApplicationService(
                tools, null, null, jsonAdapter, null, offlineEmbedding, passthroughTx, false
        );
        assertThat(offlineChat.askQuestion(userId, "¿Qué beneficios dentales cubre mi póliza?").hasEvidence()).isFalse();

        EmbeddingPort realSemanticEmbedding = new EmbeddingPort() {
            @Override public float[] generateEmbedding(String text) { return new float[0]; }
            @Override public String modelName() { return "text-embedding-3-small"; }
            @Override public String modelVersion() { return "v1"; }
            @Override public boolean isRealSemanticEmbeddingActive() { return true; }
        };
        ChatAssistantApplicationService realSemanticChat = new ChatAssistantApplicationService(
                tools, null, null, jsonAdapter, null, realSemanticEmbedding, passthroughTx, false
        );
        var resp = realSemanticChat.askQuestion(userId, "¿Qué beneficios dentales cubre mi póliza?");
        assertThat(resp.hasEvidence()).isTrue();
        assertThat(resp.citations()).contains("PDF:POL-10|PÁGINA:2");
    }

    @Test
    @DisplayName("OCR por página procesa PDF híbrido (página 1 digital + página 2 escaneada)")
    void shouldRunOcrPerPageOnHybridPdf() throws Exception {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(50, 720);
                cs.showText("Comercio: CLINICA DENTAL SONRISA NIT 900111222 Factura HYB-55 Fecha 2026-10-15");
                cs.endText();
            }
            // Página 2 sin capa digital de texto (simula página escaneada)
            doc.addPage(new PDPage());
            doc.save(baos);
            String trailerMarker = "\n%OCR_TEXT_PAYLOAD:Total: 250000 Impuesto: 0 Limpieza y ortodoncia";
            byte[] raw = baos.toByteArray();
            byte[] markerBytes = trailerMarker.getBytes(StandardCharsets.UTF_8);
            pdfBytes = new byte[raw.length + markerBytes.length];
            System.arraycopy(raw, 0, pdfBytes, 0, raw.length);
            System.arraycopy(markerBytes, 0, pdfBytes, raw.length, markerBytes.length);
        }

        StructuredInvoiceTextExtractor extractor = new StructuredInvoiceTextExtractor(objectMapper, null);
        PdfAndOcrExtractionService ocrService = new PdfAndOcrExtractionService(extractor, "/nonexistent-tessdata", "spa+eng");
        var outcome = ocrService.extractFromPdfOrImage(pdfBytes, DocumentSourceType.PDF_DIGITAL);

        assertThat(outcome.pageCount()).isEqualTo(2);
        assertThat(outcome.extractionMethod()).isEqualTo("PDFBOX_HYBRID_OCR_PDF");
        assertThat(outcome.resolvedSourceType()).isEqualTo(DocumentSourceType.PDF_SCANNED);
        assertThat(outcome.pages().get(0).text()).contains("CLINICA DENTAL SONRISA");
        assertThat(outcome.pages().get(1).text()).contains("Limpieza y ortodoncia");
    }
}
