package co.cuentaclara.domain.model;

public enum PaymentFrequency {
    MONTHLY("Mensual"),
    BIWEEKLY("Quincenal"),
    WEEKLY("Semanal"),
    VARIABLE("Variable / Independiente");

    private final String label;

    PaymentFrequency(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
