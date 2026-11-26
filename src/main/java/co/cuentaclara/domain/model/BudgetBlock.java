package co.cuentaclara.domain.model;

public enum BudgetBlock {
    NEEDS("Necesidades"),
    WANTS("Deseos / Estilo de vida"),
    SAVINGS("Ahorro / Deudas / Objetivos");

    private final String label;

    BudgetBlock(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
