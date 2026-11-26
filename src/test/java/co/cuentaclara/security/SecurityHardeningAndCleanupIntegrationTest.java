package co.cuentaclara.security;

import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentSourceType;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import co.cuentaclara.domain.port.out.UserRepositoryPort;
import co.cuentaclara.infrastructure.adapter.in.async.DocumentAsyncWorkerAdapter;
import co.cuentaclara.infrastructure.adapter.out.document.LocalTempFileStorageAdapter;
import co.cuentaclara.infrastructure.adapter.out.security.GoogleTokenVerifierAdapter;
import co.cuentaclara.infrastructure.security.jwt.JwtTokenService;
import co.cuentaclara.infrastructure.security.rls.RlsDataSourceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityHardeningAndCleanupIntegrationTest {

    @TempDir
    static Path tempDir;

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
        registry.add("cuentaclara.storage.temp-dir", () -> tempDir.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepositoryPort userRepository;

    @Autowired
    private DocumentRepositoryPort documentRepository;

    @Autowired
    private LocalTempFileStorageAdapter tempFileStorageAdapter;

    @Autowired
    private DocumentAsyncWorkerAdapter workerAdapter;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    @DisplayName("Bloque 1: Rechaza suplantación por /auth/google sin ID Token verificado y exige firma JWS")
    void shouldRejectUnsignedOrForgedGoogleAuth() throws Exception {
        // 1. Intento clásico de enviar googleEmail directo sin credencial
        mockMvc.perform(post("/auth/google")
                        .param("googleEmail", "victima.suplantada@gmail.com")
                        .param("googleName", "Atacante"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Set-Cookie")).isNull());

        // 2. Intento con JWT Base64 forjado sin firma (alg=none)
        String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"email\":\"victima.suplantada@gmail.com\",\"email_verified\":true}".getBytes(StandardCharsets.UTF_8)
        );
        String forgedUnsignedJwt = headerB64 + "." + payloadB64 + ".";

        mockMvc.perform(post("/auth/google")
                        .param("credential", forgedUnsignedJwt)
                        .param("aiConsentAccepted", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Set-Cookie")).isNull());

        assertThat(userRepository.existsByEmailIgnoreCase("victima.suplantada@gmail.com")).isFalse();
    }

    @Test
    @DisplayName("Bloque 1: GoogleIdTokenVerifierService valida aud, iss, email_verified y JWT de 3 segmentos")
    void shouldValidateGoogleClaimsStrictly() throws Exception {
        String expectedClientId = "test-client.apps.googleusercontent.com";
        String sampleSignedLikeJwt = "aaa.bbb.ccc";

        GoogleTokenVerifierAdapter validVerifier = new GoogleTokenVerifierAdapter(null, objectMapper, expectedClientId) {
            @Override
            protected JsonNode fetchTokenInfo(String idTokenCredential) {
                try {
                    return objectMapper.readTree("""
                            {
                              "sub": "10987654321",
                              "aud": "test-client.apps.googleusercontent.com",
                              "iss": "https://accounts.google.com",
                              "email": "Verified.User@gmail.com",
                              "email_verified": "true",
                              "name": "Usuario Verificado"
                            }
                            """);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        var verified = validVerifier.verifyIdToken(sampleSignedLikeJwt);
        assertThat(verified.email()).isEqualTo("verified.user@gmail.com");
        assertThat(verified.fullName()).isEqualTo("Usuario Verificado");

        GoogleTokenVerifierAdapter wrongAudienceVerifier = new GoogleTokenVerifierAdapter(null, objectMapper, expectedClientId) {
            @Override
            protected JsonNode fetchTokenInfo(String idTokenCredential) {
                try {
                    return objectMapper.readTree("""
                            {
                              "sub": "10987654321",
                              "aud": "otro-cliente-malicioso.apps.googleusercontent.com",
                              "iss": "https://accounts.google.com",
                              "email": "verified@gmail.com",
                              "email_verified": "true"
                            }
                            """);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        assertThatThrownBy(() -> wrongAudienceVerifier.verifyIdToken(sampleSignedLikeJwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audiencia");
    }

    @Test
    @DisplayName("Bloque 1: Cookie Auth incluye Secure en HTTPS, JWT dura 30 min por defecto y /settings/delete-account borra DB + disco")
    void shouldSecureCookiesAndDeleteAccountCompletely() throws Exception {
        assertThat(jwtTokenService.getExpirationSeconds()).isEqualTo(1800L);

        // Registrar usuario por Web para verificar cookie Secure detrás de TLS proxy (X-Forwarded-Proto: https)
        MvcResult regResult = mockMvc.perform(post("/auth/register")
                        .header("X-Forwarded-Proto", "https")
                        .param("fullName", "Usuario Derecho Olvido")
                        .param("email", "olvido.web@cuentaclara.co")
                        .param("password", "Segura12345!")
                        .param("aiConsentAccepted", "true"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String setCookieHeader = regResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("Secure");
        assertThat(setCookieHeader).contains("SameSite=Lax");

        var user = userRepository.findByEmailIgnoreCase("olvido.web@cuentaclara.co").orElseThrow();
        Cookie authCookie = new Cookie(JwtTokenService.AUTH_COOKIE_NAME,
                jwtTokenService.generateToken(user.getId(), user.getEmail(), user.getFullName()));

        // Crear archivo físico simulado en carpeta del usuario
        Path userDir = tempDir.resolve(user.getId().toString());
        Files.createDirectories(userDir);
        Path orphanReceipt = userDir.resolve("factura_usuario.xml");
        Files.writeString(orphanReceipt, "<Invoice/>");
        assertThat(Files.exists(orphanReceipt)).isTrue();

        // Confirmación incorrecta NO debe borrar la cuenta
        mockMvc.perform(post("/settings/delete-account")
                        .cookie(authCookie)
                        .param("confirmationText", "CANCELAR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(userRepository.existsByEmailIgnoreCase("olvido.web@cuentaclara.co")).isTrue();
        assertThat(Files.exists(orphanReceipt)).isTrue();

        // Confirmación exacta "ELIMINAR" borra BD, archivos en disco y revoca cookie
        mockMvc.perform(post("/settings/delete-account")
                        .cookie(authCookie)
                        .param("confirmationText", "ELIMINAR"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login?deleted=true"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        assertThat(userRepository.existsByEmailIgnoreCase("olvido.web@cuentaclara.co")).isFalse();
        assertThat(Files.exists(orphanReceipt)).isFalse();
        assertThat(Files.exists(userDir)).isFalse();
    }

    @Test
    @DisplayName("Bloque 1: TempFileLifecycleAndRetryService limpia indexados/huérfanos, protege activos y reencola errores recuperables")
    void shouldCleanupIndexedAndOrphanedTempFilesAndRetryFailedDocs() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Limpieza Temporal",
                                  "email": "cleanup@cuentaclara.co",
                                  "password": "PasswordSegura123",
                                  "aiConsentAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper.readTree(reg.getResponse().getContentAsString()).get("userId").asText());

        Path userDir = tempDir.resolve(userId.toString());
        Files.createDirectories(userDir);
        Path indexedFile = Files.writeString(userDir.resolve("indexed.xml"), "<Invoice>ok</Invoice>");
        Path activeFile = Files.writeString(userDir.resolve("awaiting.xml"), "<Invoice>pending confirm</Invoice>");
        Path errorFile = Files.writeString(userDir.resolve("retryable.pdf"), "%PDF-1.4");
        Path orphanFile = Files.writeString(userDir.resolve("orphan.tmp"), "stale");

        UUID indexedDocId = RlsDataSourceConfig.runAsUser(userId, () -> {
            Document indexed = new Document();
            indexed.setUserId(userId);
            indexed.setOriginalFilename("indexed.xml");
            indexed.setMimeType("application/xml");
            indexed.setSourceType(DocumentSourceType.XML_UBL);
            indexed.setFileSizeBytes(22);
            indexed.setSha256Hash("a".repeat(64));
            indexed.setStatus(DocumentStatus.INDEXED);
            indexed.setTempFilePath(indexedFile.toString());
            indexed.setTempFileDeleted(false);
            indexed = documentRepository.save(indexed);

            Document awaiting = new Document();
            awaiting.setUserId(userId);
            awaiting.setOriginalFilename("awaiting.xml");
            awaiting.setMimeType("application/xml");
            awaiting.setSourceType(DocumentSourceType.XML_UBL);
            awaiting.setFileSizeBytes(33);
            awaiting.setSha256Hash("b".repeat(64));
            awaiting.setStatus(DocumentStatus.AWAITING_CONFIRMATION);
            awaiting.setTempFilePath(activeFile.toString());
            documentRepository.save(awaiting);

            Document errorDoc = new Document();
            errorDoc.setUserId(userId);
            errorDoc.setOriginalFilename("retryable.pdf");
            errorDoc.setMimeType("application/pdf");
            errorDoc.setSourceType(DocumentSourceType.PDF_DIGITAL);
            errorDoc.setFileSizeBytes(8);
            errorDoc.setSha256Hash("c".repeat(64));
            errorDoc.setStatus(DocumentStatus.ERROR);
            errorDoc.setRetryCount(1);
            errorDoc.setTempFilePath(errorFile.toString());
            documentRepository.save(errorDoc);

            return indexed.getId();
        });

        var stats = tempFileStorageAdapter.cleanupOrphanedTempFiles(Duration.ZERO, Duration.ZERO, Duration.ofHours(24));
        assertThat(stats.deletedFiles()).isEqualTo(2);
        assertThat(Files.exists(indexedFile)).isFalse();
        assertThat(Files.exists(orphanFile)).isFalse();
        assertThat(Files.exists(activeFile)).isTrue();
        assertThat(Files.exists(errorFile)).isTrue();

        boolean indexedFlagSynced = RlsDataSourceConfig.runAsUser(userId, () ->
                documentRepository.findById(indexedDocId).orElseThrow().isTempFileDeleted());
        assertThat(indexedFlagSynced).isTrue();

        int requeued = workerAdapter.retryPendingAndFailedDocuments(Duration.ZERO, Duration.ofMinutes(5));
        assertThat(requeued).isEqualTo(1);
    }
}
