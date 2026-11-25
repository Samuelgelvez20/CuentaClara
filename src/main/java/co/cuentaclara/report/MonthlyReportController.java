package co.cuentaclara.report;

import co.cuentaclara.profile.Budget;
import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class MonthlyReportController {

    private final MonthlyAnalysisAndReportService reportService;

    public MonthlyReportController(MonthlyAnalysisAndReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/generate")
    public ResponseEntity<MonthlyAnalysisAndReportService.PeriodClosureReportView> generateReport(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", defaultValue = "2026-10") String month
    ) {
        return ResponseEntity.ok(reportService.generatePeriodReportAndNextPlan(principal.userId(), month));
    }

    @PostMapping("/simulate-first-month")
    public ResponseEntity<MonthlyAnalysisAndReportService.PeriodClosureReportView> simulateFirstMonth(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", defaultValue = "2026-10") String month
    ) {
        return ResponseEntity.ok(reportService.simulateFirstMonthAndGenerateReport(principal.userId(), month));
    }

    @PatchMapping("/budgets/{budgetId}/decision")
    public ResponseEntity<?> updatePlanDecision(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID budgetId,
            @RequestBody MonthlyAnalysisAndReportService.PlanDecisionRequest request
    ) {
        try {
            Budget updated = reportService.updateSuggestedPlanDecision(principal.userId(), budgetId, request);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<MonthlyReportEntity>> listReports(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return ResponseEntity.ok(reportService.listReports(principal.userId()));
    }
}
