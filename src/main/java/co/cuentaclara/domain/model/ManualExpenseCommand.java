package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ManualExpenseCommand(
        String merchantName,
        LocalDate transactionDate,
        BigDecimal totalAmount,
        String category,
        BudgetBlock budgetBlock,
        String description
) {
}
