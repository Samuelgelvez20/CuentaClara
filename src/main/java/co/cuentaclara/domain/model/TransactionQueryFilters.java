package co.cuentaclara.domain.model;

import java.time.LocalDate;

public record TransactionQueryFilters(
        String yearMonth,
        LocalDate startDate,
        LocalDate endDate,
        String category,
        String budgetBlock,
        String merchantKeyword
) {
}
