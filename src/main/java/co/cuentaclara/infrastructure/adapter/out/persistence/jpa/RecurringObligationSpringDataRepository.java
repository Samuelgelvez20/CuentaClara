package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringObligationSpringDataRepository extends JpaRepository<RecurringObligationJpaEntity, UUID> {

    List<RecurringObligationJpaEntity> findByUserIdOrderByDueDayAsc(UUID userId);

    void deleteByUserId(UUID userId);
}
