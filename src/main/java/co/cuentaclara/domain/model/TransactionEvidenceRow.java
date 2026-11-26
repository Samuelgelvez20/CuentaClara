package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionEvidenceRow(
        UUID transactionId,
        String merchantName,
        String merchantNit,
        String invoiceNumber,
        LocalDate transactionDate,
        BigDecimal subtotalAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String category,
        String budgetBlock,
        String description,
        String citationReference
) {
}
