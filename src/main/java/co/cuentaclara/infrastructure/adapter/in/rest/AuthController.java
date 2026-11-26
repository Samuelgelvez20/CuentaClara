package co.cuentaclara.infrastructure.adapter.in.rest;

import co.cuentaclara.domain.model.AuthResponse;
import co.cuentaclara.domain.model.GoogleAuthResult;
import co.cuentaclara.domain.port.in.AuthUseCase;
import co.cuentaclara.infrastructure.adapter.in.AuthCookieSupport;
import co.cuentaclara.infrastructure.security.jwt.AuthenticatedUserPrincipal;
import co.cuentaclara.infrastructure.security.jwt.JwtTokenService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUseCase authUseCase;
    private final JwtTokenService jwtTokenService;

    public AuthController(AuthUseCase authUseCase, JwtTokenService jwtTokenService) {
        this.authUseCase = authUseCase;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response
    ) {
        try {
            AuthResponse authResponse = authUseCase.register(
                    request.fullName(),
                    request.email(),
                    request.password(),
                    request.aiConsentAccepted()
            );
            AuthCookieSupport.attachAuthCookie(response, authResponse.token(), jwtTokenService.getExpirationSeconds());
            return ResponseEntity.status(HttpStatus.CREATED).body(authResponse);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        try {
            AuthResponse authResponse = authUseCase.login(request.email(), request.password());
            AuthCookieSupport.attachAuthCookie(response, authResponse.token(), jwtTokenService.getExpirationSeconds());
            return ResponseEntity.ok(authResponse);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/firebase")
    public ResponseEntity<?> loginWithFirebase(
            @RequestBody FirebaseAuthRequest request,
            HttpServletResponse response
    ) {
        try {
            GoogleAuthResult result = authUseCase.loginWithFirebaseIdToken(
                    request != null ? request.idToken() : null,
                    request != null && request.aiConsentAccepted()
            );
            AuthResponse authResponse = result.authResponse();
            AuthCookieSupport.attachAuthCookie(response, authResponse.token(), jwtTokenService.getExpirationSeconds());
            return ResponseEntity.ok(Map.of(
                    "userId", authResponse.userId(),
                    "email", authResponse.email(),
                    "fullName", authResponse.fullName(),
                    "token", authResponse.token(),
                    "newUser", result.newUser()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletResponse response) {
        AuthCookieSupport.attachAuthCookie(response, "", 0);
        return ResponseEntity.ok(Map.of("message", "Sesión cerrada correctamente"));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthenticatedUserPrincipal> me(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return ResponseEntity.ok(principal);
    }

    @DeleteMapping("/account")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            HttpServletResponse response
    ) {
        authUseCase.deleteAccount(principal.userId());
        AuthCookieSupport.attachAuthCookie(response, "", 0);
        return ResponseEntity.ok(Map.of("message", "Tu cuenta y todos tus datos han sido eliminados permanentemente."));
    }

    public record RegisterRequest(
            @NotBlank(message = "El nombre completo es obligatorio")
            @Size(max = 150)
            String fullName,

            @NotBlank(message = "El correo electrónico es obligatorio")
            @Email(message = "Formato de correo inválido")
            String email,

            @NotBlank(message = "La contraseña es obligatoria")
            @Size(min = 8, max = 100, message = "La contraseña debe tener al menos 8 caracteres")
            String password,

            @AssertTrue(message = "Debes aceptar el consentimiento de privacidad (Ley 1581 de 2012)")
            boolean aiConsentAccepted
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "El correo electrónico es obligatorio")
            @Email(message = "Formato de correo inválido")
            String email,

            @NotBlank(message = "La contraseña es obligatoria")
            String password
    ) {
    }

    public record FirebaseAuthRequest(String idToken, boolean aiConsentAccepted) {
    }
}
