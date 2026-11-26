package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.AuthenticatedUser;
import java.util.Optional;
import java.util.UUID;

public interface AccessTokenPort {

    String generateToken(UUID userId, String email, String fullName);

    Optional<AuthenticatedUser> parseAndValidate(String token);
}
