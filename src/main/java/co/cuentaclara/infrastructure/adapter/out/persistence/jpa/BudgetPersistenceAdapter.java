package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.port.out.BudgetRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class BudgetPersistenceAdapter implements BudgetRepositoryPort {

    private final BudgetSpringDataRepository repository;

    public BudgetPersistenceAdapter(BudgetSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Budget> findById(UUID id) {
        return repository.findById(id).map(BudgetPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Budget> findFirstByUserIdAndBudgetStageOrderByCreatedAtDesc(UUID userId, String budgetStage) {
        return repository.findFirstByUserIdAndBudgetStageOrderByCreatedAtDesc(userId, budgetStage)
                .map(BudgetPersistenceAdapter::toDomain);
    }

    @Override
    public List<Budget> findByUserIdOrderByCreatedAtDesc(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(BudgetPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Budget save(Budget budget) {
        BudgetJpaEntity saved = repository.saveAndFlush(toEntity(budget));
        return toDomain(saved);
    }

    static Budget toDomain(BudgetJpaEntity entity) {
        Budget domain = new Budget();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setPeriodLabel(entity.getPeriodLabel());
        domain.setPeriodStart(entity.getPeriodStart());
        domain.setPeriodEnd(entity.getPeriodEnd());
        domain.setBudgetStage(entity.getBudgetStage());
        domain.setStatus(entity.getStatus());
        domain.setReferenceIncome(entity.getReferenceIncome());
        domain.setNeedsPct(entity.getNeedsPct());
        domain.setWantsPct(entity.getWantsPct());
        domain.setSavingsPct(entity.getSavingsPct());
        domain.setNeedsLimit(entity.getNeedsLimit());
        domain.setWantsLimit(entity.getWantsLimit());
        domain.setSavingsTarget(entity.getSavingsTarget());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }

    static BudgetJpaEntity toEntity(Budget domain) {
        BudgetJpaEntity entity = new BudgetJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setPeriodLabel(domain.getPeriodLabel());
        entity.setPeriodStart(domain.getPeriodStart());
        entity.setPeriodEnd(domain.getPeriodEnd());
        entity.setBudgetStage(domain.getBudgetStage());
        entity.setStatus(domain.getStatus());
        entity.setReferenceIncome(domain.getReferenceIncome());
        entity.setNeedsPct(domain.getNeedsPct());
        entity.setWantsPct(domain.getWantsPct());
        entity.setSavingsPct(domain.getSavingsPct());
        entity.setNeedsLimit(domain.getNeedsLimit());
        entity.setWantsLimit(domain.getWantsLimit());
        entity.setSavingsTarget(domain.getSavingsTarget());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }
}
