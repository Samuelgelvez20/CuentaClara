package co.cuentaclara.infrastructure.adapter.in.rest;

import co.cuentaclara.domain.model.ObligationInput;
import co.cuentaclara.domain.model.PaymentFrequency;
import co.cuentaclara.domain.model.ProfileWithInitialBudget;
import co.cuentaclara.domain.model.QuestionnaireCommand;
import co.cuentaclara.domain.port.in.FinancialProfileUseCase;
import co.cuentaclara.infrastructure.security.jwt.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class FinancialProfileController {

    private final FinancialProfileUseCase financialProfileUseCase;

    public FinancialProfileController(FinancialProfileUseCase financialProfileUseCase) {
        this.financialProfileUseCase = financialProfileUseCase;
    }

    @GetMapping
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return financialProfileUseCase.getCurrentProfileAndBudget(principal.userId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "questionnaireCompleted", false,
                        "message", "Aún no has completado tu cuestionario financiero inicial."
                )));
    }

    @PostMapping
    public ResponseEntity<?> saveProfile(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody QuestionnaireRequest request
    ) {
        try {
            ProfileWithInitialBudget result = financialProfileUseCase.saveOrUpdateQuestionnaire(
                    principal.userId(),
                    new QuestionnaireCommand(
                            request.netIncome(),
                            request.paymentFrequency(),
                            request.paymentDays(),
                            request.needsPct(),
                            request.wantsPct(),
                            request.savingsPct(),
                            request.financialGoals(),
                            request.totalDebts(),
                            request.obligations()
                    )
            );
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    public record QuestionnaireRequest(
            @NotNull(message = "El ingreso neto estimado es obligatorio")
            @DecimalMin(value = "0.0", inclusive = true, message = "El ingreso no puede ser negativo")
            BigDecimal netIncome,

            @NotNull(message = "La frecuencia de pago es obligatoria")
            PaymentFrequency paymentFrequency,

            @NotBlank(message = "Indica tus días habituales de pago (ej. 15,30 o 30)")
            String paymentDays,

            @Min(0) @Max(100)
            int needsPct,

            @Min(0) @Max(100)
            int wantsPct,

            @Min(0) @Max(100)
            int savingsPct,

            String financialGoals,

            BigDecimal totalDebts,

            @Valid
            List<ObligationInput> obligations
    ) {
    }
}
