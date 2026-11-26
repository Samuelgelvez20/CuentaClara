package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "monthly_reports")
public class MonthlyReportJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "period_label", nullable = false, length = 40)
    private String periodLabel;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "declared_income", nullable = false, precision = 15, scale = 2)
    private BigDecimal declaredIncome = BigDecimal.ZERO;

    @Column(name = "actual_income", nullable = false, precision = 15, scale = 2)
    private BigDecimal actualIncome = BigDecimal.ZERO;

    @Column(name = "needs_pct", nullable = false)
    private int needsPct = 50;

    @Column(name = "wants_pct", nullable = false)
    private int wantsPct = 30;

    @Column(name = "savings_pct", nullable = false)
    private int savingsPct = 20;

    @Column(name = "needs_spent", nullable = false, precision = 15, scale = 2)
    private BigDecimal needsSpent = BigDecimal.ZERO;

    @Column(name = "wants_spent", nullable = false, precision = 15, scale = 2)
    private BigDecimal wantsSpent = BigDecimal.ZERO;

    @Column(name = "savings_achieved", nullable = false, precision = 15, scale = 2)
    private BigDecimal savingsAchieved = BigDecimal.ZERO;

    @Column(name = "essential_exceeded", nullable = false)
    private boolean essentialExceeded = false;

    @Column(name = "verdict_summary", nullable = false)
    private String verdictSummary;

    @Column(name = "recommendations_json", nullable = false)
    private String recommendationsJson;

    @Column(name = "suggested_budget_id")
    private UUID suggestedBudgetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public MonthlyReportJpaEntity() {
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getPeriodLabel() {
        return periodLabel;
    }

    public void setPeriodLabel(String periodLabel) {
        this.periodLabel = periodLabel;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public BigDecimal getDeclaredIncome() {
        return declaredIncome;
    }

    public void setDeclaredIncome(BigDecimal declaredIncome) {
        this.declaredIncome = declaredIncome;
    }

    public BigDecimal getActualIncome() {
        return actualIncome;
    }

    public void setActualIncome(BigDecimal actualIncome) {
        this.actualIncome = actualIncome;
    }

    public int getNeedsPct() {
        return needsPct;
    }

    public void setNeedsPct(int needsPct) {
        this.needsPct = needsPct;
    }

    public int getWantsPct() {
        return wantsPct;
    }

    public void setWantsPct(int wantsPct) {
        this.wantsPct = wantsPct;
    }

    public int getSavingsPct() {
        return savingsPct;
    }

    public void setSavingsPct(int savingsPct) {
        this.savingsPct = savingsPct;
    }

    public BigDecimal getNeedsSpent() {
        return needsSpent;
    }

    public void setNeedsSpent(BigDecimal needsSpent) {
        this.needsSpent = needsSpent;
    }

    public BigDecimal getWantsSpent() {
        return wantsSpent;
    }

    public void setWantsSpent(BigDecimal wantsSpent) {
        this.wantsSpent = wantsSpent;
    }

    public BigDecimal getSavingsAchieved() {
        return savingsAchieved;
    }

    public void setSavingsAchieved(BigDecimal savingsAchieved) {
        this.savingsAchieved = savingsAchieved;
    }

    public boolean isEssentialExceeded() {
        return essentialExceeded;
    }

    public void setEssentialExceeded(boolean essentialExceeded) {
        this.essentialExceeded = essentialExceeded;
    }

    public String getVerdictSummary() {
        return verdictSummary;
    }

    public void setVerdictSummary(String verdictSummary) {
        this.verdictSummary = verdictSummary;
    }

    public String getRecommendationsJson() {
        return recommendationsJson;
    }

    public void setRecommendationsJson(String recommendationsJson) {
        this.recommendationsJson = recommendationsJson;
    }

    public UUID getSuggestedBudgetId() {
        return suggestedBudgetId;
    }

    public void setSuggestedBudgetId(UUID suggestedBudgetId) {
        this.suggestedBudgetId = suggestedBudgetId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
