package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.Transaction;
import co.cuentaclara.domain.port.out.TransactionRepositoryPort;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TransactionPersistenceAdapter implements TransactionRepositoryPort {

    private final TransactionSpringDataRepository repository;

    public TransactionPersistenceAdapter(TransactionSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return repository.findById(id).map(TransactionPersistenceAdapter::toDomain);
    }

    @Override
    public List<Transaction> findByUserIdOrderByTransactionDateDescCreatedAtDesc(UUID userId) {
        return repository.findByUserIdOrderByTransactionDateDescCreatedAtDesc(userId).stream()
                .map(TransactionPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<Transaction> findByUserIdAndTransactionDateBetweenOrderByTransactionDateDesc(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return repository.findByUserIdAndTransactionDateBetweenOrderByTransactionDateDesc(userId, startDate, endDate).stream()
                .map(TransactionPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Transaction save(Transaction transaction) {
        TransactionJpaEntity saved = repository.saveAndFlush(toEntity(transaction));
        return toDomain(saved);
    }

    public static Transaction toDomain(TransactionJpaEntity entity) {
        Transaction domain = new Transaction();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setDocumentId(entity.getDocumentId());
        domain.setSourceType(entity.getSourceType());
        domain.setMerchantName(entity.getMerchantName());
        domain.setMerchantNit(entity.getMerchantNit());
        domain.setInvoiceNumber(entity.getInvoiceNumber());
        domain.setCufe(entity.getCufe());
        domain.setTransactionDate(entity.getTransactionDate());
        domain.setSubtotalAmount(entity.getSubtotalAmount());
        domain.setTaxAmount(entity.getTaxAmount());
        domain.setTotalAmount(entity.getTotalAmount());
        domain.setCategory(entity.getCategory());
        domain.setBudgetBlock(entity.getBudgetBlock());
        domain.setDescription(entity.getDescription());
        domain.setItemsSummary(entity.getItemsSummary());
        domain.setCitationReference(entity.getCitationReference());
        domain.setConfirmed(entity.isConfirmed());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }

    static TransactionJpaEntity toEntity(Transaction domain) {
        TransactionJpaEntity entity = new TransactionJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setDocumentId(domain.getDocumentId());
        entity.setSourceType(domain.getSourceType());
        entity.setMerchantName(domain.getMerchantName());
        entity.setMerchantNit(domain.getMerchantNit());
        entity.setInvoiceNumber(domain.getInvoiceNumber());
        entity.setCufe(domain.getCufe());
        entity.setTransactionDate(domain.getTransactionDate());
        entity.setSubtotalAmount(domain.getSubtotalAmount());
        entity.setTaxAmount(domain.getTaxAmount());
        entity.setTotalAmount(domain.getTotalAmount());
        entity.setCategory(domain.getCategory());
        entity.setBudgetBlock(domain.getBudgetBlock());
        entity.setDescription(domain.getDescription());
        entity.setItemsSummary(domain.getItemsSummary());
        entity.setCitationReference(domain.getCitationReference());
        entity.setConfirmed(domain.isConfirmed());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }
}
