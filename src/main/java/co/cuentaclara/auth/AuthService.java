package co.cuentaclara.auth;

import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import co.cuentaclara.security.jwt.JwtTokenService;
import co.cuentaclara.user.User;
import co.cuentaclara.user.UserRepository;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!request.aiConsentAccepted()) {
            throw new IllegalArgumentException(
                    "Debes aceptar el consentimiento explícito de procesamiento de texto y transferencia de datos (Ley 1581 de 2012)."
            );
        }
        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalArgumentException("Ya existe una cuenta registrada con este correo electrónico.");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User savedUser = userRepository.save(new User(
                normalizedEmail,
                passwordHash,
                request.fullName(),
                true
        ));

        String token = jwtTokenService.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getFullName());
        return new AuthResponse(savedUser.getId(), savedUser.getEmail(), savedUser.getFullName(), token);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("Credenciales inválidas. Verifica tu correo y contraseña."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Credenciales inválidas. Verifica tu correo y contraseña.");
        }

        String token = jwtTokenService.generateToken(user.getId(), user.getEmail(), user.getFullName());
        return new AuthResponse(user.getId(), user.getEmail(), user.getFullName(), token);
    }

    /**
     * Inicia sesión o registra automáticamente al usuario mediante su cuenta de Google (OAuth2 / Google Identity).
     */
    @Transactional
    public GoogleAuthResult loginOrRegisterWithGoogle(String email, String fullName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("El correo de la cuenta de Google es obligatorio.");
        }
        String normalizedEmail = email.trim().toLowerCase();
        String resolvedName = (fullName != null && !fullName.isBlank())
                ? fullName.trim()
                : normalizedEmail.split("@")[0];

        var existingOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingOpt.isPresent()) {
            User existing = existingOpt.get();
            String token = jwtTokenService.generateToken(existing.getId(), existing.getEmail(), existing.getFullName());
            return new GoogleAuthResult(
                    new AuthResponse(existing.getId(), existing.getEmail(), existing.getFullName(), token),
                    false
            );
        }

        String randomSecretHash = passwordEncoder.encode("GOOGLE_OAUTH2_" + UUID.randomUUID());
        User created = userRepository.save(new User(
                normalizedEmail,
                randomSecretHash,
                resolvedName,
                true
        ));
        String token = jwtTokenService.generateToken(created.getId(), created.getEmail(), created.getFullName());
        return new GoogleAuthResult(
                new AuthResponse(created.getId(), created.getEmail(), created.getFullName(), token),
                true
        );
    }

    public record GoogleAuthResult(
            AuthResponse authResponse,
            boolean newUser
    ) {
    }

    /**
     * Elimina la cuenta del usuario y todos sus datos relacionados en cascada (RF-14, RNF-04).
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        userRepository.deleteById(userId);
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

    public record AuthResponse(
            UUID userId,
            String email,
            String fullName,
            String token
    ) {
        public AuthenticatedUserPrincipal toPrincipal() {
            return new AuthenticatedUserPrincipal(userId, email, fullName);
        }
    }
}
