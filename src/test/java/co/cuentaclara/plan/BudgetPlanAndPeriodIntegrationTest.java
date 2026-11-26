package co.cuentaclara.plan;

import co.cuentaclara.domain.model.PlanDecisionRequest;
import co.cuentaclara.domain.port.in.MonthlyReportUseCase;
import co.cuentaclara.infrastructure.security.jwt.JwtTokenService;
import co.cuentaclara.infrastructure.security.rls.RlsDataSourceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BudgetPlanAndPeriodIntegrationTest {

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
    private MonthlyReportUseCase reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Bloque 4 (RF-13, RF-16): Persiste PlanCalculator en budget_plans/lines/weeks/actions bajo RLS y soporta periodos quincenales y semanales")
    void shouldPersistV2PlanTablesAndSupportBiweeklyAndWeeklyClosures() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Planeación Quincenal",
                                  "email": "planes.v2@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode regJson = objectMapper.readTree(reg.getResponse().getContentAsString());
        UUID userId = UUID.fromString(regJson.get("userId").asText());
        String token = regJson.get("token").asText();

        // Configurar perfil base $ 4.000.000 COP quincenal con arriendo fijo
        mockMvc.perform(post("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "netIncome": 4000000.00,
                                  "paymentFrequency": "BIWEEKLY",
                                  "paymentDays": "15,30",
                                  "needsPct": 50,
                                  "wantsPct": 30,
                                  "savingsPct": 20,
                                  "financialGoals": "Fondo de emergencia",
                                  "initialBalance": 500000.00,
                                  "obligations": [
                                    {
                                      "name": "Arriendo Apartamento",
                                      "category": "Vivienda",
                                      "budgetBlock": "NEEDS",
                                      "estimatedAmount": 1400000.00,
                                      "dueDay": 5
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        // Registrar un gasto en Q1 (día 10) y otro en Q2 (día 20) de 2026-10
        mockMvc.perform(post("/api/transactions/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "Mercado Q1",
                                  "transactionDate": "2026-10-10",
                                  "totalAmount": 350000.00,
                                  "category": "Alimentación",
                                  "budgetBlock": "NEEDS"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/transactions/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantName": "Cine Q2",
                                  "transactionDate": "2026-10-20",
                                  "totalAmount": 120000.00,
                                  "category": "Entretenimiento",
                                  "budgetBlock": "WANTS"
                                }
                                """))
                .andExpect(status().isCreated());

        // 1. Ejecutar cierre mensual y verificar persistencia completa en tablas V2 (budget_plans, budget_lines, budget_plan_weeks, budget_plan_actions)
        var monthlyClosure = RlsDataSourceConfig.runAsUser(userId, () ->
                reportService.generatePeriodReportAndNextPlan(userId, "2026-10"));

        assertThat(monthlyClosure.calculatedPlan()).isNotNull();
        assertThat(monthlyClosure.calculatedPlan().lines()).isNotEmpty();
        assertThat(monthlyClosure.calculatedPlan().weeks()).hasSize(5); // Noviembre 2026 -> 5 tramos semanales

        Integer linesCount = RlsDataSourceConfig.runAsUser(userId, () -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM budget_lines WHERE plan_id = ?",
                Integer.class,
                monthlyClosure.calculatedPlan().planId()
        ));
        Integer weeksCount = RlsDataSourceConfig.runAsUser(userId, () -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM budget_plan_weeks WHERE plan_id = ?",
                Integer.class,
                monthlyClosure.calculatedPlan().planId()
        ));
        assertThat(linesCount).isGreaterThanOrEqualTo(3);
        assertThat(weeksCount).isEqualTo(5);

        // Aceptar plan y verificar estado sincronizado en budget_plans.status = 'ACCEPTED'
        RlsDataSourceConfig.runAsUser(userId, () ->
                reportService.updateSuggestedPlanDecision(
                        userId,
                        monthlyClosure.suggestedNextPlan().getId(),
                        new PlanDecisionRequest("ACCEPTED", null, null, null, null)
                ));
        String v2Status = RlsDataSourceConfig.runAsUser(userId, () -> jdbcTemplate.queryForObject(
                "SELECT status FROM budget_plans WHERE id = ?",
                String.class,
                monthlyClosure.calculatedPlan().planId()
        ));
        assertThat(v2Status).isEqualTo("ACCEPTED");

        // 2. Cierre quincenal 2026-10-Q1 -> prorratea ingreso a $ 2.000.000 y solo incluye Mercado Q1 ($ 350.000)
        var q1Closure = RlsDataSourceConfig.runAsUser(userId, () ->
                reportService.generatePeriodReportAndNextPlan(userId, "2026-10-Q1"));
        assertThat(q1Closure.report().getPeriodLabel()).isEqualTo("2026-10-Q1");
        assertThat(q1Closure.report().getDeclaredIncome()).isEqualByComparingTo("2000000.00");
        assertThat(q1Closure.report().getNeedsSpent()).isEqualByComparingTo("350000.00");
        assertThat(q1Closure.report().getWantsSpent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(q1Closure.suggestedNextPlan().getPeriodLabel()).isEqualTo("Plan Sugerido (2026-10-Q2)");

        // 3. Cierre semanal 2026-10-W3 -> cubre del 15 al 21 de octubre, capturando Cine Q2 ($ 120.000)
        var w3Closure = RlsDataSourceConfig.runAsUser(userId, () ->
                reportService.generatePeriodReportAndNextPlan(userId, "2026-10-W3"));
        assertThat(w3Closure.report().getPeriodLabel()).isEqualTo("2026-10-W3");
        assertThat(w3Closure.report().getWantsSpent()).isEqualByComparingTo("120000.00");
        assertThat(w3Closure.report().getNeedsSpent()).isEqualByComparingTo(BigDecimal.ZERO);

        // 4. Web /recommendations con selector ?month=2026-10&period=Q1 renderiza correctamente
        mockMvc.perform(get("/recommendations")
                        .cookie(new Cookie(JwtTokenService.AUTH_COOKIE_NAME, token))
                        .param("month", "2026-10")
                        .param("period", "Q1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cc-rec-period-select")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1ª Quincena")));
    }
}
