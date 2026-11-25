package co.cuentaclara.profile;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FinancialProfileIntegrationTest {

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
    @DisplayName("RF-11, RF-15, RF-16, RF-17: Valida que los porcentajes sumen 100%, calcula presupuesto inicial estimado y alerta si obligaciones superan el límite sin sugerir incumplirlas")
    void shouldValidatePercentageSumAndGenerateInitialEstimatedBudget() throws Exception {
        // Registrar usuario de prueba
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Diego Vargas",
                                  "email": "diego@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(reg.getResponse().getContentAsString()).get("token").asText();

        // 1. Rechazar porcentajes que no sumen 100% (ej. 50 + 30 + 10 = 90)
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
                                  "savingsPct": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("100 %")));

        // 2. Guardar perfil con regla personalizada 60/25/15 y obligaciones esenciales que superan el 60%
        MvcResult profileResult = mockMvc.perform(post("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "netIncome": 3000000,
                                  "paymentFrequency": "BIWEEKLY",
                                  "paymentDays": "15,30",
                                  "needsPct": 60,
                                  "wantsPct": 25,
                                  "savingsPct": 15,
                                  "financialGoals": "Pagar tarjeta y ahorrar",
                                  "totalDebts": 1500000,
                                  "obligations": [
                                    {
                                      "name": "Arriendo apartamento",
                                      "category": "Vivienda",
                                      "budgetBlock": "NEEDS",
                                      "estimatedAmount": 1400000,
                                      "dueDay": 5,
                                      "essential": true
                                    },
                                    {
                                      "name": "Mercado y Servicios",
                                      "category": "Alimentación",
                                      "budgetBlock": "NEEDS",
                                      "estimatedAmount": 600000,
                                      "dueDay": 20,
                                      "essential": true
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialBudget.budgetStage").value("INITIAL_ESTIMATE"))
                .andExpect(jsonPath("$.initialBudget.needsLimit").value(1800000.00))
                .andExpect(jsonPath("$.initialBudget.wantsLimit").value(750000.00))
                .andExpect(jsonPath("$.initialBudget.savingsTarget").value(450000.00))
                .andReturn();

        JsonNode json = objectMapper.readTree(profileResult.getResponse().getContentAsString());
        String recommendationsText = json.get("preliminaryRecommendations").toString();

        // Verificar que la etapa es INITIAL_ESTIMATE y las notas explican el ajuste sin recomendar incumplir pagos fijos
        assertThat(recommendationsText).contains("se calcula con tus datos actuales");
        assertThat(recommendationsText).contains("superan en $200.000 COP");
        assertThat(recommendationsText).contains("reduciendo el porcentaje de Deseos");

        // 3. Consultar perfil guardado por GET
        mockMvc.perform(get("/api/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.needsPct").value(60))
                .andExpect(jsonPath("$.obligations.length()").value(2));
    }
}
