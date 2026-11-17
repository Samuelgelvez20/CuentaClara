package co.cuentaclara.profile;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringObligationRepository extends JpaRepository<RecurringObligation, UUID> {

    List<RecurringObligation> findByUserIdOrderByDueDayAsc(UUID userId);

    void deleteByUserId(UUID userId);
}
