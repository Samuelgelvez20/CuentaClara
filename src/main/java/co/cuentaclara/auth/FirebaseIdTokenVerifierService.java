package co.cuentaclara.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Verificador criptográfico de ID Tokens de Firebase Authentication (RS256).
 * Valida firma RSA contra el JWKS oficial de Google Secure Token, vigencia (exp/iat),
 * emisor (https://securetoken.google.com/{projectId}), audiencia ({projectId}),
 * uid no vacío y correo electrónico verificado, sin requerir archivo JSON de cuenta de servicio.
 */
@Service
public class FirebaseIdTokenVerifierService {

    private static final String FIREBASE_JWKS_URL =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    private static final Pattern MAX_AGE_PATTERN = Pattern.compile("max-age=(\\d+)");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String firebaseProjectId;

    private volatile Map<String, RSAPublicKey> cachedKeys = Collections.emptyMap();
    private volatile Instant cacheExpiresAt = Instant.EPOCH;
    private volatile Instant lastRefreshAt = Instant.EPOCH;

    @Autowired
    public FirebaseIdTokenVerifierService(
            ObjectMapper objectMapper,
            @Value("${cuentaclara.security.firebase.project-id:}") String firebaseProjectId
    ) {
        this(RestClient.create(), objectMapper, firebaseProjectId);
    }

    protected FirebaseIdTokenVerifierService(
            RestClient restClient,
            ObjectMapper objectMapper,
            String firebaseProjectId
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.firebaseProjectId = firebaseProjectId != null ? firebaseProjectId.trim() : "";
    }

    public boolean isConfigured() {
        return !firebaseProjectId.isEmpty();
    }

    public VerifiedFirebaseUser verifyIdToken(String idToken) {
        if (firebaseProjectId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Firebase Auth requiere configurar FIREBASE_PROJECT_ID en el servidor."
            );
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("No se recibió un ID Token válido de Firebase.");
        }

        String trimmed = idToken.trim();
        String[] segments = trimmed.split("\\.");
        if (segments.length != 3 || segments[0].isBlank() || segments[1].isBlank() || segments[2].isBlank()) {
            throw new IllegalArgumentException("El ID Token de Firebase no tiene estructura JWS válida.");
        }

        JsonNode header;
        try {
            byte[] headerBytes = Base64.getUrlDecoder().decode(segments[0]);
            header = objectMapper.readTree(headerBytes);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Encabezado del token de Firebase malformado.", ex);
        }

        String alg = header.path("alg").asText("");
        if (!"RS256".equals(alg)) {
            throw new IllegalArgumentException("Algoritmo de firma de Firebase no permitido: se exige RS256.");
        }

        String kid = header.path("kid").asText("").trim();
        if (kid.isEmpty()) {
            throw new IllegalArgumentException("El token de Firebase no incluye identificador de clave (kid).");
        }

        RSAPublicKey publicKey = resolvePublicKey(kid);
        if (publicKey == null) {
            throw new IllegalArgumentException("Clave pública de Firebase desconocida o rotada (kid inválido).");
        }

        String expectedIssuer = "https://securetoken.google.com/" + firebaseProjectId;
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(expectedIssuer)
                    .requireAudience(firebaseProjectId)
                    .clockSkewSeconds(60)
                    .build()
                    .parseSignedClaims(trimmed)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Token de Firebase inválido, expirado o perteneciente a otro proyecto: " + ex.getMessage(),
                    ex
            );
        }

        String uid = claims.getSubject();
        if (uid == null || uid.isBlank() || uid.length() > 128) {
            throw new IllegalArgumentException("El token de Firebase no contiene un uid válido.");
        }

        if (claims.getIssuedAt() == null
                || claims.getIssuedAt().toInstant().isAfter(Instant.now().plusSeconds(60))) {
            throw new IllegalArgumentException("Fecha de emisión (iat) inválida en el token de Firebase.");
        }

        String email = claims.get("email", String.class);
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("La cuenta autenticada en Firebase no proporcionó correo electrónico.");
        }

        Object emailVerifiedClaim = claims.get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(emailVerifiedClaim)
                || "true".equalsIgnoreCase(String.valueOf(emailVerifiedClaim));
        if (!emailVerified) {
            throw new IllegalArgumentException("El correo de la cuenta de Firebase debe estar verificado.");
        }

        String name = claims.get("name", String.class);
        String resolvedName = (name != null && !name.isBlank())
                ? name.trim()
                : email.trim().split("@")[0];

        return new VerifiedFirebaseUser(uid.trim(), email.trim().toLowerCase(), resolvedName);
    }

    protected synchronized RSAPublicKey resolvePublicKey(String kid) {
        Instant now = Instant.now();
        if (now.isAfter(cacheExpiresAt) || cachedKeys.isEmpty()) {
            refreshJwksKeys(now);
        }
        RSAPublicKey key = cachedKeys.get(kid);
        if (key == null && Duration.between(lastRefreshAt, now).getSeconds() >= 60) {
            refreshJwksKeys(now);
            key = cachedKeys.get(kid);
        }
        return key;
    }

    private void refreshJwksKeys(Instant now) {
        JwksResponse jwks = fetchJwks();
        Map<String, RSAPublicKey> parsed = new HashMap<>();
        JsonNode keysNode = jwks.body().path("keys");
        if (keysNode.isArray()) {
            for (JsonNode jwk : keysNode) {
                if (!"RSA".equalsIgnoreCase(jwk.path("kty").asText())) {
                    continue;
                }
                String keyId = jwk.path("kid").asText("").trim();
                String nB64 = jwk.path("n").asText("").trim();
                String eB64 = jwk.path("e").asText("").trim();
                if (keyId.isEmpty() || nB64.isEmpty() || eB64.isEmpty()) {
                    continue;
                }
                try {
                    BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(nB64));
                    BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(eB64));
                    RSAPublicKey rsaKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                            .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                    parsed.put(keyId, rsaKey);
                } catch (Exception ignored) {
                    // Ignorar entradas JWK malformadas individuales
                }
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("No se pudieron cargar las claves públicas JWKS de Firebase.");
        }
        this.cachedKeys = Collections.unmodifiableMap(parsed);
        this.lastRefreshAt = now;
        this.cacheExpiresAt = now.plusSeconds(Math.max(300L, jwks.maxAgeSeconds()));
    }

    protected JwksResponse fetchJwks() {
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(FIREBASE_JWKS_URL)
                    .retrieve()
                    .toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalArgumentException("Respuesta inválida al consultar claves públicas de Firebase.");
            }
            long maxAge = 3600L;
            List<String> cacheHeaders = response.getHeaders().get("Cache-Control");
            if (cacheHeaders != null) {
                for (String header : cacheHeaders) {
                    Matcher matcher = MAX_AGE_PATTERN.matcher(header);
                    if (matcher.find()) {
                        maxAge = Long.parseLong(matcher.group(1));
                        break;
                    }
                }
            }
            return new JwksResponse(objectMapper.readTree(response.getBody()), maxAge);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("No fue posible consultar las claves públicas de Firebase Auth.", ex);
        }
    }

    public record JwksResponse(JsonNode body, long maxAgeSeconds) {
    }

    public record VerifiedFirebaseUser(String uid, String email, String fullName) {
    }
}
