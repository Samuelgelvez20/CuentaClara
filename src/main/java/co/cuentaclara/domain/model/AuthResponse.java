package co.cuentaclara.domain.model;

import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String email,
        String fullName,
        String token
) {
    public AuthenticatedUser toAuthenticatedUser() {
        return new AuthenticatedUser(userId, email, fullName);
    }
}
