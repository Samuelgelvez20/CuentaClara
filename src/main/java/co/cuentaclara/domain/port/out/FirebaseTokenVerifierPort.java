package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.VerifiedExternalUser;

public interface FirebaseTokenVerifierPort {

    VerifiedExternalUser verifyIdToken(String idToken);
}
