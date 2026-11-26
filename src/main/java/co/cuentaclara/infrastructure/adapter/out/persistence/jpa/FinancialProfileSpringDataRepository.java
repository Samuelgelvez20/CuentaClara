package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialProfileSpringDataRepository extends JpaRepository<FinancialProfileJpaEntity, UUID> {

    Optional<FinancialProfileJpaEntity> findByUserId(UUID userId);
}
