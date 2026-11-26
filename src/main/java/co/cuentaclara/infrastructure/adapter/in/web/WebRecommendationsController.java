package co.cuentaclara.infrastructure.adapter.in.web;

import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.model.DocumentStatusView;
import co.cuentaclara.domain.model.MonthAnalysisSnapshot;
import co.cuentaclara.domain.model.NarrativeOutput;
import co.cuentaclara.domain.model.NextMonthAllocation;
import co.cuentaclara.domain.model.PeriodClosureReportView;
import co.cuentaclara.domain.model.PeriodWindow;
import co.cuentaclara.domain.model.PlanDecisionRequest;
import co.cuentaclara.domain.port.in.DocumentIngestionUseCase;
import co.cuentaclara.domain.port.in.FinancialProfileUseCase;
import co.cuentaclara.domain.port.in.MonthlyReportUseCase;
import co.cuentaclara.infrastructure.security.jwt.AuthenticatedUserPrincipal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/recommendations")
public class WebRecommendationsController {

    private static final Locale ES_CO = Locale.forLanguageTag("es-CO");

    private final FinancialProfileUseCase profileUseCase;
    private final DocumentIngestionUseCase ingestionUseCase;
    private final MonthlyReportUseCase reportUseCase;

    public WebRecommendationsController(
            FinancialProfileUseCase profileUseCase,
            DocumentIngestionUseCase ingestionUseCase,
            MonthlyReportUseCase reportUseCase
    ) {
        this.profileUseCase = profileUseCase;
        this.ingestionUseCase = ingestionUseCase;
        this.reportUseCase = reportUseCase;
    }

    @GetMapping
    public String recommendationsPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "period", defaultValue = "MONTHLY") String period,
            Model model
    ) {
        PeriodWindow pw = PeriodWindow.resolve(month, period);
        String selectedMonth = pw.yearMonth().toString();

        model.addAttribute("user", principal);
        model.addAttribute("selectedMonth", selectedMonth);
        model.addAttribute("selectedPeriod", pw.scopeCode());
        model.addAttribute("selectedPeriodLabel", pw.label());
        model.addAttribute("selectedMonthLabel", pw.spanishTitle());
        model.addAttribute("nextMonthLabel", pw.nextSpanishTitle());
        model.addAttribute("todayIso", LocalDate.now().toString());
        model.addAttribute("monthOptions", buildMonthOptions());
        model.addAttribute("geminiConfigured", reportUseCase.isAiAdvisorConfigured());
        model.addAttribute("geminiModel", reportUseCase.getAiAdvisorModelName());

        List<DocumentStatusView> pendingConfirmations = ingestionUseCase
                .listUserDocuments(principal.userId()).stream()
                .filter(d -> d.document().getStatus() == DocumentStatus.AWAITING_CONFIRMATION)
                .toList();
        model.addAttribute("pendingConfirmations", pendingConfirmations);

        profileUseCase.getCurrentProfileAndBudget(principal.userId())
                .ifPresent(pb -> model.addAttribute("profile", pb.profile()));

        MonthAnalysisSnapshot snapshot = reportUseCase.buildMonthSnapshot(principal.userId(), pw.label());
        model.addAttribute("snapshot", snapshot);

        return "dashboard/recommendations";
    }

    @PostMapping("/analyze")
    public String analyzeSelectedMonth(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("month") String month,
            @RequestParam(value = "period", defaultValue = "MONTHLY") String period,
            RedirectAttributes redirectAttributes
    ) {
        try {
            PeriodWindow pw = PeriodWindow.resolve(month, period);
            MonthAnalysisSnapshot snapshot = reportUseCase.buildMonthSnapshot(principal.userId(), pw.label());
            NarrativeOutput summary = reportUseCase.generateMonthSummaryNarrative(snapshot);

            redirectAttributes.addFlashAttribute("summaryNarrative", summary);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Resumen organizado de " + pw.spanishTitle() + " generado correctamente.");
            return "redirect:/recommendations?month=" + pw.yearMonth() + "&period=" + pw.scopeCode();
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/recommendations";
        }
    }

    @PostMapping("/recommend")
    public String recommendNextMonth(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("month") String month,
            @RequestParam(value = "period", defaultValue = "MONTHLY") String period,
            RedirectAttributes redirectAttributes
    ) {
        try {
            PeriodWindow pw = PeriodWindow.resolve(month, period);
            PeriodClosureReportView closure = reportUseCase.generatePeriodReportAndNextPlan(principal.userId(), pw.label());
            MonthAnalysisSnapshot snapshot = reportUseCase.buildMonthSnapshot(principal.userId(), pw.label());
            NextMonthAllocation allocation = reportUseCase.computeNextMonthAllocation(snapshot, closure.suggestedNextPlan());
            NarrativeOutput recommendation = reportUseCase.generateNextMonthNarrative(snapshot, allocation);

            redirectAttributes.addFlashAttribute("recommendationNarrative", recommendation);
            redirectAttributes.addFlashAttribute("nextAllocation", allocation);
            redirectAttributes.addFlashAttribute("closureView", closure);
            redirectAttributes.addFlashAttribute("calculatedPlan", closure.calculatedPlan());
            redirectAttributes.addFlashAttribute("suggestedBudget", closure.suggestedNextPlan());
            redirectAttributes.addFlashAttribute("savedReport", closure.report());
            redirectAttributes.addFlashAttribute("successMessage",
                    "Recomendación y plan sugerido para " + pw.nextSpanishTitle() + " listos.");
            return "redirect:/recommendations?month=" + pw.yearMonth() + "&period=" + pw.scopeCode();
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/recommendations";
        }
    }

    @PostMapping("/plan-decision")
    public String updateSuggestedPlanDecision(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("budgetId") UUID budgetId,
            @RequestParam("month") String month,
            @RequestParam(value = "period", defaultValue = "MONTHLY") String period,
            @RequestParam("status") String status,
            @RequestParam(value = "needsPct", required = false) Integer needsPct,
            @RequestParam(value = "wantsPct", required = false) Integer wantsPct,
            @RequestParam(value = "savingsPct", required = false) Integer savingsPct,
            RedirectAttributes redirectAttributes
    ) {
        PeriodWindow pw = PeriodWindow.resolve(month, period);
        try {
            Budget updated = reportUseCase.updateSuggestedPlanDecision(
                    principal.userId(),
                    budgetId,
                    new PlanDecisionRequest(status, null, needsPct, wantsPct, savingsPct)
            );
            String label = switch (updated.getStatus()) {
                case "ACCEPTED" -> "Plan aceptado para el próximo periodo.";
                case "EDITED" -> "Porcentajes del plan actualizados y guardados.";
                case "IGNORED" -> "Plan marcado como ignorado; tus datos históricos se mantienen intactos.";
                default -> "Decisión registrada.";
            };
            redirectAttributes.addFlashAttribute("successMessage", label);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/recommendations?month=" + pw.yearMonth() + "&period=" + pw.scopeCode();
    }

    private String formatMonthLabel(YearMonth ym) {
        String name = ym.getMonth().getDisplayName(TextStyle.FULL, ES_CO);
        if (name == null || name.isBlank()) {
            return ym.toString();
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + ym.getYear();
    }

    private List<MonthOption> buildMonthOptions() {
        List<MonthOption> options = new ArrayList<>();
        YearMonth cursor = YearMonth.now();
        for (int i = 0; i < 18; i++) {
            YearMonth ym = cursor.minusMonths(i);
            options.add(new MonthOption(ym.toString(), formatMonthLabel(ym)));
        }
        return options;
    }

    public record MonthOption(String value, String label) {
    }
}
