package co.cuentaclara.infrastructure.adapter.out.persistence.jdbc;

import co.cuentaclara.domain.model.BackedRecommendation;
import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.PersistedPlanView;
import co.cuentaclara.domain.model.PlanActionView;
import co.cuentaclara.domain.model.RecurringObligation;
import co.cuentaclara.domain.port.out.BudgetPlanPersistencePort;
import co.cuentaclara.domain.port.out.RecurringObligationRepositoryPort;
import co.cuentaclara.domain.service.PlanCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BudgetPlanJdbcPersistenceAdapter implements BudgetPlanPersistencePort {

    private static final Logger log = LoggerFactory.getLogger(BudgetPlanJdbcPersistenceAdapter.class);
    private static final Set<String> FIXED_NEED_CATEGORIES = Set.of("vivienda", "deudas y préstamos", "servicios", "educación");

    private final JdbcTemplate jdbcTemplate;
    private final RecurringObligationRepositoryPort obligationRepository;
    private final PlanCalculator planCalculator = new PlanCalculator();

    public BudgetPlanJdbcPersistenceAdapter(
            JdbcTemplate jdbcTemplate,
            RecurringObligationRepositoryPort obligationRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.obligationRepository = obligationRepository;
    }

    @Override
    public PersistedPlanView calculateAndPersistPlan(
            UUID userId,
            YearMonth targetMonth,
            LocalDate sourceStart,
            LocalDate sourceEnd,
            Budget nextBudget,
            List<BackedRecommendation> recommendations
    ) {
        List<PlanCalculator.CategorySpendInput> categoryInputs = buildCategoryInputs(userId, sourceStart, sourceEnd);
        BigDecimal safeIncome = nextBudget.getReferenceIncome() != null
                && nextBudget.getReferenceIncome().compareTo(BigDecimal.ONE) >= 0
                ? nextBudget.getReferenceIncome().setScale(0, RoundingMode.HALF_UP)
                : new BigDecimal("1000");

        PlanCalculator.PlanCalculationResult calc = planCalculator.calculate(new PlanCalculator.PlanInput(
                safeIncome,
                nextBudget.getNeedsPct(),
                nextBudget.getWantsPct(),
                nextBudget.getSavingsPct(),
                0,
                targetMonth,
                categoryInputs
        ));

        String mappedStatus = "ACCEPTED".equalsIgnoreCase(nextBudget.getStatus()) ? "ACCEPTED" : "DRAFT";
        List<String> warningLabels = calc.warnings().stream().map(Enum::name).toList();
        String warningsText = warningLabels.isEmpty() ? "" : String.join(" | ", warningLabels);
        LocalDate targetMonthDate = targetMonth.atDay(1);

        UUID planId = jdbcTemplate.queryForObject(
                """
                INSERT INTO budget_plans (
                    user_id, target_month, income, pct_needs, pct_wants, pct_savings,
                    status, warnings, structural_gap, accepted_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'ACCEPTED' THEN NOW() ELSE NULL END)
                ON CONFLICT (user_id, target_month) DO UPDATE SET
                    income = EXCLUDED.income,
                    pct_needs = EXCLUDED.pct_needs,
                    pct_wants = EXCLUDED.pct_wants,
                    pct_savings = EXCLUDED.pct_savings,
                    status = EXCLUDED.status,
                    warnings = EXCLUDED.warnings,
                    structural_gap = EXCLUDED.structural_gap,
                    accepted_at = CASE WHEN EXCLUDED.status = 'ACCEPTED' THEN COALESCE(budget_plans.accepted_at, NOW()) ELSE NULL END
                RETURNING id
                """,
                UUID.class,
                userId,
                Date.valueOf(targetMonthDate),
                safeIncome,
                nextBudget.getNeedsPct(),
                nextBudget.getWantsPct(),
                nextBudget.getSavingsPct(),
                mappedStatus,
                warningsText,
                calc.structuralGap().setScale(0, RoundingMode.HALF_UP),
                mappedStatus
        );

        jdbcTemplate.update("DELETE FROM budget_lines WHERE plan_id = ?", planId);
        jdbcTemplate.update("DELETE FROM budget_plan_weeks WHERE plan_id = ?", planId);
        jdbcTemplate.update("DELETE FROM budget_plan_actions WHERE plan_id = ?", planId);

        for (PlanCalculator.CalculatedLine line : calc.lines()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO budget_lines (
                        plan_id, user_id, category_id, category_name, block,
                        last_month_amount, limit_amount, is_fixed, reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    planId,
                    userId,
                    line.categoryId(),
                    truncate(line.categoryName(), 80),
                    line.block().name(),
                    line.lastMonthAmount().max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP),
                    line.limitAmount().max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP),
                    line.isFixed(),
                    line.reason().name()
            );
        }

        for (PlanCalculator.CalculatedWeek week : calc.weeks()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO budget_plan_weeks (
                        plan_id, user_id, week_index, start_date, end_date,
                        wants_limit, variable_needs_limit
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    planId,
                    userId,
                    week.weekIndex(),
                    Date.valueOf(week.startDate()),
                    Date.valueOf(week.endDate()),
                    week.wantsLimit().max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP),
                    week.variableNeedsLimit().max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP)
            );
        }

        List<PlanActionView> persistedActions = new ArrayList<>();
        if (recommendations != null) {
            int pos = 1;
            for (BackedRecommendation rec : recommendations) {
                if (pos > 3) {
                    break;
                }
                String title = switch (rec.block()) {
                    case "NEEDS" -> "Prioridad esenciales";
                    case "WANTS" -> "Control de gustos y deseos";
                    case "SAVINGS" -> "Meta de ahorro programado";
                    default -> "Acción recomendada";
                };
                String explanation = truncate(rec.explanation(), 280);
                jdbcTemplate.update(
                        """
                        INSERT INTO budget_plan_actions (
                            plan_id, user_id, position, title, explanation,
                            category_id, category_name, source
                        ) VALUES (?, ?, ?, ?, ?, NULL, ?, 'FALLBACK')
                        """,
                        planId,
                        userId,
                        pos,
                        truncate(title, 60),
                        explanation,
                        truncate(rec.block(), 80)
                );
                persistedActions.add(new PlanActionView(pos, title, explanation, rec.block(), "FALLBACK"));
                pos++;
            }
        }

        return new PersistedPlanView(
                planId,
                targetMonth.toString(),
                mappedStatus,
                calc.structuralGap(),
                warningLabels,
                calc.lines(),
                calc.weeks(),
                persistedActions
        );
    }

    @Override
    public void syncPlanDecision(UUID userId, Budget updatedBudget) {
        if (updatedBudget == null) {
            return;
        }
        YearMonth targetYm = YearMonth.from(updatedBudget.getPeriodStart());
        LocalDate targetMonthDate = targetYm.atDay(1);
        String mappedStatus = "ACCEPTED".equalsIgnoreCase(updatedBudget.getStatus()) ? "ACCEPTED" : "DRAFT";
        try {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE budget_plans
                    SET status = ?,
                        pct_needs = ?,
                        pct_wants = ?,
                        pct_savings = ?,
                        accepted_at = CASE WHEN ? = 'ACCEPTED' THEN COALESCE(accepted_at, NOW()) ELSE NULL END
                    WHERE user_id = ? AND target_month = ?
                    """,
                    mappedStatus,
                    updatedBudget.getNeedsPct(),
                    updatedBudget.getWantsPct(),
                    updatedBudget.getSavingsPct(),
                    mappedStatus,
                    userId,
                    Date.valueOf(targetMonthDate)
            );
            if (updated == 0) {
                calculateAndPersistPlan(
                        userId,
                        targetYm,
                        updatedBudget.getPeriodStart().minusMonths(1),
                        updatedBudget.getPeriodStart().minusDays(1),
                        updatedBudget,
                        List.of()
                );
            }
        } catch (Exception ex) {
            log.debug("No se pudo sincronizar budget_plans al decidir plan {}: {}", updatedBudget.getId(), ex.getMessage());
        }
    }

    @Override
    public Optional<PersistedPlanView> findPlanForTargetMonth(UUID userId, YearMonth targetMonth) {
        List<Map<String, Object>> plans = jdbcTemplate.queryForList(
                """
                SELECT id, target_month, status, warnings, structural_gap
                FROM budget_plans
                WHERE user_id = ? AND target_month = ?
                """,
                userId,
                Date.valueOf(targetMonth.atDay(1))
        );
        if (plans.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> planRow = plans.get(0);
        UUID planId = (UUID) planRow.get("id");
        String status = String.valueOf(planRow.get("status"));
        String warningsRaw = String.valueOf(planRow.getOrDefault("warnings", ""));
        List<String> warnings = warningsRaw.isBlank() ? List.of() : List.of(warningsRaw.split("\\s*\\|\\s*"));
        BigDecimal gap = planRow.get("structural_gap") instanceof BigDecimal bd
                ? bd : new BigDecimal(String.valueOf(planRow.getOrDefault("structural_gap", "0")));

        List<PlanCalculator.CalculatedLine> lines = jdbcTemplate.query(
                """
                SELECT category_id, category_name, block, last_month_amount, limit_amount, is_fixed, reason
                FROM budget_lines
                WHERE plan_id = ?
                ORDER BY block, limit_amount DESC
                """,
                (rs, i) -> new PlanCalculator.CalculatedLine(
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"),
                        BudgetBlock.valueOf(rs.getString("block")),
                        rs.getBigDecimal("last_month_amount"),
                        rs.getBigDecimal("limit_amount"),
                        rs.getBoolean("is_fixed"),
                        PlanCalculator.LineReason.valueOf(rs.getString("reason"))
                ),
                planId
        );

        List<PlanCalculator.CalculatedWeek> weeks = jdbcTemplate.query(
                """
                SELECT week_index, start_date, end_date, wants_limit, variable_needs_limit
                FROM budget_plan_weeks
                WHERE plan_id = ?
                ORDER BY week_index ASC
                """,
                (rs, i) -> {
                    LocalDate s = rs.getDate("start_date").toLocalDate();
                    LocalDate e = rs.getDate("end_date").toLocalDate();
                    return new PlanCalculator.CalculatedWeek(
                            rs.getInt("week_index"),
                            s,
                            e,
                            (int) ((e.toEpochDay() - s.toEpochDay()) + 1),
                            rs.getBigDecimal("wants_limit"),
                            rs.getBigDecimal("variable_needs_limit")
                    );
                },
                planId
        );

        List<PlanActionView> actions = jdbcTemplate.query(
                """
                SELECT position, title, explanation, category_name, source
                FROM budget_plan_actions
                WHERE plan_id = ?
                ORDER BY position ASC
                """,
                (rs, i) -> new PlanActionView(
                        rs.getInt("position"),
                        rs.getString("title"),
                        rs.getString("explanation"),
                        rs.getString("category_name"),
                        rs.getString("source")
                ),
                planId
        );

        return Optional.of(new PersistedPlanView(
                planId,
                targetMonth.toString(),
                status,
                gap,
                warnings,
                lines,
                weeks,
                actions
        ));
    }

    private List<PlanCalculator.CategorySpendInput> buildCategoryInputs(
            UUID userId,
            LocalDate sourceStart,
            LocalDate sourceEnd
    ) {
        Map<String, PlanCalculator.CategorySpendInput> byKey = new LinkedHashMap<>();

        List<Map<String, Object>> spentRows = jdbcTemplate.queryForList(
                """
                SELECT category, budget_block, COALESCE(SUM(total_amount), 0) AS total
                FROM transactions
                WHERE user_id = ? AND confirmed = TRUE
                  AND transaction_date >= ? AND transaction_date <= ?
                GROUP BY category, budget_block
                """,
                userId, Date.valueOf(sourceStart), Date.valueOf(sourceEnd)
        );
        for (Map<String, Object> row : spentRows) {
            String category = String.valueOf(row.get("category"));
            BudgetBlock block = BudgetBlock.valueOf(String.valueOf(row.get("budget_block")));
            if (block == BudgetBlock.SAVINGS) {
                continue;
            }
            BigDecimal total = row.get("total") instanceof BigDecimal bd
                    ? bd : new BigDecimal(String.valueOf(row.get("total")));
            boolean isFixed = block == BudgetBlock.NEEDS
                    && FIXED_NEED_CATEGORIES.contains(category.trim().toLowerCase(Locale.ROOT));
            String key = block.name() + "|" + category.toLowerCase(Locale.ROOT);
            byKey.put(key, new PlanCalculator.CategorySpendInput(null, category, block, isFixed, total));
        }

        List<RecurringObligation> obligations = obligationRepository.findByUserIdOrderByDueDayAsc(userId);
        for (RecurringObligation ob : obligations) {
            if (ob.getBudgetBlock() != BudgetBlock.NEEDS) {
                continue;
            }
            String category = ob.getCategory() != null && !ob.getCategory().isBlank() ? ob.getCategory() : ob.getName();
            String key = BudgetBlock.NEEDS.name() + "|" + category.toLowerCase(Locale.ROOT);
            PlanCalculator.CategorySpendInput existing = byKey.get(key);
            BigDecimal est = ob.getEstimatedAmount() != null ? ob.getEstimatedAmount() : BigDecimal.ZERO;
            BigDecimal amount = existing != null ? existing.lastMonthAmount().max(est) : est;
            byKey.put(key, new PlanCalculator.CategorySpendInput(null, category, BudgetBlock.NEEDS, true, amount));
        }

        if (byKey.values().stream().noneMatch(c -> c.block() == BudgetBlock.WANTS)) {
            byKey.put("WANTS|entretenimiento", new PlanCalculator.CategorySpendInput(
                    null, "Entretenimiento y Salidas", BudgetBlock.WANTS, false, BigDecimal.ZERO));
            byKey.put("WANTS|domicilios", new PlanCalculator.CategorySpendInput(
                    null, "Domicilios y Restaurantes", BudgetBlock.WANTS, false, BigDecimal.ZERO));
        }
        return new ArrayList<>(byKey.values());
    }

    private String truncate(String val, int maxLen) {
        if (val == null) {
            return "";
        }
        String trimmed = val.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen - 1) + "…";
    }
}
