package co.cuentaclara.domain.model;

public record VerifiedExternalUser(
        String uid,
        String email,
        String fullName
) {
}
