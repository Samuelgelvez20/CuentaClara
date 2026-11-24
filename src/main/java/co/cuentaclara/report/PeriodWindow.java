package co.cuentaclara.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Representa una ventana temporal financiera compatible con cierres mensuales, quincenales (Q1/Q2)
 * y semanales (W1..W5) para informes, snapshots y planes sugeridos (RF-13, RF-16).
 */
public record PeriodWindow(
        String label,
        String scopeCode,
        YearMonth yearMonth,
        LocalDate start,
        LocalDate end,
        String nextLabel,
        YearMonth targetBudgetMonth,
        String spanishTitle,
        String nextSpanishTitle
) {

    private static final Locale ES_CO = Locale.forLanguageTag("es-CO");
    private static final Pattern PERIOD_PATTERN = Pattern.compile("^(\\d{4}-\\d{2})(?:-(Q[12]|W[1-5]))?$", Pattern.CASE_INSENSITIVE);

    public static PeriodWindow parse(String rawPeriodSpec) {
        if (rawPeriodSpec == null || rawPeriodSpec.isBlank()) {
            YearMonth fallback = YearMonth.now();
            return monthly(fallback);
        }
        String trimmed = rawPeriodSpec.trim().toUpperCase(Locale.ROOT);
        Matcher m = PERIOD_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            return monthly(YearMonth.parse(rawPeriodSpec.trim()));
        }
        YearMonth ym = YearMonth.parse(m.group(1));
        String suffix = m.group(2);
        if (suffix == null || suffix.isBlank() || "M".equals(suffix)) {
            return monthly(ym);
        }
        if ("Q1".equals(suffix)) {
            return biweekly(ym, 1);
        }
        if ("Q2".equals(suffix)) {
            return biweekly(ym, 2);
        }
        int week = Integer.parseInt(suffix.substring(1));
        return weekly(ym, week);
    }

    public static PeriodWindow resolve(String monthStr, String periodScope) {
        YearMonth ym = (monthStr != null && !monthStr.isBlank())
                ? parse(monthStr).yearMonth()
                : YearMonth.now().minusMonths(1);
        if (periodScope == null || periodScope.isBlank() || "MONTHLY".equalsIgnoreCase(periodScope)) {
            return monthly(ym);
        }
        String scope = periodScope.trim().toUpperCase(Locale.ROOT);
        if ("Q1".equals(scope) || "Q2".equals(scope) || scope.matches("W[1-5]")) {
            return parse(ym + "-" + scope);
        }
        return monthly(ym);
    }

    private static PeriodWindow monthly(YearMonth ym) {
        YearMonth nextYm = ym.plusMonths(1);
        return new PeriodWindow(
                ym.toString(),
                "MONTHLY",
                ym,
                ym.atDay(1),
                ym.atEndOfMonth(),
                nextYm.toString(),
                nextYm,
                formatMonthSpanish(ym),
                formatMonthSpanish(nextYm)
        );
    }

    private static PeriodWindow biweekly(YearMonth ym, int half) {
        LocalDate start = half == 1 ? ym.atDay(1) : ym.atDay(16);
        LocalDate end = half == 1 ? ym.atDay(15) : ym.atEndOfMonth();
        String nextLabel = half == 1 ? ym + "-Q2" : ym.plusMonths(1) + "-Q1";
        YearMonth targetMonth = half == 1 ? ym : ym.plusMonths(1);
        String nextTitle = half == 1
                ? "2ª Quincena · " + formatMonthSpanish(ym)
                : "1ª Quincena · " + formatMonthSpanish(ym.plusMonths(1));
        return new PeriodWindow(
                ym + "-Q" + half,
                "Q" + half,
                ym,
                start,
                end,
                nextLabel,
                targetMonth,
                half + "ª Quincena · " + formatMonthSpanish(ym),
                nextTitle
        );
    }

    private static PeriodWindow weekly(YearMonth ym, int weekNum) {
        int maxDay = ym.lengthOfMonth();
        int clampedWeek = Math.max(1, Math.min(5, weekNum));
        int startDay = Math.min(((clampedWeek - 1) * 7) + 1, maxDay);
        int endDay = Math.min(startDay + 6, maxDay);
        LocalDate start = ym.atDay(startDay);
        LocalDate end = ym.atDay(endDay);
        boolean hasNextWeekInMonth = endDay < maxDay;
        String nextLabel = hasNextWeekInMonth ? ym + "-W" + (clampedWeek + 1) : ym.plusMonths(1) + "-W1";
        YearMonth targetMonth = hasNextWeekInMonth ? ym : ym.plusMonths(1);
        String nextTitle = hasNextWeekInMonth
                ? "Semana " + (clampedWeek + 1) + " · " + formatMonthSpanish(ym)
                : "Semana 1 · " + formatMonthSpanish(ym.plusMonths(1));
        return new PeriodWindow(
                ym + "-W" + clampedWeek,
                "W" + clampedWeek,
                ym,
                start,
                end,
                nextLabel,
                targetMonth,
                "Semana " + clampedWeek + " (" + startDay + "-" + endDay + ") · " + formatMonthSpanish(ym),
                nextTitle
        );
    }

    public BigDecimal prorateMonthlyAmount(BigDecimal monthlyAmount) {
        if (monthlyAmount == null) {
            return BigDecimal.ZERO;
        }
        if ("MONTHLY".equals(scopeCode)) {
            return monthlyAmount;
        }
        if ("Q1".equals(scopeCode) || "Q2".equals(scopeCode)) {
            return monthlyAmount.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        int totalDays = yearMonth.lengthOfMonth();
        long sliceDays = (end.toEpochDay() - start.toEpochDay()) + 1;
        return monthlyAmount.multiply(BigDecimal.valueOf(sliceDays))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);
    }

    public static String formatMonthSpanish(YearMonth ym) {
        String name = ym.getMonth().getDisplayName(TextStyle.FULL, ES_CO);
        if (name == null || name.isBlank()) {
            return ym.toString();
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + ym.getYear();
    }
}
