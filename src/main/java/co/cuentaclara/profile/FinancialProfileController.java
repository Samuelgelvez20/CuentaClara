package co.cuentaclara.profile;

import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
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

    private final FinancialProfileService profileService;

    public FinancialProfileController(FinancialProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return profileService.getCurrentProfileAndBudget(principal.userId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "questionnaireCompleted", false,
                        "message", "Aún no has completado tu cuestionario financiero inicial."
                )));
    }

    @PostMapping
    public ResponseEntity<?> saveProfile(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody FinancialProfileService.QuestionnaireRequest request
    ) {
        try {
            FinancialProfileService.ProfileWithInitialBudget result =
                    profileService.saveOrUpdateQuestionnaire(principal.userId(), request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
