package co.cuentaclara.report;

import co.cuentaclara.plan.BudgetPlanCalculationAndPersistenceService;
import co.cuentaclara.profile.Budget;
import co.cuentaclara.profile.BudgetBlock;
import co.cuentaclara.profile.BudgetRepository;
import co.cuentaclara.profile.FinancialProfile;
import co.cuentaclara.profile.FinancialProfileRepository;
import co.cuentaclara.profile.PaymentFrequency;
import co.cuentaclara.transaction.TransactionEntity;
import co.cuentaclara.transaction.TransactionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Motor determinista de Análisis Financiero Mensual / Quincenal y Plan Sugerido en 2 Etapas (RF-13, RF-16, RF-17, RNF-06).
 * Todos los cálculos (ingresos, gastos por bloque 50/30/20 o personalizados, desviaciones y saldos)
 * se ejecutan en SQL y código determinista. Cada recomendación referencia los gastos confirmados que la respaldan.
 */
@Service
public class MonthlyAnalysisAndReportService {

    private final JdbcTemplate jdbcTemplate;
    private final FinancialProfileRepository profileRepository;
    private final BudgetRepository budgetRepository;
    private final MonthlyReportRepository reportRepository;
    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;
    private final BudgetPlanCalculationAndPersistenceService planPersistenceService;

    public MonthlyAnalysisAndReportService(
            JdbcTemplate jdbcTemplate,
            FinancialProfileRepository profileRepository,
            BudgetRepository budgetRepository,
            MonthlyReportRepository reportRepository,
            TransactionService transactionService,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplate, profileRepository, budgetRepository, reportRepository, transactionService, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MonthlyAnalysisAndReportService(
            JdbcTemplate jdbcTemplate,
            FinancialProfileRepository profileRepository,
            BudgetRepository budgetRepository,
            MonthlyReportRepository reportRepository,
            TransactionService transactionService,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            BudgetPlanCalculationAndPersistenceService planPersistenceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileRepository = profileRepository;
        this.budgetRepository = budgetRepository;
        this.reportRepository = reportRepository;
        this.transactionService = transactionService;
        this.objectMapper = objectMapper;
        this.planPersistenceService = planPersistenceService;
    }

    /**
     * Evalúa en SQL el cierre de un periodo (mensual "2026-10", quincenal "2026-10-Q1" o semanal "2026-10-W1"),
     * compara el perfil declarado vs. los movimientos confirmados, genera el informe con citas,
     * construye el plan sugerido y persiste el detalle por categoría y semana vía PlanCalculator (RF-13, RF-16).
     */
    @Transactional
    public PeriodClosureReportView generatePeriodReportAndNextPlan(UUID userId, String periodSpec) {
        PeriodWindow pw = PeriodWindow.parse(periodSpec);
        LocalDate start = pw.start();
        LocalDate end = pw.end();

        FinancialProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultProfileIfMissing(userId));

        BigDecimal proratedDeclaredIncome = pw.prorateMonthlyAmount(profile.getNetIncome());

        // 1. Consultar ingresos registrados en SQL en ese periodo; si no hay registros explícitos, usar el ingreso declarado prorrateado
        BigDecimal recordedIncome = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM incomes WHERE user_id = ? AND received_date >= ? AND received_date <= ?",
                BigDecimal.class,
                userId, Date.valueOf(start), Date.valueOf(end)
        );
        BigDecimal actualIncome = (recordedIncome != null && recordedIncome.compareTo(BigDecimal.ZERO) > 0)
                ? recordedIncome
                : proratedDeclaredIncome;

        // 2. Calcular en SQL los gastos confirmados por bloque presupuestario (NEEDS, WANTS, SAVINGS) (RNF-06)
        BigDecimal needsSpent = sumConfirmedByBlock(userId, start, end, BudgetBlock.NEEDS);
        BigDecimal wantsSpent = sumConfirmedByBlock(userId, start, end, BudgetBlock.WANTS);
        BigDecimal explicitSavingsSpent = sumConfirmedByBlock(userId, start, end, BudgetBlock.SAVINGS);

