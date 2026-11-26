package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class RecurringObligation {

    private UUID id;
    private UUID userId;
    private String name;
    private String category;
    private BudgetBlock budgetBlock = BudgetBlock.NEEDS;
    private BigDecimal estimatedAmount = BigDecimal.ZERO;
    private int dueDay = 1;
    private boolean essential = true;
    private OffsetDateTime createdAt;

    public RecurringObligation() {
    }

    public RecurringObligation(
            UUID userId,
            String name,
            String category,
            BudgetBlock budgetBlock,
            BigDecimal estimatedAmount,
            int dueDay,
            boolean essential
    ) {
        this.userId = userId;
        this.name = name;
        this.category = category;
        this.budgetBlock = budgetBlock;
        this.estimatedAmount = estimatedAmount;
        this.dueDay = dueDay;
        this.essential = essential;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BudgetBlock getBudgetBlock() {
        return budgetBlock;
    }

    public void setBudgetBlock(BudgetBlock budgetBlock) {
        this.budgetBlock = budgetBlock;
    }

    public BigDecimal getEstimatedAmount() {
        return estimatedAmount;
    }

    public void setEstimatedAmount(BigDecimal estimatedAmount) {
        this.estimatedAmount = estimatedAmount;
    }

    public int getDueDay() {
        return dueDay;
    }

    public void setDueDay(int dueDay) {
        this.dueDay = dueDay;
    }

    public boolean isEssential() {
        return essential;
    }

    public void setEssential(boolean essential) {
        this.essential = essential;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
