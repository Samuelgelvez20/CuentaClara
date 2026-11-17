package co.cuentaclara.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RagIndexingAndCategorizationIntegrationTest {

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

    @Test
    @DisplayName("RF-07, RF-08, RF-09: Verifica que el archivo temporal se borra SOLO tras indexar en pgvector y que las correcciones de categoría aprenden por usuario sin filtrarse a otros usuarios")
    void shouldVerifyVectorIndexingBeforeDeletingTempFileAndReuseCategoryCorrectionsPerUserOnly() throws Exception {
        // Registrar Usuario 1 (Gabriela) y Usuario 2 (Héctor)
        String tokenGabriela = registerUser("Gabriela Díaz", "gabriela@cuentaclara.co");
        String tokenHector = registerUser("Héctor Ruiz", "hector@cuentaclara.co");

        // 1. Gabriela sube una factura XML
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                    <cbc:ID>FE-9901</cbc:ID>
                    <cbc:UUID>cufe-9901-gabriela</cbc:UUID>
                    <cbc:IssueDate>2026-10-12</cbc:IssueDate>
                    <cac:AccountingSupplierParty>
                        <cac:Party>
                            <cac:PartyTaxScheme>
                                <cbc:RegistrationName>LA PANADERÍA DEL BARRIO S.A.S.</cbc:RegistrationName>
                                <cbc:CompanyID>901999111</cbc:CompanyID>
                            </cac:PartyTaxScheme>
                        </cac:Party>
                    </cac:AccountingSupplierParty>
                    <cac:TaxTotal>
                        <cbc:TaxAmount currencyID="COP">0.00</cbc:TaxAmount>
                    </cac:TaxTotal>
                    <cac:LegalMonetaryTotal>
                        <cbc:LineExtensionAmount currencyID="COP">25000.00</cbc:LineExtensionAmount>
                        <cbc:PayableAmount currencyID="COP">25000.00</cbc:PayableAmount>
                    </cac:LegalMonetaryTotal>
                    <cac:InvoiceLine>
                        <cbc:ID>1</cbc:ID>
                        <cbc:InvoicedQuantity>1</cbc:InvoicedQuantity>
                        <cbc:LineExtensionAmount currencyID="COP">25000.00</cbc:LineExtensionAmount>
                        <cac:Item>
                            <cbc:Description>Pan artesanal masa madre</cbc:Description>
                        </cac:Item>
                    </cac:InvoiceLine>
                </Invoice>
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "panaderia_9901.xml",
                "application/xml",
                xml.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult uploadRes = mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenGabriela))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode uploadJson = objectMapper.readTree(uploadRes.getResponse().getContentAsString());
        String documentId = uploadJson.at("/document/id").asText();
        String tempPath = uploadJson.at("/document/tempFilePath").asText();

        // Antes de confirmar e indexar, el archivo temporal DEBE existir en disco (RF-08)
        assertThat(Files.exists(Path.of(tempPath))).isTrue();

        // 2. Gabriela confirma la factura y asigna la categoría personalizada "Antojos de Panadería"
        MvcResult confirmRes = mockMvc.perform(post("/api/documents/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + tokenGabriela)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "Antojos de Panadería",
                                  "budgetBlock": "WANTS"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("Antojos de Panadería"))
                .andReturn();

        // 3. Verificar que el documento pasó a estado INDEXED y su archivo temporal fue eliminado (RF-08, RNF-04)
        mockMvc.perform(get("/api/documents/" + documentId)
                        .header("Authorization", "Bearer " + tokenGabriela))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("INDEXED"))
                .andExpect(jsonPath("$.document.tempFileDeleted").value(true));

        assertThat(Files.exists(Path.of(tempPath))).isFalse();

        // 4. Cuando Gabriela registra otra compra en "LA PANADERÍA DEL BARRIO S.A.S." sin indicar categoría,
        // el sistema reutiliza automáticamente su corrección previa ("Antojos de Panadería") (RF-09)
        mockMvc.perform(post("/api/transactions/manual")
                        .header("Authorization", "Bearer " + tokenGabriela)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "LA PANADERÍA DEL BARRIO S.A.S.",
                                  "transactionDate": "2026-10-19",
                                  "totalAmount": 18000.00,
                                  "description": "Compra de croissants"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("Antojos de Panadería"))
                .andExpect(jsonPath("$.budgetBlock").value("WANTS"));

        // 5. En cambio, cuando Héctor (otro usuario) compra en "LA PANADERÍA DEL BARRIO S.A.S." sin indicar categoría,
        // NUNCA recibe la categoría personalizada de Gabriela (aislamiento estricto RF-09 / RNF-01)
        mockMvc.perform(post("/api/transactions/manual")
                        .header("Authorization", "Bearer " + tokenHector)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "LA PANADERÍA DEL BARRIO S.A.S.",
                                  "transactionDate": "2026-10-19",
                                  "totalAmount": 18000.00,
                                  "description": "Compra de croissants"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("Otros"));
    }

    private String registerUser(String fullName, String email) throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                  "fullName": "%s",
                                  "email": "%s",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """, fullName, email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();
    }
}
