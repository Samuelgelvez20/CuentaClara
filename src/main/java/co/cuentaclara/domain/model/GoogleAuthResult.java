package co.cuentaclara.domain.model;

public record GoogleAuthResult(
        AuthResponse authResponse,
        boolean newUser
) {
}
