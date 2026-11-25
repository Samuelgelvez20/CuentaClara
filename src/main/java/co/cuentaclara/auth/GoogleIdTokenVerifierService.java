package co.cuentaclara.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Verificador de ID Tokens de Google Identity Services (RF-01).
 * Nunca confía en el payload Base64 decodificado localmente sin verificación criptográfica:
 * valida firma, vigencia, emisor, audiencia (client_id) y correo verificado contra Google.
 */
@Service
public class GoogleIdTokenVerifierService {

    private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String googleClientId;

    @Autowired
    public GoogleIdTokenVerifierService(
            ObjectMapper objectMapper,
            @Value("${cuentaclara.security.google-client-id:}") String googleClientId
    ) {
        this(RestClient.create(), objectMapper, googleClientId);
    }

    protected GoogleIdTokenVerifierService(
            RestClient restClient,
            ObjectMapper objectMapper,
            String googleClientId
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.googleClientId = googleClientId != null ? googleClientId.trim() : "";
    }

    public VerifiedGoogleUser verifyIdToken(String idTokenCredential) {
        if (googleClientId.isEmpty()) {
            throw new IllegalArgumentException(
                    "El inicio de sesión con Google requiere GOOGLE_CLIENT_ID configurado en el servidor."
            );
        }
        if (idTokenCredential == null || idTokenCredential.isBlank()) {
            throw new IllegalArgumentException("No se recibió una credencial válida de Google.");
        }

        // Validación estructural mínima de JWS firmado (3 segmentos con firma no vacía) antes de consultar a Google
        String[] segments = idTokenCredential.trim().split("\\.");
        if (segments.length != 3 || segments[0].isBlank() || segments[1].isBlank() || segments[2].isBlank()) {
            throw new IllegalArgumentException("El token de Google no tiene una firma JWS válida.");
        }

        JsonNode tokenInfo = fetchTokenInfo(idTokenCredential.trim());
        if (tokenInfo == null || tokenInfo.isMissingNode()) {
            throw new IllegalArgumentException("No fue posible verificar el token de Google.");
        }

        String aud = textOrNull(tokenInfo, "aud");
        if (!googleClientId.equals(aud)) {
            throw new IllegalArgumentException(
                    "El token de Google no corresponde a esta aplicación (audiencia inválida)."
            );
        }

        String iss = textOrNull(tokenInfo, "iss");
        if (iss != null && !iss.equals("https://accounts.google.com") && !iss.equals("accounts.google.com")) {
            throw new IllegalArgumentException("Emisor del token de Google no autorizado.");
        }

        JsonNode emailVerifiedNode = tokenInfo.path("email_verified");
        boolean emailVerified = emailVerifiedNode.isBoolean()
                ? emailVerifiedNode.asBoolean()
                : "true".equalsIgnoreCase(emailVerifiedNode.asText("false"));
        if (!emailVerified) {
            throw new IllegalArgumentException("Tu cuenta de Google debe tener el correo electrónico verificado.");
        }

        String email = textOrNull(tokenInfo, "email");
        String subject = textOrNull(tokenInfo, "sub");
        if (email == null || subject == null) {
            throw new IllegalArgumentException("El token de Google verificado no contiene correo ni identificador.");
        }

        String name = textOrNull(tokenInfo, "name");
        return new VerifiedGoogleUser(subject, email.toLowerCase(), name != null ? name : email.split("@")[0]);
    }

    protected JsonNode fetchTokenInfo(String idTokenCredential) {
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(GOOGLE_TOKENINFO_URL + "?id_token={idToken}", idTokenCredential)
                    .retrieve()
                    .toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalArgumentException("Token de Google rechazado o expirado.");
            }
            return objectMapper.readTree(response.getBody());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Token de Google inválido, expirado o no verificable con Google.",
                    ex
            );
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    public record VerifiedGoogleUser(
            String subject,
            String email,
            String fullName
    ) {
    }
}
