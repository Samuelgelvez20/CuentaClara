package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.VerifiedExternalUser;

public interface GoogleTokenVerifierPort {

    VerifiedExternalUser verifyIdToken(String idToken);
}
