package co.cuentaclara.application.service;

import co.cuentaclara.domain.model.AuthResponse;
import co.cuentaclara.domain.model.GoogleAuthResult;
import co.cuentaclara.domain.model.User;
import co.cuentaclara.domain.model.VerifiedExternalUser;
import co.cuentaclara.domain.port.in.AuthUseCase;
import co.cuentaclara.domain.port.out.AccessTokenPort;
import co.cuentaclara.domain.port.out.FirebaseTokenVerifierPort;
import co.cuentaclara.domain.port.out.GoogleTokenVerifierPort;
import co.cuentaclara.domain.port.out.PasswordHasherPort;
import co.cuentaclara.domain.port.out.TempFileStoragePort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import co.cuentaclara.domain.port.out.UserRepositoryPort;
import java.time.OffsetDateTime;
import java.util.UUID;

public class AuthApplicationService implements AuthUseCase {

    private final UserRepositoryPort userRepository;
    private final PasswordHasherPort passwordHasher;
    private final AccessTokenPort accessTokenPort;
    private final GoogleTokenVerifierPort googleTokenVerifier;
    private final FirebaseTokenVerifierPort firebaseTokenVerifier;
    private final TempFileStoragePort tempFileStorage;
    private final TransactionRunnerPort transactionRunner;

    public AuthApplicationService(
            UserRepositoryPort userRepository,
            PasswordHasherPort passwordHasher,
            AccessTokenPort accessTokenPort,
            GoogleTokenVerifierPort googleTokenVerifier,
            FirebaseTokenVerifierPort firebaseTokenVerifier,
            TempFileStoragePort tempFileStorage,
            TransactionRunnerPort transactionRunner
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenPort = accessTokenPort;
        this.googleTokenVerifier = googleTokenVerifier;
        this.firebaseTokenVerifier = firebaseTokenVerifier;
        this.tempFileStorage = tempFileStorage;
        this.transactionRunner = transactionRunner;
    }

    @Override
    public AuthResponse register(String fullName, String email, String password, boolean aiConsentAccepted) {
        return transactionRunner.runInTransaction(() -> {
            if (!aiConsentAccepted) {
                throw new IllegalArgumentException(
                        "Debes aceptar el consentimiento explícito de procesamiento de texto y transferencia de datos (Ley 1581 de 2012)."
                );
            }
            if (email == null || email.isBlank() || fullName == null || fullName.isBlank()
                    || password == null || password.length() < 8) {
                throw new IllegalArgumentException("Datos de registro inválidos.");
            }
            String normalizedEmail = email.trim().toLowerCase();
            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                throw new IllegalArgumentException("Ya existe una cuenta registrada con este correo electrónico.");
            }

            String passwordHash = passwordHasher.encode(password);
            User savedUser = userRepository.save(new User(
                    normalizedEmail,
                    passwordHash,
                    fullName,
                    true
            ));

            String token = accessTokenPort.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getFullName());
            return new AuthResponse(savedUser.getId(), savedUser.getEmail(), savedUser.getFullName(), token);
        });
    }

    @Override
    public AuthResponse login(String email, String password) {
        return transactionRunner.runInReadOnlyTransaction(() -> {
            if (email == null || email.isBlank() || password == null) {
                throw new IllegalArgumentException("Credenciales inválidas. Verifica tu correo y contraseña.");
            }
            String normalizedEmail = email.trim().toLowerCase();
            User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                    .orElseThrow(() -> new IllegalArgumentException("Credenciales inválidas. Verifica tu correo y contraseña."));

            if (!passwordHasher.matches(password, user.getPasswordHash())) {
                throw new IllegalArgumentException("Credenciales inválidas. Verifica tu correo y contraseña.");
            }

            String token = accessTokenPort.generateToken(user.getId(), user.getEmail(), user.getFullName());
            return new AuthResponse(user.getId(), user.getEmail(), user.getFullName(), token);
        });
    }

    @Override
    public GoogleAuthResult loginOrRegisterWithGoogle(String email, String fullName) {
        return loginOrRegisterWithGoogle(email, fullName, false);
    }

    @Override
    public GoogleAuthResult loginOrRegisterWithGoogle(String email, String fullName, boolean aiConsentAccepted) {
        return transactionRunner.runInTransaction(() -> {
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
                if (!existing.isAiConsentAccepted()) {
                    if (!aiConsentAccepted) {
                        throw new IllegalArgumentException(
                                "Debes aceptar el consentimiento explícito de privacidad (Ley 1581 de 2012)."
                        );
                    }
                    existing.setAiConsentAccepted(true);
                    existing.setAiConsentAt(OffsetDateTime.now());
                    existing = userRepository.save(existing);
                }
                String token = accessTokenPort.generateToken(existing.getId(), existing.getEmail(), existing.getFullName());
                return new GoogleAuthResult(
                        new AuthResponse(existing.getId(), existing.getEmail(), existing.getFullName(), token),
                        false
                );
            }

            if (!aiConsentAccepted) {
                throw new IllegalArgumentException(
                        "Debes aceptar el consentimiento explícito de procesamiento de texto y transferencia de datos (Ley 1581 de 2012)."
                );
            }

            String randomSecretHash = passwordHasher.encode("GOOGLE_OAUTH2_" + UUID.randomUUID());
            User created = userRepository.save(new User(
                    normalizedEmail,
                    randomSecretHash,
                    resolvedName,
                    true
            ));
            String token = accessTokenPort.generateToken(created.getId(), created.getEmail(), created.getFullName());
            return new GoogleAuthResult(
                    new AuthResponse(created.getId(), created.getEmail(), created.getFullName(), token),
                    true
            );
        });
    }

    @Override
    public GoogleAuthResult loginWithGoogleIdToken(String idToken, boolean aiConsentAccepted) {
        VerifiedExternalUser verified = googleTokenVerifier.verifyIdToken(idToken);
        return loginOrRegisterWithGoogle(verified.email(), verified.fullName(), aiConsentAccepted);
    }

    @Override
    public GoogleAuthResult loginWithFirebaseIdToken(String idToken, boolean aiConsentAccepted) {
        VerifiedExternalUser verified = firebaseTokenVerifier.verifyIdToken(idToken);
        return loginOrRegisterWithGoogle(verified.email(), verified.fullName(), aiConsentAccepted);
    }

    @Override
    public void deleteAccount(UUID userId) {
        transactionRunner.runInTransactionVoid(() -> {
            userRepository.deleteById(userId);
            tempFileStorage.deleteUserTempDirectory(userId);
        });
    }
}
