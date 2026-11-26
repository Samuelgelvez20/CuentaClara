package co.cuentaclara.domain.model;

public record ValidatedFile(
        String originalFilename,
        String mimeType,
        DocumentSourceType sourceType,
        long fileSizeBytes,
        String sha256Hash
) {
}
