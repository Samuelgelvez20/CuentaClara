package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyReportSpringDataRepository extends JpaRepository<MonthlyReportJpaEntity, UUID> {

    List<MonthlyReportJpaEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<MonthlyReportJpaEntity> findFirstByUserIdAndPeriodLabelOrderByCreatedAtDesc(UUID userId, String periodLabel);
}
