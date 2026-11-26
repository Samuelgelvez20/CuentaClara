package co.cuentaclara.domain.port.in;

import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.model.MonthAnalysisSnapshot;
import co.cuentaclara.domain.model.MonthlyReport;
import co.cuentaclara.domain.model.NarrativeOutput;
import co.cuentaclara.domain.model.NextMonthAllocation;
import co.cuentaclara.domain.model.PeriodClosureReportView;
import co.cuentaclara.domain.model.PersistedPlanView;
import co.cuentaclara.domain.model.PlanDecisionRequest;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonthlyReportUseCase {

    PeriodClosureReportView generatePeriodReportAndNextPlan(UUID userId, String periodSpec);

    Budget updateSuggestedPlanDecision(UUID userId, UUID budgetId, PlanDecisionRequest request);

    PeriodClosureReportView simulateFirstMonthAndGenerateReport(UUID userId, String yearMonthStr);

    List<MonthlyReport> listReports(UUID userId);

    Optional<MonthlyReport> findLatestReportForPeriod(UUID userId, String periodLabel);

    Optional<Budget> findSuggestedBudgetById(UUID budgetId);

    MonthAnalysisSnapshot buildMonthSnapshot(UUID userId, String periodSpec);

    NextMonthAllocation computeNextMonthAllocation(MonthAnalysisSnapshot snapshot, Budget nextPlan);

    Optional<PersistedPlanView> findPlanForTargetMonth(UUID userId, YearMonth targetMonth);

    boolean isAiAdvisorConfigured();

    String getAiAdvisorModelName();

    NarrativeOutput generateMonthSummaryNarrative(MonthAnalysisSnapshot snapshot);

    NarrativeOutput generateNextMonthNarrative(MonthAnalysisSnapshot snapshot, NextMonthAllocation nextPlan);
}
