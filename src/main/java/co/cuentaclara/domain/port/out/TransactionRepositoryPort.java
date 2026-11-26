package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.Transaction;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepositoryPort {

    Optional<Transaction> findById(UUID id);

    List<Transaction> findByUserIdOrderByTransactionDateDescCreatedAtDesc(UUID userId);

    List<Transaction> findByUserIdAndTransactionDateBetweenOrderByTransactionDateDesc(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate
    );

    Transaction save(Transaction transaction);
}