        BigDecimal totalOutflows = needsSpent.add(wantsSpent);
        BigDecimal netRemaining = actualIncome.subtract(totalOutflows).max(BigDecimal.ZERO);
        BigDecimal savingsAchieved = explicitSavingsSpent.max(netRemaining);

        // 3. Topes objetivo según la regla vigente del usuario (50/30/20 o personalizada que suma 100%)
        BigDecimal needsTarget = percentageOf(actualIncome, profile.getNeedsPct());
        BigDecimal wantsTarget = percentageOf(actualIncome, profile.getWantsPct());
        BigDecimal savingsTarget = percentageOf(actualIncome, profile.getSavingsPct());

        boolean essentialExceeded = needsSpent.compareTo(needsTarget) > 0;

        // 4. Construir recomendaciones deterministas respaldadas por citas verificables a transacciones reales (RF-13)
        List<BackedRecommendation> recommendations = buildEvidenceBackedRecommendations(
                userId,
                start,
                end,
                profile,
                actualIncome,
                needsSpent,
                needsTarget,
                wantsSpent,
                wantsTarget,
                savingsAchieved,
                savingsTarget,
                essentialExceeded
        );

        String verdict = buildVerdictSummary(
                pw.label(),
                proratedDeclaredIncome,
                actualIncome,
                profile.getNeedsPct(),
                profile.getWantsPct(),
                profile.getSavingsPct(),
                needsSpent,
                needsTarget,
                wantsSpent,
                wantsTarget,
                savingsAchieved,
                savingsTarget,
                essentialExceeded
        );

        // 5. Generar Plan Sugerido (OBSERVED_PLAN) para el siguiente periodo basado en comportamiento observado (RF-16)
        PeriodWindow nextPw = PeriodWindow.parse(pw.nextLabel());
        Budget nextPlan = buildNextPeriodSuggestedPlan(
                userId,
                nextPw,
                profile,
                actualIncome,
                needsSpent,
                needsTarget,
                essentialExceeded
        );

        MonthlyReportEntity report = new MonthlyReportEntity();
        report.setUserId(userId);
        report.setPeriodLabel(pw.label());
        report.setPeriodStart(start);
        report.setPeriodEnd(end);
        report.setDeclaredIncome(proratedDeclaredIncome);
        report.setActualIncome(actualIncome);
        report.setNeedsPct(profile.getNeedsPct());
        report.setWantsPct(profile.getWantsPct());
        report.setSavingsPct(profile.getSavingsPct());
        report.setNeedsSpent(needsSpent);
        report.setWantsSpent(wantsSpent);
        report.setSavingsAchieved(savingsAchieved);
        report.setEssentialExceeded(essentialExceeded);
        report.setVerdictSummary(verdict);
        report.setRecommendationsJson(serializeRecommendations(recommendations));
        report.setSuggestedBudgetId(nextPlan.getId());

        MonthlyReportEntity savedReport = reportRepository.save(report);

        BudgetPlanCalculationAndPersistenceService.PersistedPlanView calculatedPlan = null;
        if (planPersistenceService != null) {
            calculatedPlan = planPersistenceService.calculateAndPersistPlan(
                    userId,
                    pw.targetBudgetMonth(),
                    start,
                    end,
                    nextPlan,
                    recommendations
            );
        }

