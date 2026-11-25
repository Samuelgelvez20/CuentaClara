package co.cuentaclara.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class XmlIngestionAndValidationIntegrationTest {

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

    private static final String SAMPLE_DIAN_UBL_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                     xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                     xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                <cbc:UBLVersionID>UBL 2.1</cbc:UBLVersionID>
                <cbc:ID>SETT-984512</cbc:ID>
                <cbc:UUID schemeName="CUFE-SHA384">a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0</cbc:UUID>
                <cbc:IssueDate>2026-10-18</cbc:IssueDate>
                <cac:AccountingSupplierParty>
                    <cac:Party>
                        <cac:PartyTaxScheme>
                            <cbc:RegistrationName>ALMACENES ÉXITO S.A.</cbc:RegistrationName>
                            <cbc:CompanyID>890900608</cbc:CompanyID>
                        </cac:PartyTaxScheme>
                    </cac:Party>
                </cac:AccountingSupplierParty>
                <cac:TaxTotal>
                    <cbc:TaxAmount currencyID="COP">28500.00</cbc:TaxAmount>
                </cac:TaxTotal>
                <cac:LegalMonetaryTotal>
                    <cbc:LineExtensionAmount currencyID="COP">150000.00</cbc:LineExtensionAmount>
                    <cbc:TaxExclusiveAmount currencyID="COP">150000.00</cbc:TaxExclusiveAmount>
                    <cbc:TaxInclusiveAmount currencyID="COP">178500.00</cbc:TaxInclusiveAmount>
                    <cbc:PayableAmount currencyID="COP">178500.00</cbc:PayableAmount>
                </cac:LegalMonetaryTotal>
                <cac:InvoiceLine>
                    <cbc:ID>1</cbc:ID>
                    <cbc:InvoicedQuantity unitCode="EA">2.00</cbc:InvoicedQuantity>
                    <cbc:LineExtensionAmount currencyID="COP">90000.00</cbc:LineExtensionAmount>
                    <cac:Item>
                        <cbc:Description>Café Molido Gourmet 500g</cbc:Description>
                    </cac:Item>
                </cac:InvoiceLine>
                <cac:InvoiceLine>
                    <cbc:ID>2</cbc:ID>
                    <cbc:InvoicedQuantity unitCode="EA">1.00</cbc:InvoicedQuantity>
                    <cbc:LineExtensionAmount currencyID="COP">60000.00</cbc:LineExtensionAmount>
                    <cac:Item>
                        <cbc:Description>Aceite de Oliva Extra Virgen</cbc:Description>
                    </cac:Item>
                </cac:InvoiceLine>
            </Invoice>
            """;

    @Test
    @DisplayName("RF-02, RF-03, RF-04, RF-06, RNF-03: Rechaza tipos inválidos o malware, parsea XML UBL 2.1 dejándolo 'Por confirmar' (AWAITING_CONFIRMATION) y bloquea duplicados por hash o CUFE")
    void shouldValidateIngestUblXmlAndRejectDuplicates() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Elena Rojas",
                                  "email": "elena@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();

        // 1. Subir un archivo ejecutable disfrazado de .xml -> Rechazado por validación de magic bytes (RNF-03)
        MockMultipartFile fakeXml = new MockMultipartFile(
                "file",
                "factura_falsa.xml",
                "application/xml",
                "MZ-ejecutable-binario-invalido".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/documents")
                        .file(fakeXml)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Formato de archivo no permitido")));

        // 2. Subir una factura XML UBL 2.1 DIAN válida -> 201 Created en estado AWAITING_CONFIRMATION ("Por confirmar")
        MockMultipartFile validXml = new MockMultipartFile(
                "file",
                "factura_exito_octubre.xml",
                "application/xml",
                SAMPLE_DIAN_UBL_XML.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/documents")
                        .file(validXml)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.document.cufe").value("a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"))
                .andExpect(jsonPath("$.extraction.merchantName").value("ALMACENES ÉXITO S.A."))
                .andExpect(jsonPath("$.extraction.merchantNit").value("890900608"))
                .andExpect(jsonPath("$.extraction.invoiceNumber").value("SETT-984512"))
                .andExpect(jsonPath("$.extraction.issueDate").value("2026-10-18"))
                .andExpect(jsonPath("$.extraction.subtotalAmount").value(150000.00))
                .andExpect(jsonPath("$.extraction.taxAmount").value(28500.00))
                .andExpect(jsonPath("$.extraction.totalAmount").value(178500.00))
                .andExpect(jsonPath("$.extraction.suggestedCategory").value("Alimentación"))
                .andExpect(jsonPath("$.extraction.confirmedByUser").value(false))
                .andReturn();

        JsonNode json = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        assertThat(json.at("/document/tempFileDeleted").asBoolean()).isFalse();

        // 3. Intentar volver a subir el mismo archivo -> 409 Conflict por hash SHA-256 (RF-06)
        mockMvc.perform(multipart("/api/documents")
                        .file(validXml)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("duplicado")));

        // 4. Modificar un espacio en blanco del XML pero conservar el mismo CUFE -> 409 Conflict por CUFE (RF-06)
        MockMultipartFile sameCufeDifferentBytes = new MockMultipartFile(
                "file",
                "factura_copia.xml",
                "application/xml",
                (SAMPLE_DIAN_UBL_XML + "\n<!-- comentario -->").getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/documents")
                        .file(sameCufeDifferentBytes)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("CUFE")));
    }
}
