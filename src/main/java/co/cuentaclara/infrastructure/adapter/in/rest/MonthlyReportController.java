package co.cuentaclara.infrastructure.adapter.in.rest;

import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.model.MonthlyReport;
import co.cuentaclara.domain.model.PeriodClosureReportView;
import co.cuentaclara.domain.model.PlanDecisionRequest;
import co.cuentaclara.domain.port.in.MonthlyReportUseCase;
import co.cuentaclara.infrastructure.security.jwt.AuthenticatedUserPrincipal;
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

    private final MonthlyReportUseCase monthlyReportUseCase;

    public MonthlyReportController(MonthlyReportUseCase monthlyReportUseCase) {
        this.monthlyReportUseCase = monthlyReportUseCase;
    }

    @PostMapping("/generate")
    public ResponseEntity<PeriodClosureReportView> generateReport(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", defaultValue = "2026-10") String month
    ) {
        return ResponseEntity.ok(monthlyReportUseCase.generatePeriodReportAndNextPlan(principal.userId(), month));
    }

    @PostMapping("/simulate-first-month")
    public ResponseEntity<PeriodClosureReportView> simulateFirstMonth(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", defaultValue = "2026-10") String month
    ) {
        return ResponseEntity.ok(monthlyReportUseCase.simulateFirstMonthAndGenerateReport(principal.userId(), month));
    }

    @PatchMapping("/budgets/{budgetId}/decision")
    public ResponseEntity<?> updatePlanDecision(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID budgetId,
            @RequestBody PlanDecisionRequest request
    ) {
        try {
            Budget updated = monthlyReportUseCase.updateSuggestedPlanDecision(principal.userId(), budgetId, request);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<MonthlyReport>> listReports(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return ResponseEntity.ok(monthlyReportUseCase.listReports(principal.userId()));
    }
}
