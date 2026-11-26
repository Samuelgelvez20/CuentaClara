package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.BackedRecommendation;
import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.model.PersistedPlanView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetPlanPersistencePort {

    PersistedPlanView calculateAndPersistPlan(
            UUID userId,
            YearMonth targetMonth,
            LocalDate sourceStart,
            LocalDate sourceEnd,
            Budget nextBudget,
            List<BackedRecommendation> recommendations
    );

    void syncPlanDecision(UUID userId, Budget updatedBudget);

    Optional<PersistedPlanView> findPlanForTargetMonth(UUID userId, YearMonth targetMonth);
}
