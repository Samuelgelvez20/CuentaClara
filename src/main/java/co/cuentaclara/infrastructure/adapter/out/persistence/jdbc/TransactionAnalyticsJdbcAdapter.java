package co.cuentaclara.infrastructure.adapter.out.persistence.jdbc;

import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.ExpenseHistorySummary;
import co.cuentaclara.domain.model.PaymentFrequency;
import co.cuentaclara.domain.model.Transaction;
import co.cuentaclara.domain.port.out.TransactionAnalyticsQueryPort;
import co.cuentaclara.domain.port.out.TransactionRepositoryPort;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransactionAnalyticsJdbcAdapter implements TransactionAnalyticsQueryPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionRepositoryPort transactionRepository;

    public TransactionAnalyticsJdbcAdapter(
            JdbcTemplate jdbcTemplate,
            TransactionRepositoryPort transactionRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public ExpenseHistorySummary queryExpenseHistory(
            UUID userId,
            String yearMonthFilter,
            LocalDate startDate,
            LocalDate endDate,
            String categoryFilter,
            String merchantFilter
    ) {
        StringBuilder whereClause = new StringBuilder(" WHERE confirmed = TRUE AND user_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (startDate != null || endDate != null) {
            LocalDate effectiveStart = startDate;
            LocalDate effectiveEnd = endDate;
            if (effectiveStart != null && effectiveEnd != null && effectiveStart.isAfter(effectiveEnd)) {
                LocalDate tmp = effectiveStart;
                effectiveStart = effectiveEnd;
                effectiveEnd = tmp;
            }
            if (effectiveStart != null) {
                whereClause.append(" AND transaction_date >= ? ");
                params.add(Date.valueOf(effectiveStart));
            }
            if (effectiveEnd != null) {
                whereClause.append(" AND transaction_date <= ? ");
                params.add(Date.valueOf(effectiveEnd));
            }
        } else if (yearMonthFilter != null && !yearMonthFilter.isBlank()
                && !"ALL".equalsIgnoreCase(yearMonthFilter.trim())) {
            YearMonth ym = YearMonth.parse(yearMonthFilter.trim());
            whereClause.append(" AND transaction_date >= ? AND transaction_date <= ? ");
            params.add(Date.valueOf(ym.atDay(1)));
            params.add(Date.valueOf(ym.atEndOfMonth()));
        }
        if (categoryFilter != null && !categoryFilter.isBlank()) {
            whereClause.append(" AND LOWER(category) = LOWER(?) ");
            params.add(categoryFilter.trim());
        }
        if (merchantFilter != null && !merchantFilter.isBlank()) {
            whereClause.append(" AND LOWER(merchant_name) LIKE LOWER(?) ");
            params.add("%" + merchantFilter.trim() + "%");
        }

        String listSql = "SELECT id FROM transactions " + whereClause + " ORDER BY transaction_date DESC, created_at DESC";
        List<UUID> ids = jdbcTemplate.queryForList(listSql, UUID.class, params.toArray());
        List<Transaction> transactions = ids.stream()
                .map(transactionRepository::findById)
                .flatMap(Optional::stream)
                .toList();

        String categoryTotalsSql = "SELECT category, COALESCE(SUM(total_amount), 0) AS cat_total "
                + "FROM transactions " + whereClause
                + " GROUP BY category ORDER BY cat_total DESC";
        Map<String, BigDecimal> totalsByCategory = new LinkedHashMap<>();
        jdbcTemplate.query(categoryTotalsSql, rs -> {
            totalsByCategory.put(rs.getString("category"), rs.getBigDecimal("cat_total"));
        }, params.toArray());

        String grandTotalSql = "SELECT COALESCE(SUM(total_amount), 0) FROM transactions " + whereClause;
        BigDecimal grandTotal = jdbcTemplate.queryForObject(grandTotalSql, BigDecimal.class, params.toArray());

        return new ExpenseHistorySummary(
                transactions,
                totalsByCategory,
                grandTotal != null ? grandTotal : BigDecimal.ZERO
        );
    }

    @Override
    public BigDecimal sumRecordedIncomeBetween(UUID userId, LocalDate start, LocalDate end) {
        BigDecimal val = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM incomes WHERE user_id = ? AND received_date >= ? AND received_date <= ?",
                BigDecimal.class,
                userId, Date.valueOf(start), Date.valueOf(end)
        );
        return val != null ? val : BigDecimal.ZERO;
    }

    @Override
    public BigDecimal sumRecurringObligationsMonthly(UUID userId) {
        BigDecimal val = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(estimated_amount), 0) FROM recurring_obligations WHERE user_id = ?",
                BigDecimal.class,
                userId
        );
        return val != null ? val : BigDecimal.ZERO;
    }

    @Override
    public BigDecimal sumConfirmedExpensesByBlock(UUID userId, LocalDate start, LocalDate end, BudgetBlock block) {
        BigDecimal val = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(total_amount), 0)
                FROM transactions
                WHERE user_id = ? AND confirmed = TRUE
                  AND transaction_date >= ? AND transaction_date <= ?
                  AND budget_block = ?
                """,
                BigDecimal.class,
                userId, Date.valueOf(start), Date.valueOf(end), block.name()
        );
        return val != null ? val : BigDecimal.ZERO;
    }

    @Override
    public int countConfirmedTransactionsBetween(UUID userId, LocalDate start, LocalDate end) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ? AND confirmed = TRUE AND transaction_date >= ? AND transaction_date <= ?",
                Integer.class,
                userId, Date.valueOf(start), Date.valueOf(end)
        );
        return cnt != null ? cnt : 0;
    }

    @Override
    public int countAllTransactionsBetween(UUID userId, LocalDate start, LocalDate end) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ? AND transaction_date >= ? AND transaction_date <= ?",
                Integer.class,
                userId, Date.valueOf(start), Date.valueOf(end)
        );
        return cnt != null ? cnt : 0;
    }

    @Override
    public SmallExpensesStats querySmallWantsExpensesStats(UUID userId, LocalDate start, LocalDate end) {
        Map<String, Object> smallStats = jdbcTemplate.queryForMap(
                """
                SELECT COUNT(*) AS cnt, COALESCE(SUM(total_amount), 0) AS total
                FROM transactions
                WHERE user_id = ? AND confirmed = TRUE
                  AND transaction_date >= ? AND transaction_date <= ?
                  AND budget_block = 'WANTS'
                  AND total_amount <= 50000
                """,
                userId, Date.valueOf(start), Date.valueOf(end)
        );
        int smallCount = ((Number) smallStats.get("cnt")).intValue();
        BigDecimal smallTotal = smallStats.get("total") instanceof BigDecimal bd
                ? bd
                : new BigDecimal(smallStats.get("total").toString());
        return new SmallExpensesStats(smallCount, smallTotal);
    }

    @Override
    public List<String> findTopCitationsForBlock(UUID userId, LocalDate start, LocalDate end, BudgetBlock block, int limit) {
        return jdbcTemplate.query(
                """
                SELECT merchant_name, total_amount, citation_reference
                FROM transactions
                WHERE user_id = ? AND confirmed = TRUE
                  AND transaction_date >= ? AND transaction_date <= ?
                  AND budget_block = ?
                ORDER BY total_amount DESC
                LIMIT ?
                """,
                (rs, rowNum) -> String.format(
                        "%s ($%,.2f COP) [%s]",
                        rs.getString("merchant_name"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("citation_reference")
                ),
                userId, Date.valueOf(start), Date.valueOf(end), block.name(), limit
        );
    }

    @Override
    public void replaceSimulatedIncome(UUID userId, LocalDate start, LocalDate end, BigDecimal netIncome, PaymentFrequency frequency) {
        jdbcTemplate.update(
                "DELETE FROM incomes WHERE user_id = ? AND is_simulated = TRUE AND received_date >= ? AND received_date <= ?",
                userId, Date.valueOf(start), Date.valueOf(end)
        );
        jdbcTemplate.update(
                "INSERT INTO incomes (user_id, source_name, amount, received_date, period_type, is_simulated) VALUES (?, ?, ?, ?, ?, TRUE)",
                userId, "Salario Mensual Simulado", netIncome, Date.valueOf(start), frequency.name()
        );
    }
}
