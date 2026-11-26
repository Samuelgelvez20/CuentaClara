package co.cuentaclara.domain.model;

import java.util.List;

public record PeriodClosureReportView(
        MonthlyReport report,
        List<BackedRecommendation> recommendations,
        Budget suggestedNextPlan,
        PersistedPlanView calculatedPlan
) {
    public PeriodClosureReportView(
            MonthlyReport report,
            List<BackedRecommendation> recommendations,
            Budget suggestedNextPlan
    ) {
        this(report, recommendations, suggestedNextPlan, null);
    }
}
