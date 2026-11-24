package co.cuentaclara.transaction;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ConfirmationAndManualExpenseIntegrationTest {

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
    @DisplayName("RF-02, RF-05, RF-10: Nada se guarda en el historial sin confirmación del usuario; admite gastos manuales sin adjunto y calcula totales exactos por categoría y mes")
    void shouldRequireConfirmationBeforeSavingAndSupportManualExpensesAndFilteredHistory() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Felipe Castro",
                                  "email": "felipe@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();

        // 1. Subir factura XML (queda en AWAITING_CONFIRMATION)
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                    <cbc:ID>FE-7701</cbc:ID>
                    <cbc:UUID>cufe-7701-octubre</cbc:UUID>
                    <cbc:IssueDate>2026-10-10</cbc:IssueDate>
                    <cac:AccountingSupplierParty>
                        <cac:Party>
                            <cac:PartyTaxScheme>
                                <cbc:RegistrationName>CARULLA S.A.</cbc:RegistrationName>
                                <cbc:CompanyID>890900609</cbc:CompanyID>
                            </cac:PartyTaxScheme>
                        </cac:Party>
                    </cac:AccountingSupplierParty>
                    <cac:TaxTotal>
                        <cbc:TaxAmount currencyID="COP">19000.00</cbc:TaxAmount>
                    </cac:TaxTotal>
                    <cac:LegalMonetaryTotal>
                        <cbc:LineExtensionAmount currencyID="COP">100000.00</cbc:LineExtensionAmount>
                        <cbc:PayableAmount currencyID="COP">119000.00</cbc:PayableAmount>
                    </cac:LegalMonetaryTotal>
                </Invoice>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "file",
                "carulla_7701.xml",
                "application/xml",
                xmlContent.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult uploadRes = mockMvc.perform(multipart("/api/documents")
                        .file(xmlFile)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.status").value("AWAITING_CONFIRMATION"))
                .andReturn();

        String documentId = objectMapper.readTree(uploadRes.getResponse().getContentAsString())
                .at("/document/id").asText();

        // 2. Verificar que ANTES de confirmar, el historial de transacciones está vacío (RF-05)
        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(0))
                .andExpect(jsonPath("$.grandTotal").value(0));

        // 3. Confirmar la factura corrigiendo el monto total a 120000.00 (RF-05)
        mockMvc.perform(post("/api/documents/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "CARULLA S.A.",
                                  "totalAmount": 120000.00,
                                  "category": "Alimentación",
                                  "budgetBlock": "NEEDS"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(120000.00))
                .andExpect(jsonPath("$.citationReference").value(org.hamcrest.Matchers.containsString("XML:FE-7701")));

        // 4. Registrar un gasto manual SIN adjunto de respaldo (RF-02)
        mockMvc.perform(post("/api/transactions/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "Rappi Hamburguesa",
                                  "transactionDate": "2026-10-15",
                                  "totalAmount": 45000.00,
                                  "category": "Domicilios y Restaurantes",
                                  "budgetBlock": "WANTS",
                                  "description": "Cena viernes por Rappi"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.citationReference").value(org.hamcrest.Matchers.startsWith("MANUAL:")));

        // 5. Consultar historial de octubre 2026 y verificar que la suma SQL coincide (120000 + 45000 = 165000) (RF-10)
        mockMvc.perform(get("/api/transactions")
                        .param("month", "2026-10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(2))
                .andExpect(jsonPath("$.grandTotal").value(165000.00))
                .andExpect(jsonPath("$.totalsByCategory['Alimentación']").value(120000.00))
                .andExpect(jsonPath("$.totalsByCategory['Domicilios y Restaurantes']").value(45000.00));
    }

    @Test
    @DisplayName("Permite cancelar un recibo pendiente sin guardar gasto (habilitando re-subida) y reporta distribución personalizada en Consultas")
    void shouldCancelPendingDocumentWithoutSavingAndReportCustomBudgetDistributionInChat() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Camila Morales",
                                  "email": "camila.cancel@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();

        // Guardar perfil con distribución personalizada 50/41/9
        mockMvc.perform(post("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "netIncome": 3500000.00,
                                  "paymentFrequency": "MONTHLY",
                                  "paymentDays": "30",
                                  "needsPct": 50,
                                  "wantsPct": 41,
                                  "savingsPct": 9,
                                  "financialGoals": "Fondo de emergencia",
                                  "totalDebts": 0,
                                  "obligations": []
                                }
                                """))
                .andExpect(status().isOk());

        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                    <cbc:ID>FE-8801</cbc:ID>
                    <cbc:UUID>cufe-8801-cancel</cbc:UUID>
                    <cbc:IssueDate>2026-10-18</cbc:IssueDate>
                    <cac:AccountingSupplierParty>
                        <cac:Party>
                            <cac:PartyTaxScheme>
                                <cbc:RegistrationName>EXITO S.A.</cbc:RegistrationName>
                                <cbc:CompanyID>890900608</cbc:CompanyID>
                            </cac:PartyTaxScheme>
                        </cac:Party>
                    </cac:AccountingSupplierParty>
                    <cac:LegalMonetaryTotal>
                        <cbc:LineExtensionAmount currencyID="COP">50000.00</cbc:LineExtensionAmount>
                        <cbc:PayableAmount currencyID="COP">50000.00</cbc:PayableAmount>
                    </cac:LegalMonetaryTotal>
                </Invoice>
                """;

        MockMultipartFile xmlFile = new MockMultipartFile(
                "file",
                "exito_8801.xml",
                "application/xml",
                xmlContent.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult uploadRes = mockMvc.perform(multipart("/api/documents")
                        .file(xmlFile)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.status").value("AWAITING_CONFIRMATION"))
                .andReturn();

        String documentId = objectMapper.readTree(uploadRes.getResponse().getContentAsString())
                .at("/document/id").asText();

        // Vista /upload muestra la opción de cancelar
        mockMvc.perform(get("/upload").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cancelar y no guardar")))
                .andExpect(content().string(containsString("/dashboard/documents/" + documentId + "/cancel")));

        // Cancelar el comprobante sin guardar gasto
        mockMvc.perform(post("/dashboard/documents/" + documentId + "/cancel")
                        .param("returnTo", "/upload")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/upload"));

        // Confirmar que no quedó ningún gasto ni bloqueo por duplicado al volver a subirlo
        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(0));

        mockMvc.perform(multipart("/api/documents")
                        .file(xmlFile)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        // Consultas debe reportar exactamente 50/41/9 en lugar de 50/30/20
        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "question": "cual es la distribucion de mi presupuesto?" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", containsString("Según tu distribución 50/41/9")))
                .andExpect(jsonPath("$.answer", not(containsString("50/30/20"))));
    }

    @Test
    @DisplayName("Permite navegar y filtrar fechas antiguas y futuras en Calendario, Historial (rangos y Todo) y Recomendaciones")
    void shouldSupportArbitraryDatesAndRangesAcrossDashboardHistoryAndRecommendations() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Mateo Ríos",
                                  "email": "mateo.fechas@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "netIncome": 4000000.00,
                                  "paymentFrequency": "MONTHLY",
                                  "paymentDays": "30",
                                  "needsPct": 50,
                                  "wantsPct": 30,
                                  "savingsPct": 20,
                                  "financialGoals": "Ahorro",
                                  "totalDebts": 0,
                                  "obligations": []
                                }
                                """))
                .andExpect(status().isOk());

        // Registrar un gasto antiguo en 2018-04-12 y uno futuro en 2029-05-05
        mockMvc.perform(post("/api/transactions/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "Librería 2018",
                                  "transactionDate": "2018-04-12",
                                  "totalAmount": 85000.00,
                                  "category": "Educación",
                                  "budgetBlock": "NEEDS",
                                  "description": "Compra histórica"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/transactions/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "Viaje Futuro 2029",
                                  "transactionDate": "2029-05-05",
                                  "totalAmount": 250000.00,
                                  "category": "Entretenimiento",
                                  "budgetBlock": "WANTS",
                                  "description": "Reserva futura"
                                }
                                """))
                .andExpect(status().isCreated());

        // 1. Calendario permite fecha antigua (2018-04) y futura (2029-05) renderizando el Date Picker visual
        mockMvc.perform(get("/dashboard")
                        .param("month", "2018-04")
                        .param("day", "12")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cc-cal-datepicker")))
                .andExpect(content().string(containsString("Abril 2018")))
                .andExpect(content().string(containsString("Librería 2018")));

        // 2. Historial soporta rango exacto y 'month=ALL' (con tarjetas por categoría activas)
        mockMvc.perform(get("/history")
                        .param("startDate", "2018-04-01")
                        .param("endDate", "2018-04-20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cc-history-datepicker")))
                .andExpect(content().string(containsString("1 movimiento(s)")))
                .andExpect(content().string(containsString("Compra histórica")))
                .andExpect(content().string(not(containsString("Reserva futura"))));

        mockMvc.perform(get("/history")
                        .param("month", "ALL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Todo el historial")))
                .andExpect(content().string(containsString("2 movimiento(s)")))
                .andExpect(content().string(containsString("Compra histórica")))
                .andExpect(content().string(containsString("Reserva futura")));

        // 3. Recomendaciones soporta meses antiguos más allá de 18 meses y meses futuros
        mockMvc.perform(get("/recommendations")
                        .param("month", "2018-04")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cc-rec-datepicker")))
                .andExpect(content().string(containsString("Abril 2018")));

        mockMvc.perform(get("/recommendations")
                        .param("month", "2029-05")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mayo 2029")));
    }
}
