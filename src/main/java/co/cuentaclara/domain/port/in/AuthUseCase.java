package co.cuentaclara.domain.port.in;

import co.cuentaclara.domain.model.AuthResponse;
import co.cuentaclara.domain.model.GoogleAuthResult;
import java.util.UUID;

public interface AuthUseCase {

    AuthResponse register(String fullName, String email, String password, boolean aiConsentAccepted);

    AuthResponse login(String email, String password);

    GoogleAuthResult loginOrRegisterWithGoogle(String email, String fullName);

    GoogleAuthResult loginOrRegisterWithGoogle(String email, String fullName, boolean aiConsentAccepted);

    GoogleAuthResult loginWithGoogleIdToken(String idToken, boolean aiConsentAccepted);

    GoogleAuthResult loginWithFirebaseIdToken(String idToken, boolean aiConsentAccepted);

    void deleteAccount(UUID userId);
}
