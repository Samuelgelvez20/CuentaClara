package co.cuentaclara.profile;

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
@Table(name = "budgets")
public class Budget {

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

    @Column(name = "budget_stage", nullable = false, length = 25)
    private String budgetStage = "INITIAL_ESTIMATE";

    @Column(nullable = false, length = 25)
    private String status = "SUGGESTED";

    @Column(name = "reference_income", nullable = false, precision = 15, scale = 2)
    private BigDecimal referenceIncome;

    @Column(name = "needs_pct", nullable = false)
    private int needsPct;

    @Column(name = "wants_pct", nullable = false)
    private int wantsPct;

    @Column(name = "savings_pct", nullable = false)
    private int savingsPct;

    @Column(name = "needs_limit", nullable = false, precision = 15, scale = 2)
    private BigDecimal needsLimit;

    @Column(name = "wants_limit", nullable = false, precision = 15, scale = 2)
    private BigDecimal wantsLimit;

    @Column(name = "savings_target", nullable = false, precision = 15, scale = 2)
    private BigDecimal savingsTarget;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Budget() {
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
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

    public String getBudgetStage() {
        return budgetStage;
    }

    public void setBudgetStage(String budgetStage) {
        this.budgetStage = budgetStage;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getReferenceIncome() {
        return referenceIncome;
    }

    public void setReferenceIncome(BigDecimal referenceIncome) {
        this.referenceIncome = referenceIncome;
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

    public BigDecimal getNeedsLimit() {
        return needsLimit;
    }

    public void setNeedsLimit(BigDecimal needsLimit) {
        this.needsLimit = needsLimit;
    }

    public BigDecimal getWantsLimit() {
        return wantsLimit;
    }

    public void setWantsLimit(BigDecimal wantsLimit) {
        this.wantsLimit = wantsLimit;
    }

    public BigDecimal getSavingsTarget() {
        return savingsTarget;
    }

    public void setSavingsTarget(BigDecimal savingsTarget) {
        this.savingsTarget = savingsTarget;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
