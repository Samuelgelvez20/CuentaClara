package co.cuentaclara.report;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyReportRepository extends JpaRepository<MonthlyReportEntity, UUID> {

    List<MonthlyReportEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<MonthlyReportEntity> findFirstByUserIdAndPeriodLabelOrderByCreatedAtDesc(UUID userId, String periodLabel);
}
