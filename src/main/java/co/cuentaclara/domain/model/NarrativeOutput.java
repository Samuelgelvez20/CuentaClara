package co.cuentaclara.domain.model;

public record NarrativeOutput(
        String text,
        boolean usedGemini,
        String providerLabel
) {
}
