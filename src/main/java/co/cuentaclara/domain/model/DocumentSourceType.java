package co.cuentaclara.domain.model;

public enum DocumentSourceType {
    XML_UBL("Factura electrónica XML UBL 2.1"),
    PDF_DIGITAL("Factura PDF digital"),
    PDF_SCANNED("Factura PDF escaneada"),
    IMAGE("Foto de factura / tirilla"),
    STATEMENT_PDF("Extracto bancario PDF digital");

    private final String label;

    DocumentSourceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
