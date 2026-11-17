package co.cuentaclara.web;

import co.cuentaclara.auth.AuthController;
import co.cuentaclara.auth.AuthService;
import co.cuentaclara.profile.BudgetBlock;
import co.cuentaclara.profile.FinancialProfileService;
import co.cuentaclara.profile.PaymentFrequency;
import co.cuentaclara.report.MonthlyAnalysisAndReportService;
import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import co.cuentaclara.security.jwt.JwtTokenService;
import co.cuentaclara.security.rls.RlsDataSourceConfig;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebOnboardingController {

    private final AuthService authService;
    private final JwtTokenService jwtTokenService;
    private final FinancialProfileService profileService;
    private final MonthlyAnalysisAndReportService reportService;

    public WebOnboardingController(
            AuthService authService,
            JwtTokenService jwtTokenService,
            FinancialProfileService profileService,
            MonthlyAnalysisAndReportService reportService
    ) {
        this.authService = authService;
        this.jwtTokenService = jwtTokenService;
        this.profileService = profileService;
        this.reportService = reportService;
    }

    @GetMapping("/")
    public String index(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        if (principal == null) {
            return "redirect:/auth/login";
        }
        return "redirect:/dashboard";
    }

    @GetMapping("/auth/login")
    public String loginPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "mode", required = false) String mode,
            Model model
    ) {
        if (principal != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("registerMode", "register".equalsIgnoreCase(mode));
        return "auth/login";
    }

    @GetMapping("/auth/register")
    public String registerPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            Model model
    ) {
        if (principal != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("registerMode", true);
        return "auth/login";
    }

    @PostMapping("/auth/demo")
    public String handleDemoLogin(
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        String demoEmail = "demo.profesional@cuentaclara.co";
        String demoPass = "UBL2.1-Demo-2026";
        AuthService.AuthResponse auth;
        try {
            auth = authService.login(new AuthService.LoginRequest(demoEmail, demoPass));
        } catch (Exception ex) {
            auth = authService.register(new AuthService.RegisterRequest(
                    "Camila Morales (Demo)",
                    demoEmail,
                    demoPass,
                    true
            ));
        }
        AuthController.attachAuthCookie(response, auth.token(), jwtTokenService.getExpirationSeconds());
        final var userId = auth.userId();
        RlsDataSourceConfig.runAsUser(userId, () -> {
            reportService.simulateFirstMonthAndGenerateReport(userId, "2026-10");
        });
        redirectAttributes.addFlashAttribute(
                "successMessage",
                "¡Entorno Demo activado! Se cargó el primer mes simulado (2026-10) con movimientos e informe SQL listos."
        );
        return "redirect:/dashboard";
    }

    @PostMapping("/auth/register")
    public String handleRegisterForm(
            @RequestParam String fullName,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(defaultValue = "false") boolean aiConsentAccepted,
            @RequestParam(required = false) BigDecimal initialNetIncome,
            @RequestParam(value = "obligationName", required = false) List<String> obligationNames,
            @RequestParam(value = "obligationAmount", required = false) List<BigDecimal> obligationAmounts,
            @RequestParam(value = "obligationDueDay", required = false) List<Integer> obligationDueDays,
            @RequestParam(value = "obligationCategory", required = false) List<String> obligationCategories,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        try {
            AuthService.AuthResponse auth = authService.register(
                    new AuthService.RegisterRequest(fullName, email, password, aiConsentAccepted)
            );
            AuthController.attachAuthCookie(response, auth.token(), jwtTokenService.getExpirationSeconds());

            // Si al crear la cuenta indicó sus ingresos o deudas/pagos mensuales con su día de pago, guardarlos de inmediato
            final var userId = auth.userId();
            RlsDataSourceConfig.runAsUser(userId, () -> {
                List<FinancialProfileService.ObligationInput> obList = buildDynamicObligations(
                        obligationNames, obligationAmounts, obligationDueDays, obligationCategories
                );
                BigDecimal income = (initialNetIncome != null && initialNetIncome.compareTo(BigDecimal.ZERO) > 0)
                        ? initialNetIncome
                        : new BigDecimal("3500000.00");
                if (initialNetIncome != null || !obList.isEmpty()) {
                    profileService.saveOrUpdateQuestionnaire(
                            userId,
                            new FinancialProfileService.QuestionnaireRequest(
                                    income,
                                    PaymentFrequency.MONTHLY,
                                    "30",
                                    50, 30, 20,
                                    "Organizar mis gastos y pagar mis compromisos a tiempo",
                                    BigDecimal.ZERO,
                                    obList
                            )
                    );
                }
            });

            if (obligationNames != null && !obligationNames.isEmpty()) {
                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "¡Bienvenido(a)! Tus pagos y deudas mensuales quedaron marcados automáticamente en tu calendario cada mes."
                );
                return "redirect:/dashboard";
            }
            return "redirect:/profile";
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/auth/register";
        }
    }

    @PostMapping("/auth/login")
    public String handleLoginForm(
            @RequestParam String email,
            @RequestParam String password,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        try {
            AuthService.AuthResponse auth = authService.login(new AuthService.LoginRequest(email, password));
            AuthController.attachAuthCookie(response, auth.token(), jwtTokenService.getExpirationSeconds());
            return "redirect:/dashboard";
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/auth/login";
        }
    }

    @PostMapping("/auth/logout")
    public String handleLogout(HttpServletResponse response) {
        AuthController.attachAuthCookie(response, "", 0);
        return "redirect:/auth/login";
    }

    @GetMapping("/profile")
    public String profilePage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            Model model
    ) {
        model.addAttribute("user", principal);
        model.addAttribute("frequencies", PaymentFrequency.values());
        profileService.getCurrentProfileAndBudget(principal.userId())
                .ifPresent(data -> {
                    model.addAttribute("profileData", data);
                    model.addAttribute("profile", data.profile());
                    model.addAttribute("obligations", data.obligations());
                    model.addAttribute("initialBudget", data.initialBudget());
                    model.addAttribute("recommendations", data.preliminaryRecommendations());
                });
        return "profile/questionnaire";
    }

    @PostMapping("/profile")
    public String handleProfileForm(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam BigDecimal netIncome,
            @RequestParam PaymentFrequency paymentFrequency,
            @RequestParam(defaultValue = "30") String paymentDays,
            @RequestParam(defaultValue = "50") int needsPct,
            @RequestParam(defaultValue = "30") int wantsPct,
            @RequestParam(defaultValue = "20") int savingsPct,
            @RequestParam(required = false) String financialGoals,
            @RequestParam(required = false) BigDecimal totalDebts,
            @RequestParam(required = false) BigDecimal housingAmount,
            @RequestParam(defaultValue = "5") Integer housingDueDay,
            @RequestParam(required = false) BigDecimal utilitiesAmount,
            @RequestParam(defaultValue = "18") Integer utilitiesDueDay,
            @RequestParam(required = false) BigDecimal foodAmount,
            @RequestParam(defaultValue = "15") Integer foodDueDay,
            @RequestParam(value = "obligationName", required = false) List<String> obligationNames,
            @RequestParam(value = "obligationAmount", required = false) List<BigDecimal> obligationAmounts,
            @RequestParam(value = "obligationDueDay", required = false) List<Integer> obligationDueDays,
            @RequestParam(value = "obligationCategory", required = false) List<String> obligationCategories,
            RedirectAttributes redirectAttributes
    ) {
        try {
            List<FinancialProfileService.ObligationInput> obligations = new ArrayList<>();
            if (housingAmount != null && housingAmount.compareTo(BigDecimal.ZERO) > 0) {
                obligations.add(new FinancialProfileService.ObligationInput(
                        "Vivienda / Arriendo", "Vivienda", BudgetBlock.NEEDS, housingAmount, housingDueDay, true
                ));
            }
            if (utilitiesAmount != null && utilitiesAmount.compareTo(BigDecimal.ZERO) > 0) {
                obligations.add(new FinancialProfileService.ObligationInput(
                        "Servicios Públicos / Internet", "Servicios", BudgetBlock.NEEDS, utilitiesAmount, utilitiesDueDay, true
                ));
            }
            if (foodAmount != null && foodAmount.compareTo(BigDecimal.ZERO) > 0) {
                obligations.add(new FinancialProfileService.ObligationInput(
                        "Alimentación prioritaria", "Alimentación", BudgetBlock.NEEDS, foodAmount, foodDueDay, true
                ));
            }
            obligations.addAll(buildDynamicObligations(
                    obligationNames, obligationAmounts, obligationDueDays, obligationCategories
            ));

            profileService.saveOrUpdateQuestionnaire(
                    principal.userId(),
                    new FinancialProfileService.QuestionnaireRequest(
                            netIncome,
                            paymentFrequency,
                            paymentDays,
                            needsPct,
                            wantsPct,
                            savingsPct,
                            financialGoals,
                            totalDebts,
                            obligations
                    )
            );
            redirectAttributes.addFlashAttribute("successMessage",
                    "Perfil financiero y fechas de tus pagos/deudas mensuales actualizados en tu calendario.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/profile";
    }

    private List<FinancialProfileService.ObligationInput> buildDynamicObligations(
            List<String> names,
            List<BigDecimal> amounts,
            List<Integer> dueDays,
            List<String> categories
    ) {
        List<FinancialProfileService.ObligationInput> result = new ArrayList<>();
        if (names == null || amounts == null) {
            return result;
        }
        int count = Math.min(names.size(), amounts.size());
        for (int i = 0; i < count; i++) {
            String name = names.get(i);
            BigDecimal amount = amounts.get(i);
            if (name != null && !name.isBlank() && amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                int day = (dueDays != null && i < dueDays.size() && dueDays.get(i) != null) ? dueDays.get(i) : 5;
                String cat = (categories != null && i < categories.size() && categories.get(i) != null && !categories.get(i).isBlank())
                        ? categories.get(i)
                        : "Deudas y Préstamos";
                result.add(new FinancialProfileService.ObligationInput(
                        name.trim(), cat.trim(), BudgetBlock.NEEDS, amount, day, true
                ));
            }
        }
        return result;
    }
}
