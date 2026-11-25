package co.cuentaclara.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MonthlyReportIntegrationTest {

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
    @DisplayName("RF-13, RF-16, RF-17: Evalúa en SQL el cierre del primer mes simulado, detecta exceso en necesidades sin sugerir incumplir obligaciones básicas, cita los gastos de respaldo y permite editar el plan sugerido")
    void shouldGenerateMonthlyReportWithCitationsAndAllowEditingSuggestedPlan() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Laura Méndez",
                                  "email": "laura@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();

        // 1. Configurar perfil con ingreso de $4.000.000 y regla 50/30/20 (tope Necesidades = $2.000.000)
        mockMvc.perform(post("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "netIncome": 4000000,
                                  "paymentFrequency": "BIWEEKLY",
                                  "paymentDays": "15,30",
                                  "needsPct": 50,
                                  "wantsPct": 30,
                                  "savingsPct": 20
                                }
                                """))
                .andExpect(status().isOk());

        // 2. Simular el cierre del primer mes (2026-10):
        // Necesidades simuladas = 1.450.000 + 620.000 + 260.000 = 2.330.000 (> 2.000.000)
        // Deseos simulados = 195.000 + 140.000 = 335.000
        MvcResult simRes = mockMvc.perform(post("/api/reports/simulate-first-month")
                        .param("month", "2026-10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.needsSpent").value(2330000.00))
                .andExpect(jsonPath("$.report.wantsSpent").value(335000.00))
                .andExpect(jsonPath("$.report.essentialExceeded").value(true))
                .andExpect(jsonPath("$.suggestedNextPlan.budgetStage").value("OBSERVED_PLAN"))
                .andExpect(jsonPath("$.suggestedNextPlan.status").value("SUGGESTED"))
                .andReturn();

        JsonNode reportJson = objectMapper.readTree(simRes.getResponse().getContentAsString());
        String recsText = reportJson.get("recommendations").toString();

        // Verificar que no recomienda incumplir obligaciones básicas y que incluye citas a los gastos reales
        assertThat(recsText).contains("No debes incumplir obligaciones básicas");
        assertThat(recsText).contains("MANUAL:");

        String suggestedBudgetId = reportJson.at("/suggestedNextPlan/id").asText();

        // 3. El usuario decide EDITAR el plan sugerido para el siguiente mes a 60/20/20 (RF-16, RF-17)
        mockMvc.perform(patch("/api/reports/budgets/" + suggestedBudgetId + "/decision")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "EDITED",
                                  "referenceIncome": 4000000,
                                  "needsPct": 60,
                                  "wantsPct": 20,
                                  "savingsPct": 20
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EDITED"))
                .andExpect(jsonPath("$.needsPct").value(60))
                .andExpect(jsonPath("$.needsLimit").value(2400000.00));
    }
}