        return new PeriodClosureReportView(
                savedReport,
                recommendations,
                nextPlan,
                calculatedPlan
        );
    }

    /**
     * Permite aceptar, editar o ignorar el plan sugerido para el siguiente periodo sin alterar informes cerrados (RF-16, RF-17).
     */
    @Transactional
    public Budget updateSuggestedPlanDecision(UUID userId, UUID budgetId, PlanDecisionRequest request) {
        Budget budget = budgetRepository.findById(budgetId)
                .orElseThrow(() -> new IllegalArgumentException("Plan presupuestario no encontrado: " + budgetId));

        String decision = request.status() != null ? request.status().trim().toUpperCase() : "ACCEPTED";
        if (!List.of("SUGGESTED", "ACCEPTED", "EDITED", "IGNORED").contains(decision)) {
            throw new IllegalArgumentException("Estado de decisión inválido. Usa ACCEPTED, EDITED o IGNORED.");
        }
        budget.setStatus(decision);

        if ("EDITED".equals(decision) && request.needsPct() != null && request.wantsPct() != null && request.savingsPct() != null) {
            int sum = request.needsPct() + request.wantsPct() + request.savingsPct();
            if (sum != 100) {
                throw new IllegalArgumentException("Los porcentajes editados del plan deben sumar exactamente 100 %.");
            }
            BigDecimal refIncome = request.referenceIncome() != null ? request.referenceIncome() : budget.getReferenceIncome();
            budget.setReferenceIncome(refIncome);
            budget.setNeedsPct(request.needsPct());
            budget.setWantsPct(request.wantsPct());
            budget.setSavingsPct(request.savingsPct());
            budget.setNeedsLimit(percentageOf(refIncome, request.needsPct()));
            budget.setWantsLimit(percentageOf(refIncome, request.wantsPct()));
            budget.setSavingsTarget(percentageOf(refIncome, request.savingsPct()));
        }

        Budget savedBudget = budgetRepository.save(budget);
        if (planPersistenceService != null) {
            planPersistenceService.syncPlanDecision(userId, savedBudget);
        }
        return savedBudget;
    }

    /**
     * Simula el primer mes de demostración con un ingreso simulado y movimientos realistas confirmados,
     * y ejecuta el informe de cierre del periodo (Alcance MVP y Objetivo Específico 5).
     */
    @Transactional
    public PeriodClosureReportView simulateFirstMonthAndGenerateReport(UUID userId, String yearMonthStr) {
        YearMonth ym = (yearMonthStr != null && !yearMonthStr.isBlank())
                ? YearMonth.parse(yearMonthStr.trim())
                : YearMonth.of(2026, 10);

        FinancialProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultProfileIfMissing(userId));

        // Asegurar obligaciones recurrentes para el calendario mensual si aún no existen
        Integer existingObligations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recurring_obligations WHERE user_id = ?",
                Integer.class,
                userId
        );
        if (existingObligations == null || existingObligations == 0) {
            jdbcTemplate.update(
                    "INSERT INTO recurring_obligations (user_id, name, category, budget_block, estimated_amount, due_day, is_essential) VALUES " +
                            "(?, 'Alquiler / Arriendo', 'Vivienda', 'NEEDS', 1450000.00, 5, TRUE), " +
                            "(?, 'Cuota Préstamo / Deuda', 'Deudas y Préstamos', 'NEEDS', 350000.00, 12, TRUE), " +
                            "(?, 'Servicios e Internet', 'Servicios', 'NEEDS', 260000.00, 16, TRUE)",
                    userId, userId, userId
            );
        }

        // Registrar ingreso simulado del mes
        jdbcTemplate.update(
                "DELETE FROM incomes WHERE user_id = ? AND is_simulated = TRUE AND received_date >= ? AND received_date <= ?",
                userId, Date.valueOf(ym.atDay(1)), Date.valueOf(ym.atEndOfMonth())
        );
        jdbcTemplate.update(
                "INSERT INTO incomes (user_id, source_name, amount, received_date, period_type, is_simulated) VALUES (?, ?, ?, ?, ?, TRUE)",
                userId, "Salario Mensual Simulado", profile.getNetIncome(), Date.valueOf(ym.atDay(1)), profile.getPaymentFrequency().name()
        );

        // Si el usuario aún no tiene gastos en ese mes, poblar movimientos representativos confirmados
        Integer existingTxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ? AND transaction_date >= ? AND transaction_date <= ?",
                Integer.class,
                userId, Date.valueOf(ym.atDay(1)), Date.valueOf(ym.atEndOfMonth())
        );

        if (existingTxCount == null || existingTxCount == 0) {
            transactionService.registerManualExpense(
                    userId,
                    new TransactionService.ManualExpenseRequest(
                            "Inmobiliaria Central - Arriendo",
                            ym.atDay(5),
                            new BigDecimal("1450000.00"),
                            "Vivienda",
                            BudgetBlock.NEEDS,
                            "Pago arriendo mensual apartamento"
                    ),
                    null, null
            );
            transactionService.registerManualExpense(
                    userId,
                    new TransactionService.ManualExpenseRequest(
                            "ALMACENES ÉXITO S.A.",
                            ym.atDay(10),
                            new BigDecimal("620000.00"),
                            "Alimentación",
                            BudgetBlock.NEEDS,
                            "Mercado quincenal familiar"
                    ),
                    null, null
            );
            transactionService.registerManualExpense(
                    userId,
                    new TransactionService.ManualExpenseRequest(
                            "EPM Servicios Públicos",
                            ym.atDay(16),
                            new BigDecimal("260000.00"),
                            "Servicios",
                            BudgetBlock.NEEDS,
                            "Energía y acueducto"
                    ),
                    null, null
            );
            transactionService.registerManualExpense(
                    userId,
                    new TransactionService.ManualExpenseRequest(
                            "RAPPI COLOMBIA S.A.S.",
                            ym.atDay(18),
                            new BigDecimal("195000.00"),
                            "Domicilios y Restaurantes",
                            BudgetBlock.WANTS,
                            "Pedidos de comida a domicilio fin de semana"
                    ),
                    null, null
            );
            transactionService.registerManualExpense(
                    userId,
                    new TransactionService.ManualExpenseRequest(
                            "UBER EATS COLOMBIA",
                            ym.atDay(24),
                            new BigDecimal("140000.00"),
                            "Domicilios y Restaurantes",
                            BudgetBlock.WANTS,
                            "Cenas a domicilio"
                    ),
                    null, null
            );
        }

        return generatePeriodReportAndNextPlan(userId, ym.toString());
    }

    @Transactional(readOnly = true)
    public List<MonthlyReportEntity> listReports(UUID userId) {
        return reportRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<MonthlyReportEntity> findLatestReportForPeriod(UUID userId, String periodLabel) {
        return reportRepository.findFirstByUserIdAndPeriodLabelOrderByCreatedAtDesc(userId, periodLabel);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Budget> findSuggestedBudgetById(UUID budgetId) {
        return budgetId != null ? budgetRepository.findById(budgetId) : java.util.Optional.empty();
    }

    @Transactional(readOnly = true)
    public GeminiFinancialAdvisorService.MonthAnalysisSnapshot buildMonthSnapshot(UUID userId, String periodSpec) {
        PeriodWindow pw = PeriodWindow.parse(periodSpec != null && !periodSpec.isBlank()
                ? periodSpec
                : YearMonth.now().minusMonths(1).toString());
        LocalDate start = pw.start();
        LocalDate end = pw.end();

        FinancialProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultProfileIfMissing(userId));

        BigDecimal proratedDeclaredIncome = pw.prorateMonthlyAmount(profile.getNetIncome());

        BigDecimal recordedIncome = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM incomes WHERE user_id = ? AND received_date >= ? AND received_date <= ?",
                BigDecimal.class,
                userId, Date.valueOf(start), Date.valueOf(end)
        );
        BigDecimal actualIncome = (recordedIncome != null && recordedIncome.compareTo(BigDecimal.ZERO) > 0)
                ? recordedIncome
                : proratedDeclaredIncome;

        BigDecimal fixedObligationsMonthly = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(estimated_amount), 0) FROM recurring_obligations WHERE user_id = ?",
                BigDecimal.class,
                userId
        );
        if (fixedObligationsMonthly == null) {
            fixedObligationsMonthly = BigDecimal.ZERO;
        }
        BigDecimal fixedObligationsTotal = pw.prorateMonthlyAmount(fixedObligationsMonthly);

        BigDecimal needsSpent = sumConfirmedByBlock(userId, start, end, BudgetBlock.NEEDS);
        BigDecimal wantsSpent = sumConfirmedByBlock(userId, start, end, BudgetBlock.WANTS);
        BigDecimal explicitSavingsSpent = sumConfirmedByBlock(userId, start, end, BudgetBlock.SAVINGS);

        BigDecimal totalOutflows = needsSpent.add(wantsSpent);
        BigDecimal netRemaining = actualIncome.subtract(totalOutflows).max(BigDecimal.ZERO);
        BigDecimal savingsAchieved = explicitSavingsSpent.max(netRemaining);

        BigDecimal needsTarget = percentageOf(actualIncome, profile.getNeedsPct());
        BigDecimal wantsTarget = percentageOf(actualIncome, profile.getWantsPct());
        BigDecimal savingsTarget = percentageOf(actualIncome, profile.getSavingsPct());
        boolean essentialExceeded = needsSpent.compareTo(needsTarget) > 0;

        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE user_id = ? AND confirmed = TRUE AND transaction_date >= ? AND transaction_date <= ?",
                Integer.class,
                userId, Date.valueOf(start), Date.valueOf(end)
        );

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

        List<String> topNeeds = findTopCitationsForBlock(userId, start, end, BudgetBlock.NEEDS, 5);
        List<String> topWants = findTopCitationsForBlock(userId, start, end, BudgetBlock.WANTS, 5);

        return new GeminiFinancialAdvisorService.MonthAnalysisSnapshot(
                pw.label(),
                actualIncome,
                fixedObligationsTotal,
                needsSpent,
                needsTarget,
                wantsSpent,
                wantsTarget,
                savingsAchieved,
                savingsTarget,
                profile.getNeedsPct(),
                profile.getWantsPct(),
                profile.getSavingsPct(),
                essentialExceeded,
                smallCount,
                smallTotal,
                txCount != null ? txCount : 0,
                topNeeds,
                topWants
        );
    }

    public GeminiFinancialAdvisorService.NextMonthAllocation computeNextMonthAllocation(
            GeminiFinancialAdvisorService.MonthAnalysisSnapshot snapshot,
            Budget nextPlan
    ) {
        PeriodWindow currentPw = PeriodWindow.parse(snapshot.periodLabel());
        BigDecimal indispensable = nextPlan.getNeedsLimit().max(snapshot.fixedObligationsTotal());
        BigDecimal recommendedSavings = nextPlan.getSavingsTarget();
        BigDecimal flexibleWants = nextPlan.getWantsLimit();
        BigDecimal potentialExtraSavings = snapshot.smallExpensesTotal()
                .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);

        return new GeminiFinancialAdvisorService.NextMonthAllocation(
                currentPw.nextLabel(),
                nextPlan.getNeedsPct(),
                nextPlan.getWantsPct(),
                nextPlan.getSavingsPct(),
                indispensable,
                recommendedSavings,
                flexibleWants,
                potentialExtraSavings
        );
    }

    private BigDecimal sumConfirmedByBlock(UUID userId, LocalDate start, LocalDate end, BudgetBlock block) {
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

    private List<BackedRecommendation> buildEvidenceBackedRecommendations(
            UUID userId,
            LocalDate start,
            LocalDate end,
            FinancialProfile profile,
            BigDecimal actualIncome,
            BigDecimal needsSpent,
            BigDecimal needsTarget,
            BigDecimal wantsSpent,
            BigDecimal wantsTarget,
            BigDecimal savingsAchieved,
            BigDecimal savingsTarget,
            boolean essentialExceeded
    ) {
        List<BackedRecommendation> list = new ArrayList<>();

        // 1. Evaluación del bloque de Necesidades (RF-13: nunca recomendar incumplir obligaciones básicas)
        List<String> topNeedsCitations = findTopCitationsForBlock(userId, start, end, BudgetBlock.NEEDS, 3);
        if (essentialExceeded) {
            BigDecimal excess = needsSpent.subtract(needsTarget);
            list.add(new BackedRecommendation(
                    "NEEDS",
                    String.format(
                            "Tus gastos esenciales confirmados ($%,.2f COP) superaron en $%,.2f COP la meta del %d%% ($%,.2f COP). No debes incumplir obligaciones básicas como vivienda, servicios o alimentación: para el siguiente periodo sugerimos reasignar temporalmente el porcentaje hacia Necesidades y contener compras discrecionales.",
                            needsSpent, excess, profile.getNeedsPct(), needsTarget
                    ),
                    topNeedsCitations
            ));
        } else {
            BigDecimal margin = needsTarget.subtract(needsSpent);
            list.add(new BackedRecommendation(
                    "NEEDS",
                    String.format(
                            "Tus gastos esenciales ($%,.2f COP) se mantuvieron dentro del tope del %d%% ($%,.2f COP), con un margen favorable de $%,.2f COP.",
                            needsSpent, profile.getNeedsPct(), needsTarget, margin
                    ),
                    topNeedsCitations
            ));
        }

        // 2. Evaluación del bloque de Deseos / Estilo de vida con evidencia específica
        List<String> topWantsCitations = findTopCitationsForBlock(userId, start, end, BudgetBlock.WANTS, 3);
        if (wantsSpent.compareTo(wantsTarget) > 0) {
            BigDecimal excessWants = wantsSpent.subtract(wantsTarget);
            list.add(new BackedRecommendation(
                    "WANTS",
                    String.format(
                            "El bloque de Deseos ($%,.2f COP) excedió en $%,.2f COP el límite del %d%% ($%,.2f COP). Reducir un 25%% los rubros respaldados en las citas adjuntas liberaría flujo de caja directo para tu meta de ahorro.",
                            wantsSpent, excessWants, profile.getWantsPct(), wantsTarget
                    ),
                    topWantsCitations
            ));
        } else {
            list.add(new BackedRecommendation(
                    "WANTS",
                    String.format(
                            "Tus gastos en Deseos ($%,.2f COP) cumplieron el límite del %d%% ($%,.2f COP). Si deseas acelerar tu meta de ahorro, puedes fijar un tope semanal sobre domicilios y salidas.",
                            wantsSpent, profile.getWantsPct(), wantsTarget
                    ),
                    topWantsCitations
            ));
        }

        // 3. Evaluación de Ahorro y Flujo de Caja según frecuencia de pago (RF-16)
        List<String> savingsCitations = !topWantsCitations.isEmpty() ? topWantsCitations : topNeedsCitations;
        if (savingsAchieved.compareTo(savingsTarget) < 0) {
            BigDecimal gap = savingsTarget.subtract(savingsAchieved);
            list.add(new BackedRecommendation(
                    "SAVINGS",
                    String.format(
                            "El ahorro/disponible del periodo fue $%,.2f COP frente a la meta del %d%% ($%,.2f COP; brecha de $%,.2f COP). Separa el ahorro apenas recibas tu ingreso en las fechas de pago (%s).",
                            savingsAchieved, profile.getSavingsPct(), savingsTarget, gap, profile.getPaymentDays()
                    ),
                    savingsCitations
            ));
        } else {
            list.add(new BackedRecommendation(
                    "SAVINGS",
                    String.format(
                            "¡Meta de ahorro cumplida! Alcanzaste $%,.2f COP frente al objetivo del %d%% ($%,.2f COP) sobre tu ingreso de $%,.2f COP.",
                            savingsAchieved, profile.getSavingsPct(), savingsTarget, actualIncome
                    ),
                    savingsCitations
            ));
        }

        return list;
    }

    private List<String> findTopCitationsForBlock(
            UUID userId,
            LocalDate start,
            LocalDate end,
            BudgetBlock block,
            int limit
    ) {
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

    private Budget buildNextPeriodSuggestedPlan(
            UUID userId,
            PeriodWindow nextPw,
            FinancialProfile profile,
            BigDecimal actualIncome,
            BigDecimal needsSpent,
            BigDecimal needsTarget,
            boolean essentialExceeded
    ) {
        int suggestedNeedsPct = profile.getNeedsPct();
        int suggestedWantsPct = profile.getWantsPct();
        int suggestedSavingsPct = profile.getSavingsPct();

        // Si las necesidades reales superaron el porcentaje objetivo, proponer un ajuste realista sin bajar de obligaciones esenciales
        if (essentialExceeded && actualIncome.compareTo(BigDecimal.ZERO) > 0) {
            int observedNeedsPct = needsSpent.multiply(BigDecimal.valueOf(100))
                    .divide(actualIncome, 0, RoundingMode.CEILING)
                    .intValue();
            int realisticNeeds = Math.min(80, Math.max(suggestedNeedsPct, observedNeedsPct));
            int delta = realisticNeeds - suggestedNeedsPct;
            if (delta > 0) {
                int cutFromWants = Math.min(suggestedWantsPct, delta);
                suggestedNeedsPct = realisticNeeds;
                suggestedWantsPct -= cutFromWants;
                suggestedSavingsPct = 100 - suggestedNeedsPct - suggestedWantsPct;
            }
        }

        Budget plan = new Budget();
        plan.setUserId(userId);
        plan.setPeriodLabel("Plan Sugerido (" + nextPw.label() + ")");
        plan.setPeriodStart(nextPw.start());
        plan.setPeriodEnd(nextPw.end());
        plan.setBudgetStage("OBSERVED_PLAN");
        plan.setStatus("SUGGESTED");
        plan.setReferenceIncome(actualIncome);
        plan.setNeedsPct(suggestedNeedsPct);
        plan.setWantsPct(suggestedWantsPct);
        plan.setSavingsPct(suggestedSavingsPct);
        plan.setNeedsLimit(percentageOf(actualIncome, suggestedNeedsPct));
        plan.setWantsLimit(percentageOf(actualIncome, suggestedWantsPct));
        plan.setSavingsTarget(percentageOf(actualIncome, suggestedSavingsPct));

        return budgetRepository.save(plan);
    }

    private String buildVerdictSummary(
            String period,
            BigDecimal declaredIncome,
            BigDecimal actualIncome,
            int needsPct,
            int wantsPct,
            int savingsPct,
            BigDecimal needsSpent,
            BigDecimal needsTarget,
            BigDecimal wantsSpent,
            BigDecimal wantsTarget,
            BigDecimal savingsAchieved,
            BigDecimal savingsTarget,
            boolean essentialExceeded
    ) {
        return String.format(
                "Cierre del periodo %s (basado en movimientos confirmados en SQL): Ingreso declarado $%,.2f COP vs. Ingreso real $%,.2f COP | Regla evaluada %d/%d/%d | Necesidades: $%,.2f / $%,.2f COP (%s) | Deseos: $%,.2f / $%,.2f COP | Ahorro logrado: $%,.2f / $%,.2f COP.",
                period,
                declaredIncome,
                actualIncome,
                needsPct, wantsPct, savingsPct,
                needsSpent, needsTarget,
                essentialExceeded ? "Excedió el objetivo esencial" : "Dentro del límite",
                wantsSpent, wantsTarget,
                savingsAchieved, savingsTarget
        );
    }

    private FinancialProfile createDefaultProfileIfMissing(UUID userId) {
        FinancialProfile fp = new FinancialProfile();
        fp.setUserId(userId);
        fp.setNetIncome(new BigDecimal("3500000.00"));
        fp.setPaymentFrequency(PaymentFrequency.MONTHLY);
        fp.setPaymentDays("30");
        fp.setNeedsPct(50);
        fp.setWantsPct(30);
        fp.setSavingsPct(20);
        return profileRepository.save(fp);
    }

    private String serializeRecommendations(List<BackedRecommendation> recommendations) {
        try {
            return objectMapper.writeValueAsString(recommendations);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private BigDecimal percentageOf(BigDecimal base, int pct) {
        return base.multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public record BackedRecommendation(
            String block,
            String explanation,
            List<String> backingCitations
    ) {
    }

    public record PeriodClosureReportView(
            MonthlyReportEntity report,
            List<BackedRecommendation> recommendations,
            Budget suggestedNextPlan,
            BudgetPlanCalculationAndPersistenceService.PersistedPlanView calculatedPlan
    ) {
        public PeriodClosureReportView(
                MonthlyReportEntity report,
                List<BackedRecommendation> recommendations,
                Budget suggestedNextPlan
        ) {
            this(report, recommendations, suggestedNextPlan, null);
        }
    }

    public record PlanDecisionRequest(
            String status,
            BigDecimal referenceIncome,
            Integer needsPct,
            Integer wantsPct,
            Integer savingsPct
    ) {
    }
}
