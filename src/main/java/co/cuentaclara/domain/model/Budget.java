package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public class Budget {

    private UUID id;
    private UUID userId;
    private String periodLabel;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String budgetStage = "INITIAL_ESTIMATE";
    private String status = "SUGGESTED";
    private BigDecimal referenceIncome;
    private int needsPct;
    private int wantsPct;
    private int savingsPct;
    private BigDecimal needsLimit;
    private BigDecimal wantsLimit;
    private BigDecimal savingsTarget;
    private OffsetDateTime createdAt;

    public Budget() {
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

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
