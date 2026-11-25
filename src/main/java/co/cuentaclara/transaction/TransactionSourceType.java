package co.cuentaclara.transaction;

public enum TransactionSourceType {
    INVOICE_XML("Factura Electrónica XML"),
    INVOICE_PDF("Factura PDF"),
    INVOICE_IMAGE("Foto / Tirilla"),
    MANUAL("Gasto Manual");

    private final String label;

    TransactionSourceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
