package co.cuentaclara.document;

import co.cuentaclara.infrastructure.adapter.in.async.DocumentAsyncWorkerAdapter;
import co.cuentaclara.infrastructure.adapter.out.document.RedisDocumentJobQueueAdapter;
import co.cuentaclara.infrastructure.security.jwt.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "cuentaclara.worker.consumer-enabled=false",
        "cuentaclara.worker.inline-async-fallback=false"
})
@AutoConfigureMockMvc
@Testcontainers
class AsyncRedisWorkerIntegrationTest {

    @TempDir
    static Path tempDir;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("cuentaclara")
            .withUsername("cuentaclara_owner")
            .withPassword("owner_secret_dev");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "cuentaclara_app");
        registry.add("spring.datasource.password", () -> "app_secret_dev");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("cuentaclara.storage.temp-dir", () -> tempDir.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisDocumentJobQueueAdapter jobQueueAdapter;

    @Autowired
    private DocumentAsyncWorkerAdapter ingestionWorker;

    private static final String SAMPLE_XML_API = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                     xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                     xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                <cbc:ID>ASYNC-1001</cbc:ID>
                <cbc:UUID schemeName="CUFE-SHA384">fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210</cbc:UUID>
                <cbc:IssueDate>2026-10-21</cbc:IssueDate>
                <cac:AccountingSupplierParty>
                    <cac:Party>
                        <cac:PartyTaxScheme>
                            <cbc:RegistrationName>DROGUERÍA CENTRAL S.A.S.</cbc:RegistrationName>
                            <cbc:CompanyID>900111222</cbc:CompanyID>
                        </cac:PartyTaxScheme>
                    </cac:Party>
                </cac:AccountingSupplierParty>
                <cac:TaxTotal><cbc:TaxAmount currencyID="COP">0.00</cbc:TaxAmount></cac:TaxTotal>
                <cac:LegalMonetaryTotal>
                    <cbc:LineExtensionAmount currencyID="COP">42000.00</cbc:LineExtensionAmount>
                    <cbc:PayableAmount currencyID="COP">42000.00</cbc:PayableAmount>
                </cac:LegalMonetaryTotal>
                <cac:InvoiceLine>
                    <cbc:ID>1</cbc:ID>
                    <cbc:InvoicedQuantity unitCode="EA">1.00</cbc:InvoicedQuantity>
                    <cbc:LineExtensionAmount currencyID="COP">42000.00</cbc:LineExtensionAmount>
                    <cac:Item><cbc:Description>Analgésicos y vitaminas</cbc:Description></cac:Item>
                </cac:InvoiceLine>
            </Invoice>
            """;

    private static final String SAMPLE_XML_WEB = SAMPLE_XML_API
            .replace("ASYNC-1001", "ASYNC-1002")
            .replace("fedcba9876543210", "0123456789abcdef")
            .replace("DROGUERÍA CENTRAL S.A.S.", "SUPERMERCADO NORTE S.A.");

    @Test
    @DisplayName("Bloque 2 (RF-03): Encola en Redis real (202 Accepted), consume desde Redis bajo RLS y soporta tracking en UI /upload")
    void shouldEnqueueAndConsumeRealRedisQueueAndRenderAsyncWebTracker() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Cola Asíncrona",
                                  "email": "redis.worker@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();

        // 1. Subida API async=true devuelve 202 Accepted en estado PENDING con trabajo en Redis
        MockMultipartFile apiXml = new MockMultipartFile(
                "file", "async_api.xml", "application/xml", SAMPLE_XML_API.getBytes(StandardCharsets.UTF_8)
        );
        MvcResult apiUpload = mockMvc.perform(multipart("/api/documents")
                        .file(apiXml)
                        .param("async", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.document.status").value("PENDING"))
                .andReturn();

        UUID apiDocId = UUID.fromString(objectMapper.readTree(apiUpload.getResponse().getContentAsString())
                .at("/document/id").asText());
        assertThat(jobQueueAdapter.getQueueDepth()).isEqualTo(1L);

        // 2. Worker consume el job real desde Redis
        assertThat(ingestionWorker.consumeNextQueuedJob()).isTrue();
        assertThat(jobQueueAdapter.getQueueDepth()).isEqualTo(0L);

        mockMvc.perform(get("/api/documents/" + apiDocId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.extraction.merchantName").value("DROGUERÍA CENTRAL S.A.S."))
                .andExpect(jsonPath("$.extraction.totalAmount").value(42000.00));

        // 3. Flujo Web /dashboard/upload (async por defecto) redirige a /upload?trackingDoc=... y muestra progreso vivo
        MockMultipartFile webXml = new MockMultipartFile(
                "file", "async_web.xml", "application/xml", SAMPLE_XML_WEB.getBytes(StandardCharsets.UTF_8)
        );
        Cookie authCookie = new Cookie(JwtTokenService.AUTH_COOKIE_NAME, token);
        MvcResult webPost = mockMvc.perform(multipart("/dashboard/upload")
                        .file(webXml)
                        .cookie(authCookie))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String redirectedUrl = webPost.getResponse().getRedirectedUrl();
        assertThat(redirectedUrl).startsWith("/upload?trackingDoc=");
        UUID webDocId = UUID.fromString(redirectedUrl.substring("/upload?trackingDoc=".length()));

        mockMvc.perform(get(redirectedUrl).cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cc-async-tracker")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cc-async-progress")));

        // Consumir el trabajo de Redis y confirmar que /upload muestra la tarjeta de confirmación extraída
        assertThat(ingestionWorker.consumeNextQueuedJob()).isTrue();

        mockMvc.perform(get("/api/documents/" + webDocId).cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.extraction.merchantName").value("SUPERMERCADO NORTE S.A."));
    }
}
