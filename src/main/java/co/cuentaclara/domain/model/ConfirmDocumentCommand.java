package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ConfirmDocumentCommand(
        String merchantName,
        String merchantNit,
        String invoiceNumber,
        String cufe,
        LocalDate transactionDate,
        BigDecimal subtotalAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String category,
        BudgetBlock budgetBlock,
        String description,
        String itemsSummary
) {
}
