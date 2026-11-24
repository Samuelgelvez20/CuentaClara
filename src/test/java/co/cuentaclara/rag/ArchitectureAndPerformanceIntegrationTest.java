package co.cuentaclara.rag;

import co.cuentaclara.chat.HybridSearchAndChatToolsService;
import co.cuentaclara.chat.NaturalLanguageChatService;
import co.cuentaclara.security.rls.RlsDataSourceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "cuentaclara.chat.gemini-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class ArchitectureAndPerformanceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("cuentaclara")
            .withUsername("cuentaclara_owner")
            .withPassword("owner_secret_dev");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "cuentaclara_app");
        registry.add("spring.datasource.password", () -> "app_secret_dev");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingProvider primaryEmbeddingProvider;

    @Autowired
    private HybridSearchAndChatToolsService searchTools;

    @Autowired
    private NaturalLanguageChatService chatService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("Bloque 5 (RNF-08, RNF-10): OllamaEmbeddingProvider intercambiable + caché LRU + límite de cuota diaria")
    void shouldSwitchEmbeddingProviderToOllamaWithLruCacheAndEnforceQuota() throws Exception {
        // Levantar servidor HTTP local simulando Ollama `/api/embeddings` con vector de 768 dims
        AtomicInteger ollamaHttpCalls = new AtomicInteger(0);
        HttpServer mockOllama = HttpServer.create(new InetSocketAddress(0), 0);
        mockOllama.createContext("/api/embeddings", exchange -> {
            ollamaHttpCalls.incrementAndGet();
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < 768; i++) {
                if (i > 0) arr.append(',');
                arr.append(i == 0 ? "0.6" : "0.01");
            }
            arr.append(']');
            byte[] resp = ("{\"embedding\":" + arr + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockOllama.start();

        try {
            String baseUrl = "http://localhost:" + mockOllama.getAddress().getPort();
            OllamaEmbeddingProvider ollama = new OllamaEmbeddingProvider(objectMapper, baseUrl, "nomic-embed-text", "v1");
            OpenAiCompatibleEmbeddingProvider openAi = new OpenAiCompatibleEmbeddingProvider("", "text-embedding-3-small", "v1", objectMapper);
            AiCostGuardAndCacheService costGuard = new AiCostGuardAndCacheService(2);

            RoutingEmbeddingProvider router = new RoutingEmbeddingProvider(
                    "ollama", openAi, ollama, costGuard, objectMapper
            );
            assertThat(router.modelName()).isEqualTo("ollama:nomic-embed-text");

            float[] v1 = router.generateEmbedding("Factura de electricidad EPM");
            float[] v2 = router.generateEmbedding("Factura de electricidad EPM");

            assertThat(v1).hasSize(1536);
            assertThat(v2).hasSize(1536);
            // Segunda llamada idéntica viene de caché LRU (RNF-10), una sola llamada HTTP a Ollama
            assertThat(ollamaHttpCalls.get()).isEqualTo(1);
            assertThat(costGuard.getCacheHits()).isEqualTo(1);
            assertThat(costGuard.getCacheMisses()).isEqualTo(1);

            // Cuota diaria de IA por usuario (RNF-10)
            UUID uid = UUID.randomUUID();
            assertThat(costGuard.tryConsumeDailyAiCall(uid)).isTrue();
            assertThat(costGuard.tryConsumeDailyAiCall(uid)).isTrue();
            assertThat(costGuard.tryConsumeDailyAiCall(uid)).isFalse();
            assertThat(costGuard.getRemainingDailyCalls(uid)).isZero();
        } finally {
            mockOllama.stop(0);
        }
    }

    @Test
    @DisplayName("Bloque 5 (RNF-07, RNF-08): Verifica compatibilidad índice HNSW pgvector y latencia < 30s ingesta / < 10s chat")
    void shouldVerifyHnswIndexPlanAndOperationalLatencyUnderTargets() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Benchmark HNSW",
                                  "email": "benchmark.hnsw@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        var regJson = objectMapper.readTree(reg.getResponse().getContentAsString());
        UUID userId = UUID.fromString(regJson.get("userId").asText());
        String token = regJson.get("token").asText();

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                    <cbc:ID>BENCH-100</cbc:ID>
                    <cbc:UUID schemeName="CUFE-SHA384">1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff</cbc:UUID>
                    <cbc:IssueDate>2026-10-22</cbc:IssueDate>
                    <cac:AccountingSupplierParty>
                        <cac:Party><cac:PartyTaxScheme>
                            <cbc:RegistrationName>ALMACENES ÉXITO S.A.</cbc:RegistrationName>
                            <cbc:CompanyID>890900608</cbc:CompanyID>
                        </cac:PartyTaxScheme></cac:Party>
                    </cac:AccountingSupplierParty>
                    <cac:TaxTotal><cbc:TaxAmount currencyID="COP">9500.00</cbc:TaxAmount></cac:TaxTotal>
                    <cac:LegalMonetaryTotal>
                        <cbc:LineExtensionAmount currencyID="COP">50000.00</cbc:LineExtensionAmount>
                        <cbc:PayableAmount currencyID="COP">59500.00</cbc:PayableAmount>
                    </cac:LegalMonetaryTotal>
                    <cac:InvoiceLine>
                        <cbc:ID>1</cbc:ID><cbc:InvoicedQuantity>1</cbc:InvoicedQuantity>
                        <cbc:LineExtensionAmount currencyID="COP">50000.00</cbc:LineExtensionAmount>
                        <cac:Item><cbc:Description>Mercado benchmark HNSW</cbc:Description></cac:Item>
                    </cac:InvoiceLine>
                </Invoice>
                """;

        // 1. Medir ingesta síncrona end-to-end (< 30s RNF-07)
        long startIngestMs = System.currentTimeMillis();
        MvcResult uploadRes = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "bench.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docId = UUID.fromString(objectMapper.readTree(uploadRes.getResponse().getContentAsString()).at("/document/id").asText());

        mockMvc.perform(post("/api/documents/" + docId + "/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "ALMACENES ÉXITO S.A.",
                                  "merchantNit": "890900608",
                                  "invoiceNumber": "BENCH-100",
                                  "transactionDate": "2026-10-22",
                                  "totalAmount": 59500.00,
                                  "category": "Alimentación",
                                  "budgetBlock": "NEEDS"
                                }
                                """))
                .andExpect(status().isOk());
        long ingestMs = System.currentTimeMillis() - startIngestMs;
        assertThat(ingestMs).isLessThan(30_000L);

        // 2. Poblar 180 chunks vectorizados y comprobar plan EXPLAIN del índice HNSW `idx_document_chunks_hnsw`
        String sampleVector = primaryEmbeddingProvider.toPgVectorLiteral(
                primaryEmbeddingProvider.generateEmbedding("mercado alimentacion exito")
        );
        String explainPlan = RlsDataSourceConfig.runAsUser(userId, () -> transactionTemplate.execute(status -> {
            for (int i = 0; i < 180; i++) {
                jdbcTemplate.update(
                        """
                        INSERT INTO document_chunks (
                            user_id, document_id, chunk_order, chunk_type, page_number,
                            source_reference, content_text, embedding_model, embedding_version, embedding
                        ) VALUES (?, ?, ?, 'INVOICE_SUMMARY', 1, ?, ?, 'local', 'v1', CAST(? AS vector))
                        """,
                        userId, docId, i + 10,
                        "BENCH-REF-" + i,
                        "Fragmento prueba HNSW " + i + " compra supermercado alimentos",
                        sampleVector
                );
            }
            List<String> planLines = jdbcTemplate.queryForList(
                    "EXPLAIN (ANALYZE) SELECT id FROM document_chunks WHERE embedding IS NOT NULL ORDER BY embedding <=> CAST(? AS vector) LIMIT 5",
                    String.class,
                    sampleVector
            );
            String amName = jdbcTemplate.queryForObject(
                    """
                    SELECT am.amname
                    FROM pg_class idx
                    JOIN pg_am am ON am.oid = idx.relam
                    JOIN pg_index pgidx ON pgidx.indexrelid = idx.oid
                    WHERE idx.relname = 'idx_document_chunks_hnsw'
                      AND pgidx.indisvalid = TRUE
                    """,
                    String.class
            );
            return "AM=" + amName + "\n" + String.join("\n", planLines);
        }));
        assertThat(explainPlan).startsWith("AM=hnsw");
        assertThat(explainPlan).contains("Execution Time:");

        // 3. Medir consulta RAG completa bajo contexto RLS (< 10s RNF-07)
        long startChatMs = System.currentTimeMillis();
        var chatResp = RlsDataSourceConfig.runAsUser(userId, () ->
                chatService.askQuestion(userId, "¿Cuánto gasté en Éxito en octubre 2026?"));
        long chatMs = System.currentTimeMillis() - startChatMs;
        assertThat(chatResp.hasEvidence()).isTrue();
        assertThat(chatResp.sqlComputedTotal()).isEqualByComparingTo("59500.00");
        assertThat(chatMs).isLessThan(10_000L);
    }
}
