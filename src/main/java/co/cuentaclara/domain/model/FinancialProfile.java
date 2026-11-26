package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class FinancialProfile {

    private UUID id;
    private UUID userId;
    private BigDecimal netIncome = BigDecimal.ZERO;
    private PaymentFrequency paymentFrequency = PaymentFrequency.MONTHLY;
    private String paymentDays = "30";
    private int needsPct = 50;
    private int wantsPct = 30;
    private int savingsPct = 20;
    private int needsCutPercent = 10;
    private String financialGoals;
    private BigDecimal totalDebts = BigDecimal.ZERO;
    private boolean questionnaireCompleted = true;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public FinancialProfile() {
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

    public BigDecimal getNetIncome() {
        return netIncome;
    }

    public void setNetIncome(BigDecimal netIncome) {
        this.netIncome = netIncome;
    }

    public PaymentFrequency getPaymentFrequency() {
        return paymentFrequency;
    }

    public void setPaymentFrequency(PaymentFrequency paymentFrequency) {
        this.paymentFrequency = paymentFrequency;
    }

    public String getPaymentDays() {
        return paymentDays;
    }

    public void setPaymentDays(String paymentDays) {
        this.paymentDays = paymentDays;
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

    public int getNeedsCutPercent() {
        return needsCutPercent;
    }

    public void setNeedsCutPercent(int needsCutPercent) {
        this.needsCutPercent = Math.max(0, Math.min(30, needsCutPercent));
    }

    public String getFinancialGoals() {
        return financialGoals;
    }

    public void setFinancialGoals(String financialGoals) {
        this.financialGoals = financialGoals;
    }

    public BigDecimal getTotalDebts() {
        return totalDebts;
    }

    public void setTotalDebts(BigDecimal totalDebts) {
        this.totalDebts = totalDebts;
    }

    public boolean isQuestionnaireCompleted() {
        return questionnaireCompleted;
    }

    public void setQuestionnaireCompleted(boolean questionnaireCompleted) {
        this.questionnaireCompleted = questionnaireCompleted;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
