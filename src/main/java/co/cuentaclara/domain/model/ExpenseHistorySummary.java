package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ExpenseHistorySummary(
        List<Transaction> transactions,
        Map<String, BigDecimal> totalsByCategory,
        BigDecimal grandTotal
) {
}
