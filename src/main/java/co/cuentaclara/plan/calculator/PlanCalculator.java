package co.cuentaclara.plan.calculator;

import co.cuentaclara.profile.BudgetBlock;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Calculadora determinista pura (sin Spring ni acceso a base de datos) para el
 * "Plan de gastos del mes siguiente".
 * Todos los cálculos se realizan en BigDecimal (COP sin decimales, escala 0).
 */
public final class PlanCalculator {

    public enum WarningCode {
        EXTRA_TO_SAVINGS,
        SAVINGS_REDUCED,
        NEEDS_EXCEED_INCOME
    }

    public enum LineReason {
        FIXED_KEPT("Gasto fijo mantenido igual al mes anterior"),
        VARIABLE_CUT("Ajuste gradual sobre gasto necesario variable"),
        PROPORTIONAL("Reparto proporcional según tu consumo del último mes"),
        EQUAL_SPLIT("Reparto equitativo entre categorías de deseos"),
        SAVINGS_GOAL("Meta de ahorro del mes (incluye excedentes no asignados)");

        private final String description;

        LineReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public record CategorySpendInput(
            UUID categoryId,
            String categoryName,
            BudgetBlock block,
            boolean isFixed,
            BigDecimal lastMonthAmount
    ) {
        public CategorySpendInput {
            Objects.requireNonNull(categoryName, "El nombre de la categoría es obligatorio");
            Objects.requireNonNull(block, "El bloque de la categoría es obligatorio");
            lastMonthAmount = lastMonthAmount != null ? roundCop(lastMonthAmount) : BigDecimal.ZERO;
        }
    }

    public record PlanInput(
            BigDecimal income,
            int pctNeeds,
            int pctWants,
            int pctSavings,
            int needsCutPercent,
            YearMonth targetMonth,
            List<CategorySpendInput> categorySpends
    ) {
    }

    public record BlockSummary(
            BudgetBlock block,
            int targetPercent,
            BigDecimal targetAmount,
            BigDecimal lastMonthAmount,
            BigDecimal planAmount,
            int actualPlanPercent
    ) {
    }

    public record CalculatedLine(
            UUID categoryId,
            String categoryName,
            BudgetBlock block,
            BigDecimal lastMonthAmount,
            BigDecimal limitAmount,
            boolean isFixed,
            LineReason reason
    ) {
    }

    public record CalculatedWeek(
            int weekIndex,
            LocalDate startDate,
            LocalDate endDate,
            int daysCount,
            BigDecimal wantsLimit,
            BigDecimal variableNeedsLimit
    ) {
    }

    public record PlanCalculationResult(
            YearMonth targetMonth,
            BigDecimal income,
            int pctNeeds,
            int pctWants,
            int pctSavings,
            int needsCutPercent,
            BlockSummary needsBlock,
            BlockSummary wantsBlock,
            BlockSummary savingsBlock,
            BigDecimal needsFixed,
            BigDecimal needsVariableLast,
            BigDecimal needsVariablePlan,
            BigDecimal structuralGap,
            BigDecimal projectedSavings,
            List<CalculatedLine> lines,
            List<CalculatedWeek> weeks,
            List<WarningCode> warnings
    ) {
    }

    public PlanCalculationResult calculate(PlanInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Los datos de entrada del plan no pueden ser nulos.");
        }
        if (input.income() == null || input.income().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El ingreso mensual debe ser mayor que 0.");
        }
        if (input.pctNeeds() < 0 || input.pctWants() < 0 || input.pctSavings() < 0
                || (input.pctNeeds() + input.pctWants() + input.pctSavings() != 100)) {
            throw new IllegalArgumentException("Los porcentajes de Necesidades, Deseos y Ahorro deben sumar exactamente 100%.");
        }
        if (input.needsCutPercent() < 0 || input.needsCutPercent() > 30) {
            throw new IllegalArgumentException("El porcentaje de recorte en necesidades variables debe estar entre 0 y 30.");
        }
        YearMonth targetMonth = input.targetMonth() != null ? input.targetMonth() : YearMonth.now().plusMonths(1);
        BigDecimal income = roundCop(input.income());

        // 1. Objetivos: targetNeeds / Wants / Savings = ingreso * porcentaje
        BigDecimal targetNeeds = percentageCop(income, input.pctNeeds());
        BigDecimal targetWants = percentageCop(income, input.pctWants());
        BigDecimal targetSavings = income.subtract(targetNeeds).subtract(targetWants);

