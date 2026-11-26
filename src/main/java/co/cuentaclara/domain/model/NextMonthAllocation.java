package co.cuentaclara.domain.model;

import java.math.BigDecimal;

public record NextMonthAllocation(
        String nextPeriodLabel,
        int needsPct,
        int wantsPct,
        int savingsPct,
        BigDecimal indispensableAmount,
        BigDecimal recommendedSavingsAmount,
        BigDecimal flexibleWantsAmount,
        BigDecimal potentialExtraSavings
) {
}
