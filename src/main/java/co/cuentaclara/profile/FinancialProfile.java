package co.cuentaclara.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "financial_profiles")
public class FinancialProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "net_income", nullable = false, precision = 15, scale = 2)
    private BigDecimal netIncome = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_frequency", nullable = false, length = 20)
    private PaymentFrequency paymentFrequency = PaymentFrequency.MONTHLY;

    @Column(name = "payment_days", nullable = false, length = 100)
    private String paymentDays = "30";

    @Column(name = "needs_pct", nullable = false)
    private int needsPct = 50;

    @Column(name = "wants_pct", nullable = false)
    private int wantsPct = 30;

    @Column(name = "savings_pct", nullable = false)
    private int savingsPct = 20;

    @Column(name = "needs_cut_percent", nullable = false)
    private int needsCutPercent = 10;

    @Column(name = "financial_goals")
    private String financialGoals;

    @Column(name = "total_debts", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalDebts = BigDecimal.ZERO;

    @Column(name = "questionnaire_completed", nullable = false)
    private boolean questionnaireCompleted = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public FinancialProfile() {
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
