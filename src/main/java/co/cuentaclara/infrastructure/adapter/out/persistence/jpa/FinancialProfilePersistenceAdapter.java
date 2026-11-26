package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.FinancialProfile;
import co.cuentaclara.domain.port.out.FinancialProfileRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FinancialProfilePersistenceAdapter implements FinancialProfileRepositoryPort {

    private final FinancialProfileSpringDataRepository repository;

    public FinancialProfilePersistenceAdapter(FinancialProfileSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<FinancialProfile> findByUserId(UUID userId) {
        return repository.findByUserId(userId).map(FinancialProfilePersistenceAdapter::toDomain);
    }

    @Override
    public FinancialProfile save(FinancialProfile profile) {
        FinancialProfileJpaEntity saved = repository.saveAndFlush(toEntity(profile));
        return toDomain(saved);
    }

    static FinancialProfile toDomain(FinancialProfileJpaEntity entity) {
        FinancialProfile domain = new FinancialProfile();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setNetIncome(entity.getNetIncome());
        domain.setPaymentFrequency(entity.getPaymentFrequency());
        domain.setPaymentDays(entity.getPaymentDays());
        domain.setNeedsPct(entity.getNeedsPct());
        domain.setWantsPct(entity.getWantsPct());
        domain.setSavingsPct(entity.getSavingsPct());
        domain.setNeedsCutPercent(entity.getNeedsCutPercent());
        domain.setFinancialGoals(entity.getFinancialGoals());
        domain.setTotalDebts(entity.getTotalDebts());
        domain.setQuestionnaireCompleted(entity.isQuestionnaireCompleted());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    static FinancialProfileJpaEntity toEntity(FinancialProfile domain) {
        FinancialProfileJpaEntity entity = new FinancialProfileJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setNetIncome(domain.getNetIncome());
        entity.setPaymentFrequency(domain.getPaymentFrequency());
        entity.setPaymentDays(domain.getPaymentDays());
        entity.setNeedsPct(domain.getNeedsPct());
        entity.setWantsPct(domain.getWantsPct());
        entity.setSavingsPct(domain.getSavingsPct());
        entity.setNeedsCutPercent(domain.getNeedsCutPercent());
        entity.setFinancialGoals(domain.getFinancialGoals());
        entity.setTotalDebts(domain.getTotalDebts());
        entity.setQuestionnaireCompleted(domain.isQuestionnaireCompleted());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