        List<CategorySpendInput> categories = input.categorySpends() != null ? input.categorySpends() : List.of();

        // 2. needsFixed, needsVariable, needsLast y wantsLast
        BigDecimal needsFixed = categories.stream()
                .filter(c -> c.block() == BudgetBlock.NEEDS && c.isFixed())
                .map(CategorySpendInput::lastMonthAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal needsVariableLast = categories.stream()
                .filter(c -> c.block() == BudgetBlock.NEEDS && !c.isFixed())
                .map(CategorySpendInput::lastMonthAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal needsLast = needsFixed.add(needsVariableLast);

        BigDecimal wantsLast = categories.stream()
                .filter(c -> c.block() == BudgetBlock.WANTS)
                .map(CategorySpendInput::lastMonthAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal savingsLast = income.subtract(needsLast).subtract(wantsLast).max(BigDecimal.ZERO);

        // 3. savingsPlan inicial = targetSavings
        BigDecimal savingsPlan = targetSavings;

        // 4. needsPlan: si needsLast <= targetNeeds, es needsLast (sin recorte).
        // Si no, es needsFixed + needsVariable * (1 - needsCutPercent/100). Nunca menor que needsFixed ni mayor que needsLast.
        boolean cutAppliedOnNeeds = needsLast.compareTo(targetNeeds) > 0;
        BigDecimal needsPlan;
        if (!cutAppliedOnNeeds) {
            needsPlan = needsLast;
        } else {
            BigDecimal factor = BigDecimal.valueOf(100 - input.needsCutPercent());
            BigDecimal cutVariable = needsVariableLast.multiply(factor)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            BigDecimal candidate = needsFixed.add(cutVariable);
            needsPlan = candidate.max(needsFixed).min(needsLast);
        }

        // 5. structuralGap = max(0, needsLast - targetNeeds)
        BigDecimal structuralGap = needsLast.subtract(targetNeeds).max(BigDecimal.ZERO);

        // 6 y 7. Cálculo de wantsPlan, ajustes de savingsPlan y advertencias
        Set<WarningCode> warnings = new LinkedHashSet<>();
        BigDecimal wantsPlan;

        if (needsPlan.compareTo(income) > 0) {
            warnings.add(WarningCode.NEEDS_EXCEED_INCOME);
        }

        BigDecimal remainingAfterNeeds = income.subtract(needsPlan);
        if (remainingAfterNeeds.compareTo(savingsPlan) < 0) {
            wantsPlan = BigDecimal.ZERO;
            savingsPlan = remainingAfterNeeds.max(BigDecimal.ZERO);
            warnings.add(WarningCode.SAVINGS_REDUCED);
        } else {
            BigDecimal rawWants = remainingAfterNeeds.subtract(savingsPlan);
            if (rawWants.compareTo(targetWants) > 0) {
                BigDecimal extra = rawWants.subtract(targetWants);
                wantsPlan = targetWants;
                savingsPlan = savingsPlan.add(extra);
                warnings.add(WarningCode.EXTRA_TO_SAVINGS);
            } else {
                wantsPlan = rawWants;
            }
        }

        BigDecimal needsVariablePlan = needsPlan.subtract(needsFixed).max(BigDecimal.ZERO);

        // 8. Líneas por categoría con ajuste exacto de redondeo en la línea mayor de cada bloque
        List<CalculatedLine> allLines = new ArrayList<>();
        allLines.addAll(buildNeedsLines(categories, needsPlan, cutAppliedOnNeeds, input.needsCutPercent()));
        allLines.addAll(buildWantsLines(categories, wantsLast, wantsPlan));
        allLines.add(new CalculatedLine(
                null,
                "Ahorro",
                BudgetBlock.SAVINGS,
                savingsLast,
                savingsPlan,
                false,
                LineReason.SAVINGS_GOAL
        ));

        // 9. Semanas: tramos consecutivos de 7 días desde el día 1, ajustando el último tramo
        List<CalculatedWeek> weeks = buildWeeks(targetMonth, wantsPlan, needsVariablePlan);

        // 10. Resúmenes de bloques
        BlockSummary needsSummary = new BlockSummary(
                BudgetBlock.NEEDS,
                input.pctNeeds(),
                targetNeeds,
                needsLast,
                needsPlan,
                percentOfIncome(needsPlan, income)
        );
        BlockSummary wantsSummary = new BlockSummary(
                BudgetBlock.WANTS,
                input.pctWants(),
                targetWants,
                wantsLast,
                wantsPlan,
                percentOfIncome(wantsPlan, income)
        );
        BlockSummary savingsSummary = new BlockSummary(
                BudgetBlock.SAVINGS,
                input.pctSavings(),
                targetSavings,
                savingsLast,
                savingsPlan,
                percentOfIncome(savingsPlan, income)
        );

        return new PlanCalculationResult(
                targetMonth,
                income,
                input.pctNeeds(),
                input.pctWants(),
                input.pctSavings(),
                input.needsCutPercent(),
                needsSummary,
                wantsSummary,
                savingsSummary,
                needsFixed,
                needsVariableLast,
                needsVariablePlan,
                structuralGap,
                savingsPlan,
                List.copyOf(allLines),
                List.copyOf(weeks),
                List.copyOf(warnings)
        );
    }

    private List<CalculatedLine> buildNeedsLines(
            List<CategorySpendInput> categories,
            BigDecimal needsPlan,
            boolean cutAppliedOnNeeds,
            int needsCutPercent
    ) {
        List<CategorySpendInput> needsCats = categories.stream()
                .filter(c -> c.block() == BudgetBlock.NEEDS)
                .toList();

        if (needsCats.isEmpty()) {
            if (needsPlan.compareTo(BigDecimal.ZERO) == 0) {
                return List.of();
            }
            return List.of(new CalculatedLine(
                    null, "Necesidades", BudgetBlock.NEEDS, needsPlan, needsPlan, false, LineReason.VARIABLE_CUT
            ));
        }

        List<CalculatedLine> lines = new ArrayList<>();
        for (CategorySpendInput cat : needsCats) {
            if (cat.isFixed()) {
                lines.add(new CalculatedLine(
                        cat.categoryId(),
                        cat.categoryName(),
                        BudgetBlock.NEEDS,
                        cat.lastMonthAmount(),
                        cat.lastMonthAmount(),
                        true,
                        LineReason.FIXED_KEPT
                ));
            } else {
                BigDecimal limit;
                if (cutAppliedOnNeeds && needsCutPercent > 0) {
                    limit = cat.lastMonthAmount()
                            .multiply(BigDecimal.valueOf(100 - needsCutPercent))
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                } else {
                    limit = cat.lastMonthAmount();
                }
                lines.add(new CalculatedLine(
                        cat.categoryId(),
                        cat.categoryName(),
                        BudgetBlock.NEEDS,
                        cat.lastMonthAmount(),
                        limit,
                        false,
                        LineReason.VARIABLE_CUT
                ));
            }
        }

        return adjustRoundingOnLargestLine(lines, needsPlan, false);
    }

    private List<CalculatedLine> buildWantsLines(
            List<CategorySpendInput> categories,
            BigDecimal wantsLast,
            BigDecimal wantsPlan
    ) {
        List<CategorySpendInput> wantsCats = categories.stream()
                .filter(c -> c.block() == BudgetBlock.WANTS)
                .toList();

        if (wantsCats.isEmpty()) {
            return List.of(new CalculatedLine(
                    null,
                    "Deseos y Ocio",
                    BudgetBlock.WANTS,
                    wantsLast,
                    wantsPlan,
                    false,
                    wantsLast.compareTo(BigDecimal.ZERO) > 0 ? LineReason.PROPORTIONAL : LineReason.EQUAL_SPLIT
            ));
        }

        List<CalculatedLine> lines = new ArrayList<>();
        if (wantsLast.compareTo(BigDecimal.ZERO) > 0) {
            for (CategorySpendInput cat : wantsCats) {
                BigDecimal limit = wantsPlan.multiply(cat.lastMonthAmount())
                        .divide(wantsLast, 0, RoundingMode.HALF_UP);
                lines.add(new CalculatedLine(
                        cat.categoryId(),
                        cat.categoryName(),
                        BudgetBlock.WANTS,
                        cat.lastMonthAmount(),
                        limit,
                        false,
                        LineReason.PROPORTIONAL
                ));
            }
        } else {
            int count = wantsCats.size();
            BigDecimal equalShare = wantsPlan.divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP);
            for (CategorySpendInput cat : wantsCats) {
                lines.add(new CalculatedLine(
                        cat.categoryId(),
                        cat.categoryName(),
                        BudgetBlock.WANTS,
                        BigDecimal.ZERO,
                        equalShare,
                        false,
                        LineReason.EQUAL_SPLIT
                ));
            }
        }

        return adjustRoundingOnLargestLine(lines, wantsPlan, true);
    }

    private List<CalculatedLine> adjustRoundingOnLargestLine(
            List<CalculatedLine> lines,
            BigDecimal targetTotal,
            boolean anyLineAllowed
    ) {
        if (lines.isEmpty()) {
            return lines;
        }
        BigDecimal currentSum = lines.stream()
                .map(CalculatedLine::limitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diff = targetTotal.subtract(currentSum);
        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            return lines;
        }

        // Encontrar el índice de la línea con mayor limitAmount (prefiriendo no fijas en NEEDS)
        int targetIdx = -1;
        BigDecimal maxAmt = new BigDecimal("-1");
        for (int i = 0; i < lines.size(); i++) {
            CalculatedLine line = lines.get(i);
            if (!anyLineAllowed && line.isFixed()) {
                continue;
            }
            if (line.limitAmount().compareTo(maxAmt) > 0) {
                maxAmt = line.limitAmount();
                targetIdx = i;
            }
        }
        if (targetIdx == -1) {
            targetIdx = lines.stream()
                    .max(Comparator.comparing(CalculatedLine::limitAmount))
                    .map(lines::indexOf)
                    .orElse(0);
        }

        List<CalculatedLine> adjusted = new ArrayList<>(lines);
        CalculatedLine chosen = adjusted.get(targetIdx);
        BigDecimal newLimit = chosen.limitAmount().add(diff).max(BigDecimal.ZERO);
        adjusted.set(targetIdx, new CalculatedLine(
                chosen.categoryId(),
                chosen.categoryName(),
                chosen.block(),
                chosen.lastMonthAmount(),
                newLimit,
                chosen.isFixed(),
                chosen.reason()
        ));
        return adjusted;
    }

    private List<CalculatedWeek> buildWeeks(
            YearMonth targetMonth,
            BigDecimal wantsPlan,
            BigDecimal variableNeedsPlan
    ) {
        int totalDays = targetMonth.lengthOfMonth();
        List<int[]> ranges = new ArrayList<>();
        int startDay = 1;
        while (startDay <= totalDays) {
            int endDay = Math.min(startDay + 6, totalDays);
            ranges.add(new int[]{startDay, endDay});
            startDay = endDay + 1;
        }

        List<CalculatedWeek> weeks = new ArrayList<>();
        BigDecimal accWants = BigDecimal.ZERO;
        BigDecimal accVarNeeds = BigDecimal.ZERO;

        for (int i = 0; i < ranges.size(); i++) {
            int s = ranges.get(i)[0];
            int e = ranges.get(i)[1];
            int daysInSlice = (e - s) + 1;
            boolean isLast = (i == ranges.size() - 1);

            BigDecimal weekWants;
            BigDecimal weekVarNeeds;
            if (!isLast) {
                weekWants = wantsPlan.multiply(BigDecimal.valueOf(daysInSlice))
                        .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP);
                weekVarNeeds = variableNeedsPlan.multiply(BigDecimal.valueOf(daysInSlice))
                        .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP);
                accWants = accWants.add(weekWants);
                accVarNeeds = accVarNeeds.add(weekVarNeeds);
            } else {
                weekWants = wantsPlan.subtract(accWants).max(BigDecimal.ZERO);
                weekVarNeeds = variableNeedsPlan.subtract(accVarNeeds).max(BigDecimal.ZERO);
            }

            weeks.add(new CalculatedWeek(
                    i + 1,
                    targetMonth.atDay(s),
                    targetMonth.atDay(e),
                    daysInSlice,
                    weekWants,
                    weekVarNeeds
            ));
        }

        return weeks;
    }

    private static BigDecimal roundCop(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentageCop(BigDecimal base, int pct) {
        return base.multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private static int percentOfIncome(BigDecimal amount, BigDecimal income) {
        if (income.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return amount.multiply(BigDecimal.valueOf(100))
                .divide(income, 0, RoundingMode.HALF_UP)
                .intValue();
    }
}
