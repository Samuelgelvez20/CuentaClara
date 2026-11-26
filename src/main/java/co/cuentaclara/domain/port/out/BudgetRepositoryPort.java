package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.Budget;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepositoryPort {

    Optional<Budget> findById(UUID id);

    Optional<Budget> findFirstByUserIdAndBudgetStageOrderByCreatedAtDesc(UUID userId, String budgetStage);

    List<Budget> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Budget save(Budget budget);
}
