package co.cuentaclara.auth;

import co.cuentaclara.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FirebaseAuthIntegrationTest {

    private static final String TEST_FIREBASE_PROJECT = "cuentaclara-firebase-test";
    private static final String TEST_KID = "firebase-rsa-kid-2026";
    private static KeyPair rsaKeyPair;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("cuentaclara")
            .withUsername("cuentaclara_owner")
            .withPassword("owner_secret_dev");

    @BeforeAll
    static void generateTestRsaKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        rsaKeyPair = gen.generateKeyPair();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "cuentaclara_app");
        registry.add("spring.datasource.password", () -> "app_secret_dev");

        registry.add("cuentaclara.security.firebase.project-id", () -> TEST_FIREBASE_PROJECT);
        registry.add("cuentaclara.security.firebase.api-key", () -> "AIzaSyTestPublicKeyForCuentaClara");
        registry.add("cuentaclara.security.firebase.auth-domain", () -> TEST_FIREBASE_PROJECT + ".firebaseapp.com");
        registry.add("cuentaclara.security.firebase.app-id", () -> "1:402708172122:web:abcdef123456");
    }

    @TestConfiguration
    static class TestFirebaseJwksConfig {

        @Bean
        @Primary
        FirebaseIdTokenVerifierService testFirebaseVerifier(ObjectMapper objectMapper) {
            return new FirebaseIdTokenVerifierService(null, objectMapper, TEST_FIREBASE_PROJECT) {
                @Override
                protected JwksResponse fetchJwks() {
                    RSAPublicKey pub = (RSAPublicKey) rsaKeyPair.getPublic();
                    String n = base64UrlUnsigned(pub.getModulus());
                    String e = base64UrlUnsigned(pub.getPublicExponent());
                    JsonNode jwksNode = objectMapper.valueToTree(Map.of(
                            "keys", List.of(Map.of(
                                    "kty", "RSA",
                                    "alg", "RS256",
                                    "use", "sig",
                                    "kid", TEST_KID,
                                    "n", n,
                                    "e", e
                            ))
                    ));
                    return new JwksResponse(jwksNode, 3600L);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FirebaseIdTokenVerifierService firebaseVerifier;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Firebase Auth: renderiza botón modular en login, verifica RS256 JWKS, sincroniza PostgreSQL y emite cookie JWT")
    void shouldAuthenticateViaFirebaseWebAndRestAndEnforceClaims() throws Exception {
        // 1. La página /auth/login activa el modo Firebase Auth cuando projectId y apiKey están presentes
        String html = mockMvc.perform(get("/auth/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(html).contains("id=\"firebaseGoogleBtn\"", "action=\"/auth/firebase\"", TEST_FIREBASE_PROJECT);

        // 2. Generar un ID Token RS256 válido firmado con la clave privada RSA de prueba
        String validIdToken = buildSignedFirebaseToken(
                TEST_KID,
                "https://securetoken.google.com/" + TEST_FIREBASE_PROJECT,
                TEST_FIREBASE_PROJECT,
                "fb-uid-001",
                "valeria.firebase@gmail.com",
                true,
                "Valeria Gómez",
                Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(1800)
        );

        // 3. Sin consentimiento Ley 1581 en primer ingreso -> rechaza y redirige a /auth/login
        mockMvc.perform(post("/auth/firebase")
                        .param("idToken", validIdToken)
                        .param("aiConsentAccepted", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(userRepository.existsByEmailIgnoreCase("valeria.firebase@gmail.com")).isFalse();

        // 4. Con consentimiento Ley 1581 -> crea usuario en PostgreSQL, emite cookie CC_AUTH_TOKEN y redirige a /profile
        mockMvc.perform(post("/auth/firebase")
                        .param("idToken", validIdToken)
                        .param("aiConsentAccepted", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile"))
                .andExpect(header().exists("Set-Cookie"));
        assertThat(userRepository.existsByEmailIgnoreCase("valeria.firebase@gmail.com")).isTrue();

        // 5. Segundo inicio de sesión del mismo usuario -> redirige directo a /dashboard
        mockMvc.perform(post("/auth/firebase")
                        .param("idToken", validIdToken)
                        .param("aiConsentAccepted", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(header().exists("Set-Cookie"));

        // 6. Endpoint REST /api/auth/firebase devuelve JWT propio de CuentaClara + datos del usuario
        mockMvc.perform(post("/api/auth/firebase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idToken": "%s",
                                  "aiConsentAccepted": true
                                }
                                """.formatted(validIdToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("valeria.firebase@gmail.com"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.newUser").value(false));

        // 7. Rechaza tokens con audiencia incorrecta, correo no verificado o expirados
        String wrongAudienceToken = buildSignedFirebaseToken(
                TEST_KID,
                "https://securetoken.google.com/" + TEST_FIREBASE_PROJECT,
                "otro-proyecto-ajeno",
                "fb-uid-002",
                "intruso@gmail.com",
                true,
                "Intruso",
                Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(1800)
        );
        assertThatThrownBy(() -> firebaseVerifier.verifyIdToken(wrongAudienceToken))
                .isInstanceOf(IllegalArgumentException.class);

        String unverifiedEmailToken = buildSignedFirebaseToken(
                TEST_KID,
                "https://securetoken.google.com/" + TEST_FIREBASE_PROJECT,
                TEST_FIREBASE_PROJECT,
                "fb-uid-003",
                "noverificado@gmail.com",
                false,
                "Sin Verificar",
                Instant.now().minusSeconds(10),
                Instant.now().plusSeconds(1800)
        );
        assertThatThrownBy(() -> firebaseVerifier.verifyIdToken(unverifiedEmailToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verificado");
    }

    private static String buildSignedFirebaseToken(
            String kid,
            String issuer,
            String audience,
            String uid,
            String email,
            boolean emailVerified,
            String name,
            Instant issuedAt,
            Instant expiresAt
    ) {
        RSAPrivateKey privateKey = (RSAPrivateKey) rsaKeyPair.getPrivate();
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(uid)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("name", name)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
            bytes = stripped;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
