package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.RecurringObligation;
import co.cuentaclara.domain.port.out.RecurringObligationRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RecurringObligationPersistenceAdapter implements RecurringObligationRepositoryPort {

    private final RecurringObligationSpringDataRepository repository;

    public RecurringObligationPersistenceAdapter(RecurringObligationSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<RecurringObligation> findByUserIdOrderByDueDayAsc(UUID userId) {
        return repository.findByUserIdOrderByDueDayAsc(userId).stream()
                .map(RecurringObligationPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<RecurringObligation> findById(UUID id) {
        return repository.findById(id).map(RecurringObligationPersistenceAdapter::toDomain);
    }

    @Override
    public RecurringObligation save(RecurringObligation obligation) {
        RecurringObligationJpaEntity saved = repository.saveAndFlush(toEntity(obligation));
        return toDomain(saved);
    }

    @Override
    public void delete(RecurringObligation obligation) {
        if (obligation.getId() != null) {
            repository.deleteById(obligation.getId());
            repository.flush();
        }
    }

    @Override
    public void deleteByUserId(UUID userId) {
        repository.deleteByUserId(userId);
        repository.flush();
    }

    static RecurringObligation toDomain(RecurringObligationJpaEntity entity) {
        RecurringObligation domain = new RecurringObligation();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setName(entity.getName());
        domain.setCategory(entity.getCategory());
        domain.setBudgetBlock(entity.getBudgetBlock());
        domain.setEstimatedAmount(entity.getEstimatedAmount());
        domain.setDueDay(entity.getDueDay());
        domain.setEssential(entity.isEssential());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }

    static RecurringObligationJpaEntity toEntity(RecurringObligation domain) {
        RecurringObligationJpaEntity entity = new RecurringObligationJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setName(domain.getName());
        entity.setCategory(domain.getCategory());
        entity.setBudgetBlock(domain.getBudgetBlock());
        entity.setEstimatedAmount(domain.getEstimatedAmount());
        entity.setDueDay(domain.getDueDay());
        entity.setEssential(domain.isEssential());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }
}
