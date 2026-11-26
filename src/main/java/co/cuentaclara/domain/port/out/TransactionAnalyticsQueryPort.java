package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.ExpenseHistorySummary;
import co.cuentaclara.domain.model.PaymentFrequency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TransactionAnalyticsQueryPort {

    ExpenseHistorySummary queryExpenseHistory(
            UUID userId,
            String yearMonthFilter,
            LocalDate startDate,
            LocalDate endDate,
            String categoryFilter,
            String merchantFilter
    );

    BigDecimal sumRecordedIncomeBetween(UUID userId, LocalDate start, LocalDate end);

    BigDecimal sumRecurringObligationsMonthly(UUID userId);

    BigDecimal sumConfirmedExpensesByBlock(UUID userId, LocalDate start, LocalDate end, BudgetBlock block);

    int countConfirmedTransactionsBetween(UUID userId, LocalDate start, LocalDate end);

    int countAllTransactionsBetween(UUID userId, LocalDate start, LocalDate end);

    SmallExpensesStats querySmallWantsExpensesStats(UUID userId, LocalDate start, LocalDate end);

    List<String> findTopCitationsForBlock(UUID userId, LocalDate start, LocalDate end, BudgetBlock block, int limit);

    void replaceSimulatedIncome(UUID userId, LocalDate start, LocalDate end, BigDecimal netIncome, PaymentFrequency frequency);

    record SmallExpensesStats(int count, BigDecimal totalAmount) {
    }
}
