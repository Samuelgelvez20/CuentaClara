package co.cuentaclara.web;

import co.cuentaclara.document.DocumentIngestionService;
import co.cuentaclara.document.DocumentStatus;
import co.cuentaclara.profile.Budget;
import co.cuentaclara.profile.FinancialProfileService;
import co.cuentaclara.report.GeminiFinancialAdvisorService;
import co.cuentaclara.report.MonthlyAnalysisAndReportService;
import co.cuentaclara.report.MonthlyReportEntity;
import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private final FinancialProfileService profileService;
    private final DocumentIngestionService ingestionService;
    private final MonthlyAnalysisAndReportService reportService;
    private final GeminiFinancialAdvisorService geminiAdvisor;

    public WebRecommendationsController(
            FinancialProfileService profileService,
            DocumentIngestionService ingestionService,
            MonthlyAnalysisAndReportService reportService,
            GeminiFinancialAdvisorService geminiAdvisor
    ) {
        this.profileService = profileService;
        this.ingestionService = ingestionService;
        this.reportService = reportService;
        this.geminiAdvisor = geminiAdvisor;
    }

    @GetMapping
    public String recommendationsPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            Model model
    ) {
        YearMonth selectedYm = parseOrDefaultPreviousMonth(month);
        String selectedMonth = selectedYm.toString();

        model.addAttribute("user", principal);
        model.addAttribute("selectedMonth", selectedMonth);
        model.addAttribute("selectedMonthLabel", formatMonthLabel(selectedYm));
        model.addAttribute("nextMonthLabel", formatMonthLabel(selectedYm.plusMonths(1)));
        model.addAttribute("monthOptions", buildMonthOptions());
        model.addAttribute("geminiConfigured", geminiAdvisor.isGeminiConfigured());
        model.addAttribute("geminiModel", geminiAdvisor.getActiveModelName());

        List<DocumentIngestionService.DocumentStatusView> pendingConfirmations = ingestionService
                .listUserDocuments(principal.userId()).stream()
                .filter(d -> d.document().getStatus() == DocumentStatus.AWAITING_CONFIRMATION)
                .toList();
        model.addAttribute("pendingConfirmations", pendingConfirmations);

        profileService.getCurrentProfileAndBudget(principal.userId())
                .ifPresent(pb -> model.addAttribute("profile", pb.profile()));

        GeminiFinancialAdvisorService.MonthAnalysisSnapshot snapshot =
                reportService.buildMonthSnapshot(principal.userId(), selectedMonth);
        model.addAttribute("snapshot", snapshot);

        Optional<MonthlyReportEntity> existingReport =
                reportService.findLatestReportForPeriod(principal.userId(), selectedMonth);
        existingReport.ifPresent(report -> {
            model.addAttribute("savedReport", report);
            reportService.findSuggestedBudgetById(report.getSuggestedBudgetId()).ifPresent(budget -> {
                model.addAttribute("suggestedBudget", budget);
                if (!model.containsAttribute("nextAllocation")) {
                    model.addAttribute("nextAllocation", reportService.computeNextMonthAllocation(snapshot, budget));
                }
            });
        });

        return "dashboard/recommendations";
    }

    @PostMapping("/analyze")
    public String analyzeSelectedMonth(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("month") String month,
            RedirectAttributes redirectAttributes
    ) {
        try {
            YearMonth ym = parseOrDefaultPreviousMonth(month);
            GeminiFinancialAdvisorService.MonthAnalysisSnapshot snapshot =
                    reportService.buildMonthSnapshot(principal.userId(), ym.toString());
            GeminiFinancialAdvisorService.NarrativeOutput summary =
                    geminiAdvisor.generateMonthSummary(snapshot);

            redirectAttributes.addFlashAttribute("summaryNarrative", summary);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Resumen organizado de " + formatMonthLabel(ym) + " generado correctamente.");
            return "redirect:/recommendations?month=" + ym;
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/recommendations";
        }
    }

    @PostMapping("/recommend")
    public String recommendNextMonth(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("month") String month,
            RedirectAttributes redirectAttributes
    ) {
        try {
            YearMonth ym = parseOrDefaultPreviousMonth(month);
            MonthlyAnalysisAndReportService.PeriodClosureReportView closure =
                    reportService.generatePeriodReportAndNextPlan(principal.userId(), ym.toString());
            GeminiFinancialAdvisorService.MonthAnalysisSnapshot snapshot =
                    reportService.buildMonthSnapshot(principal.userId(), ym.toString());
            GeminiFinancialAdvisorService.NextMonthAllocation allocation =
                    reportService.computeNextMonthAllocation(snapshot, closure.suggestedNextPlan());
            GeminiFinancialAdvisorService.NarrativeOutput recommendation =
                    geminiAdvisor.generateNextMonthRecommendation(snapshot, allocation);

            redirectAttributes.addFlashAttribute("recommendationNarrative", recommendation);
            redirectAttributes.addFlashAttribute("nextAllocation", allocation);
            redirectAttributes.addFlashAttribute("closureView", closure);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Recomendación y plan sugerido para " + formatMonthLabel(ym.plusMonths(1)) + " listos.");
            return "redirect:/recommendations?month=" + ym;
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
            @RequestParam("status") String status,
            @RequestParam(value = "needsPct", required = false) Integer needsPct,
            @RequestParam(value = "wantsPct", required = false) Integer wantsPct,
            @RequestParam(value = "savingsPct", required = false) Integer savingsPct,
            RedirectAttributes redirectAttributes
    ) {
        try {
            Budget updated = reportService.updateSuggestedPlanDecision(
                    principal.userId(),
                    budgetId,
                    new MonthlyAnalysisAndReportService.PlanDecisionRequest(
                            status, null, needsPct, wantsPct, savingsPct
                    )
            );
            String label = switch (updated.getStatus()) {
                case "ACCEPTED" -> "Plan aceptado para el próximo mes.";
                case "EDITED" -> "Porcentajes del plan actualizados y guardados.";
                case "IGNORED" -> "Plan marcado como ignorado; tus datos históricos se mantienen intactos.";
                default -> "Decisión registrada.";
            };
            redirectAttributes.addFlashAttribute("successMessage", label);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/recommendations?month=" + parseOrDefaultPreviousMonth(month);
    }

    private YearMonth parseOrDefaultPreviousMonth(String month) {
        if (month != null && !month.isBlank()) {
            try {
                return YearMonth.parse(month.trim());
            } catch (Exception ignored) {
            }
        }
        return YearMonth.now().minusMonths(1);
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
