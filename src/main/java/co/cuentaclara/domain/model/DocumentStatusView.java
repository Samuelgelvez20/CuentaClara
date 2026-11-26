package co.cuentaclara.domain.model;

public record DocumentStatusView(
        Document document,
        DocumentExtraction extraction
) {
}
