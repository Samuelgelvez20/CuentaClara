package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.MonthlyReport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonthlyReportRepositoryPort {

    List<MonthlyReport> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<MonthlyReport> findFirstByUserIdAndPeriodLabelOrderByCreatedAtDesc(UUID userId, String periodLabel);

    MonthlyReport save(MonthlyReport report);
}
