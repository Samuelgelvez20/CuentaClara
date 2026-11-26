package co.cuentaclara.domain.model;

import co.cuentaclara.domain.service.PlanCalculator;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PersistedPlanView(
        UUID planId,
        String targetMonth,
        String status,
        BigDecimal structuralGap,
        List<String> warnings,
        List<PlanCalculator.CalculatedLine> lines,
        List<PlanCalculator.CalculatedWeek> weeks,
        List<PlanActionView> actions
) {
}
