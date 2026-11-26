package co.cuentaclara.domain.model;

import java.math.BigDecimal;

public record ObligationInput(
        String name,
        String category,
        BudgetBlock budgetBlock,
        BigDecimal estimatedAmount,
        Integer dueDay,
        Boolean essential
) {
}
