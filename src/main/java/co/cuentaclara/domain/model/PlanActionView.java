package co.cuentaclara.domain.model;

public record PlanActionView(
        int position,
        String title,
        String explanation,
        String categoryName,
        String source
) {
}
