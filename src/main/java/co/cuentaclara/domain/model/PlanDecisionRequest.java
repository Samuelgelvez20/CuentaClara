package co.cuentaclara.domain.model;

import java.math.BigDecimal;

public record PlanDecisionRequest(
        String status,
        BigDecimal referenceIncome,
        Integer needsPct,
        Integer wantsPct,
        Integer savingsPct
) {
}
