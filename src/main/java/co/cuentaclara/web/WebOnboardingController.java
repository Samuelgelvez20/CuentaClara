package co.cuentaclara.web;

import co.cuentaclara.auth.AuthController;
import co.cuentaclara.auth.AuthService;
import co.cuentaclara.profile.BudgetBlock;
import co.cuentaclara.profile.FinancialProfileService;
import co.cuentaclara.profile.PaymentFrequency;
import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import co.cuentaclara.security.jwt.JwtTokenService;
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

    public WebOnboardingController(
            AuthService authService,
            JwtTokenService jwtTokenService,
            FinancialProfileService profileService
    ) {
        this.authService = authService;
        this.jwtTokenService = jwtTokenService;
        this.profileService = profileService;
    }

    @GetMapping("/")
    public String index(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        if (principal == null) {
            return "redirect:/auth/login";
        }
        return "redirect:/profile";
    }

    @GetMapping("/auth/login")
    public String loginPage(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        if (principal != null) {
            return "redirect:/profile";
        }
        return "auth/login";
    }

    @GetMapping("/auth/register")
    public String registerPage(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        if (principal != null) {
            return "redirect:/profile";
        }
        return "auth/register";
    }

    @PostMapping("/auth/register")
    public String handleRegisterForm(
            @RequestParam String fullName,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(defaultValue = "false") boolean aiConsentAccepted,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        try {
            AuthService.AuthResponse auth = authService.register(
                    new AuthService.RegisterRequest(fullName, email, password, aiConsentAccepted)
            );
            AuthController.attachAuthCookie(response, auth.token(), jwtTokenService.getExpirationSeconds());
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
            return "redirect:/profile";
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
            redirectAttributes.addFlashAttribute("successMessage", "Perfil financiero y presupuesto inicial estimado actualizados.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/profile";
    }
}
