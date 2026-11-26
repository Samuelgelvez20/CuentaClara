package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetSpringDataRepository extends JpaRepository<BudgetJpaEntity, UUID> {

    Optional<BudgetJpaEntity> findFirstByUserIdAndBudgetStageOrderByCreatedAtDesc(UUID userId, String budgetStage);

    List<BudgetJpaEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
