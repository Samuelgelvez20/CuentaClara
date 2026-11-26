package co.cuentaclara.domain.model;

import java.util.List;

public record ProfileWithInitialBudget(
        FinancialProfile profile,
        List<RecurringObligation> obligations,
        Budget initialBudget,
        List<String> preliminaryRecommendations
) {
}
