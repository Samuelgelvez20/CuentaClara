package co.cuentaclara.domain.model;

public enum DocumentStatus {
    PENDING("Pendiente"),
    PROCESSING("Procesando"),
    AWAITING_CONFIRMATION("Por confirmar"),
    INDEXED("Indexado"),
    ERROR("Error");

    private final String label;

    DocumentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
