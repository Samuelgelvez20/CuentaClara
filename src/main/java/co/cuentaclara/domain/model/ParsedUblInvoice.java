package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ParsedUblInvoice(
        String merchantName,
        String merchantNit,
        String invoiceNumber,
        String cufe,
        LocalDate issueDate,
        BigDecimal subtotalAmount,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        List<ParsedLineItem> items,
        String itemsJson,
        String suggestedCategory,
        BudgetBlock suggestedBudgetBlock,
        String rawSummaryText
) {
    public boolean issuerValidForDuplicateCheck() {
        return merchantNit != null && !merchantNit.isBlank() && !"0".equals(merchantNit)
                && invoiceNumber != null && !invoiceNumber.isBlank() && !"SIN-NUMERO".equals(invoiceNumber);
    }
}
