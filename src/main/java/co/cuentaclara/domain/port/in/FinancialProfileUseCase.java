package co.cuentaclara.domain.port.in;

import co.cuentaclara.domain.model.ObligationInput;
import co.cuentaclara.domain.model.ProfileWithInitialBudget;
import co.cuentaclara.domain.model.QuestionnaireCommand;
import co.cuentaclara.domain.model.RecurringObligation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialProfileUseCase {

    ProfileWithInitialBudget saveOrUpdateQuestionnaire(UUID userId, QuestionnaireCommand request);

    Optional<ProfileWithInitialBudget> getCurrentProfileAndBudget(UUID userId);

    List<RecurringObligation> listObligations(UUID userId);

    RecurringObligation addRecurringObligation(UUID userId, ObligationInput item);

    void deleteRecurringObligation(UUID userId, UUID obligationId);
}
