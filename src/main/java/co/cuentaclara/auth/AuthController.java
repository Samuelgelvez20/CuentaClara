package co.cuentaclara.auth;

import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import co.cuentaclara.security.jwt.JwtTokenService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
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

    private final AuthService authService;
    private final JwtTokenService jwtTokenService;

    public AuthController(AuthService authService, JwtTokenService jwtTokenService) {
        this.authService = authService;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @Valid @RequestBody AuthService.RegisterRequest request,
            HttpServletResponse response
    ) {
        try {
            AuthService.AuthResponse authResponse = authService.register(request);
            attachAuthCookie(response, authResponse.token(), jwtTokenService.getExpirationSeconds());
            return ResponseEntity.status(HttpStatus.CREATED).body(authResponse);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody AuthService.LoginRequest request,
            HttpServletResponse response
    ) {
        try {
            AuthService.AuthResponse authResponse = authService.login(request);
            attachAuthCookie(response, authResponse.token(), jwtTokenService.getExpirationSeconds());
            return ResponseEntity.ok(authResponse);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletResponse response) {
        attachAuthCookie(response, "", 0);
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
        authService.deleteAccount(principal.userId());
        attachAuthCookie(response, "", 0);
        return ResponseEntity.ok(Map.of("message", "Tu cuenta y todos tus datos han sido eliminados permanentemente."));
    }

    public static void attachAuthCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(JwtTokenService.AUTH_COOKIE_NAME, token)
                .httpOnly(true)
                .path("/")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
