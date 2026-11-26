package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public class MonthlyReport {

    private UUID id;
    private UUID userId;
    private String periodLabel;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal declaredIncome = BigDecimal.ZERO;
    private BigDecimal actualIncome = BigDecimal.ZERO;
    private int needsPct = 50;
    private int wantsPct = 30;
    private int savingsPct = 20;
    private BigDecimal needsSpent = BigDecimal.ZERO;
    private BigDecimal wantsSpent = BigDecimal.ZERO;
    private BigDecimal savingsAchieved = BigDecimal.ZERO;
    private boolean essentialExceeded = false;
    private String verdictSummary;
    private String recommendationsJson;
    private UUID suggestedBudgetId;
    private OffsetDateTime createdAt;

    public MonthlyReport() {
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
