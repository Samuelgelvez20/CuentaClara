package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.MonthlyReport;
import co.cuentaclara.domain.port.out.MonthlyReportRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MonthlyReportPersistenceAdapter implements MonthlyReportRepositoryPort {

    private final MonthlyReportSpringDataRepository repository;

    public MonthlyReportPersistenceAdapter(MonthlyReportSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<MonthlyReport> findByUserIdOrderByCreatedAtDesc(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(MonthlyReportPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<MonthlyReport> findFirstByUserIdAndPeriodLabelOrderByCreatedAtDesc(UUID userId, String periodLabel) {
        return repository.findFirstByUserIdAndPeriodLabelOrderByCreatedAtDesc(userId, periodLabel)
                .map(MonthlyReportPersistenceAdapter::toDomain);
    }

    @Override
    public MonthlyReport save(MonthlyReport report) {
        MonthlyReportJpaEntity saved = repository.saveAndFlush(toEntity(report));
        return toDomain(saved);
    }

    static MonthlyReport toDomain(MonthlyReportJpaEntity entity) {
        MonthlyReport domain = new MonthlyReport();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setPeriodLabel(entity.getPeriodLabel());
        domain.setPeriodStart(entity.getPeriodStart());
        domain.setPeriodEnd(entity.getPeriodEnd());
        domain.setDeclaredIncome(entity.getDeclaredIncome());
        domain.setActualIncome(entity.getActualIncome());
        domain.setNeedsPct(entity.getNeedsPct());
        domain.setWantsPct(entity.getWantsPct());
        domain.setSavingsPct(entity.getSavingsPct());
        domain.setNeedsSpent(entity.getNeedsSpent());
        domain.setWantsSpent(entity.getWantsSpent());
        domain.setSavingsAchieved(entity.getSavingsAchieved());
        domain.setEssentialExceeded(entity.isEssentialExceeded());
        domain.setVerdictSummary(entity.getVerdictSummary());
        domain.setRecommendationsJson(entity.getRecommendationsJson());
        domain.setSuggestedBudgetId(entity.getSuggestedBudgetId());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }

    static MonthlyReportJpaEntity toEntity(MonthlyReport domain) {
        MonthlyReportJpaEntity entity = new MonthlyReportJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setPeriodLabel(domain.getPeriodLabel());
        entity.setPeriodStart(domain.getPeriodStart());
        entity.setPeriodEnd(domain.getPeriodEnd());
        entity.setDeclaredIncome(domain.getDeclaredIncome());
        entity.setActualIncome(domain.getActualIncome());
        entity.setNeedsPct(domain.getNeedsPct());
        entity.setWantsPct(domain.getWantsPct());
        entity.setSavingsPct(domain.getSavingsPct());
        entity.setNeedsSpent(domain.getNeedsSpent());
        entity.setWantsSpent(domain.getWantsSpent());
        entity.setSavingsAchieved(domain.getSavingsAchieved());
        entity.setEssentialExceeded(domain.isEssentialExceeded());
        entity.setVerdictSummary(domain.getVerdictSummary());
        entity.setRecommendationsJson(domain.getRecommendationsJson());
        entity.setSuggestedBudgetId(domain.getSuggestedBudgetId());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }
}
