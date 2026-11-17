package co.cuentaclara.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    Optional<Budget> findFirstByUserIdAndBudgetStageOrderByCreatedAtDesc(UUID userId, String budgetStage);

    List<Budget> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
