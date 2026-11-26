package co.cuentaclara.domain.model;

import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        String email,
        String fullName
) {
}
