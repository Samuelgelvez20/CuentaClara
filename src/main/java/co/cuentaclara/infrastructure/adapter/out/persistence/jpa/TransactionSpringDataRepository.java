package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionSpringDataRepository extends JpaRepository<TransactionJpaEntity, UUID> {

    List<TransactionJpaEntity> findByUserIdOrderByTransactionDateDescCreatedAtDesc(UUID userId);

    List<TransactionJpaEntity> findByUserIdAndTransactionDateBetweenOrderByTransactionDateDesc(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate
    );
}
