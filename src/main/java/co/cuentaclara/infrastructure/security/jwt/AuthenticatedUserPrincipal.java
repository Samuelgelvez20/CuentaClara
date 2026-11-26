package co.cuentaclara.infrastructure.security.jwt;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record AuthenticatedUserPrincipal(
        UUID userId,
        String email,
        String fullName
) implements Principal, Serializable {

    @Override
    public String getName() {
        return email;
    }
}
