package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record MonthAnalysisSnapshot(
        String periodLabel,
        BigDecimal actualIncome,
        BigDecimal fixedObligationsTotal,
        BigDecimal needsSpent,
        BigDecimal needsTarget,
        BigDecimal wantsSpent,
        BigDecimal wantsTarget,
        BigDecimal savingsAchieved,
        BigDecimal savingsTarget,
        int needsPct,
        int wantsPct,
        int savingsPct,
        boolean essentialExceeded,
        int smallExpensesCount,
        BigDecimal smallExpensesTotal,
        int totalTransactionsCount,
        List<String> topNeeds,
        List<String> topWants
) {
}
