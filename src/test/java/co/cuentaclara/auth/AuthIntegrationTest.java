package co.cuentaclara.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

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

    @Value("${cuentaclara.security.google-client-id:}")
    private String configuredGoogleClientId;

    @Value("${cuentaclara.security.firebase.project-id:}")
    private String configuredFirebaseProjectId;

    @Value("${cuentaclara.security.firebase.api-key:}")
    private String configuredFirebaseApiKey;

    @Test
    @DisplayName("RF-01, RNF-02, RNF-04, RF-14: Registro exige consentimiento Ley 1581, autentica con JWT y permite eliminar cuenta y datos")
    void shouldRegisterLoginAndDeleteAccountCompletely() throws Exception {
        // 1. Intentar registrarse sin aceptar consentimiento Ley 1581 -> 400 Bad Request
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Camila Soto",
                                  "email": "camila@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": false
                                }
                                """))
                .andExpect(status().isBadRequest());

        // 2. Registro exitoso con consentimiento explícito -> 201 Created y JWT emitido
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Camila Soto",
                                  "email": "camila@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("camila@cuentaclara.co"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        JsonNode registerJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        String jwtToken = registerJson.get("token").asText();
        String userId = registerJson.get("userId").asText();

        // 3. Consultar /api/auth/me con el JWT
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("camila@cuentaclara.co"))
                .andExpect(jsonPath("$.userId").value(userId));

        // 4. Iniciar sesión con contraseña incorrecta -> 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "camila@cuentaclara.co",
                                  "password": "ClaveIncorrecta999"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        // 5. Iniciar sesión con credenciales válidas -> 200 OK
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "camila@cuentaclara.co",
                                  "password": "PasswordSegura123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 6. Eliminar cuenta (RF-14) -> No queda ninguna fila del usuario
        mockMvc.perform(delete("/api/auth/account")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk());

        Integer remainingUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = 'camila@cuentaclara.co'",
                Integer.class
        );
        assertThat(remainingUsers).isZero();
    }

    @Test
    @DisplayName("UI Login A1 + Móvil C y Google Client ID configurado desde entorno")
    void shouldRenderRedesignedLoginPageWithConfiguredGoogleClient() throws Exception {
        MvcResult loginRes = mockMvc.perform(get("/auth/login"))
                .andExpect(status().isOk())
                .andReturn();
        String loginHtml = loginRes.getResponse().getContentAsString();
        assertThat(loginHtml).contains("cc-aurora-orb-1", "authTabTrack", "loginModeToggleBtn", "Calendario diario");
        if (!configuredFirebaseProjectId.isBlank() && !configuredFirebaseApiKey.isBlank()) {
            assertThat(loginHtml).contains("id=\"firebaseGoogleBtn\"", "action=\"/auth/firebase\"", configuredFirebaseProjectId);
        } else if (!configuredGoogleClientId.isBlank()) {
            assertThat(loginHtml).contains("data-client-id=\"" + configuredGoogleClientId + "\"", "https://accounts.google.com/gsi/client");
        } else {
            assertThat(loginHtml).contains("GOOGLE_CLIENT_ID");
        }

        MvcResult regRes = mockMvc.perform(get("/auth/register"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(regRes.getResponse().getContentAsString()).contains("const initialMode = \"register\";");
    }

    @Test
    @DisplayName("Swagger / OpenAPI expone endpoints REST /api/** con esquema JWT bearerAuth y Swagger UI accesible")
    void shouldExposeOpenApiSpecAndSwaggerUi() throws Exception {
        MvcResult apiDocsResult = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("CuentaClara API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andReturn();

        JsonNode apiDocs = objectMapper.readTree(apiDocsResult.getResponse().getContentAsString());
        JsonNode paths = apiDocs.path("paths");
        assertThat(paths.has("/api/auth/login")).isTrue();
        assertThat(paths.has("/api/documents")).isTrue();
        assertThat(paths.has("/api/documents/{documentId}")).isTrue();
        assertThat(paths.has("/api/chat")).isTrue();
        assertThat(paths.has("/api/transactions")).isTrue();
        assertThat(paths.has("/api/transactions/manual")).isTrue();
        assertThat(paths.has("/api/profile")).isTrue();
        assertThat(paths.has("/api/reports")).isTrue();
        assertThat(paths.has("/api/reports/generate")).isTrue();
        assertThat(paths.has("/recommendations")).isFalse();

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());

        MvcResult swaggerUiResult = mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(swaggerUiResult.getResponse().getContentAsString()).contains("swagger-ui");
    }
}
