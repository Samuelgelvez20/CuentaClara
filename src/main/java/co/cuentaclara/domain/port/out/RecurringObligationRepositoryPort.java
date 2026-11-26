package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.RecurringObligation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringObligationRepositoryPort {

    List<RecurringObligation> findByUserIdOrderByDueDayAsc(UUID userId);

    Optional<RecurringObligation> findById(UUID id);

    RecurringObligation save(RecurringObligation obligation);

    void delete(RecurringObligation obligation);

    void deleteByUserId(UUID userId);
}
