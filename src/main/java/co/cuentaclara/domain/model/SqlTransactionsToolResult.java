package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record SqlTransactionsToolResult(
        BigDecimal totalAmount,
        BigDecimal totalTaxAmount,
        int count,
        List<TransactionEvidenceRow> rows
) {
}
