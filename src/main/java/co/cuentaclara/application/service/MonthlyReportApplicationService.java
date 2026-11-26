package co.cuentaclara.application.service;

import co.cuentaclara.domain.model.BackedRecommendation;
import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.FinancialProfile;
import co.cuentaclara.domain.model.ManualExpenseCommand;
import co.cuentaclara.domain.model.MonthAnalysisSnapshot;
import co.cuentaclara.domain.model.MonthlyReport;
import co.cuentaclara.domain.model.NarrativeOutput;
import co.cuentaclara.domain.model.NextMonthAllocation;
import co.cuentaclara.domain.model.PaymentFrequency;
import co.cuentaclara.domain.model.PeriodClosureReportView;
import co.cuentaclara.domain.model.PeriodWindow;
import co.cuentaclara.domain.model.PersistedPlanView;
import co.cuentaclara.domain.model.PlanDecisionRequest;
import co.cuentaclara.domain.model.RecurringObligation;
import co.cuentaclara.domain.port.in.MonthlyReportUseCase;
import co.cuentaclara.domain.port.in.TransactionUseCase;
import co.cuentaclara.domain.port.out.BudgetPlanPersistencePort;
import co.cuentaclara.domain.port.out.BudgetRepositoryPort;
import co.cuentaclara.domain.port.out.FinancialAdvisorAiPort;
import co.cuentaclara.domain.port.out.FinancialProfileRepositoryPort;
import co.cuentaclara.domain.port.out.JsonSerializationPort;
import co.cuentaclara.domain.port.out.MonthlyReportRepositoryPort;
import co.cuentaclara.domain.port.out.RecurringObligationRepositoryPort;
import co.cuentaclara.domain.port.out.TransactionAnalyticsQueryPort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MonthlyReportApplicationService implements MonthlyReportUseCase {

    private final FinancialProfileRepositoryPort profileRepository;
    private final RecurringObligationRepositoryPort obligationRepository;
    private final BudgetRepositoryPort budgetRepository;
    private final MonthlyReportRepositoryPort reportRepository;
    private final TransactionAnalyticsQueryPort analyticsQueryPort;
    private final BudgetPlanPersistencePort budgetPlanPersistencePort;
    private final FinancialAdvisorAiPort advisorAiPort;
    private final JsonSerializationPort jsonSerializationPort;
    private final TransactionUseCase transactionUseCase;
    private final TransactionRunnerPort transactionRunner;

    public MonthlyReportApplicationService(
            FinancialProfileRepositoryPort profileRepository,
            RecurringObligationRepositoryPort obligationRepository,
            BudgetRepositoryPort budgetRepository,
            MonthlyReportRepositoryPort reportRepository,
            TransactionAnalyticsQueryPort analyticsQueryPort,
            BudgetPlanPersistencePort budgetPlanPersistencePort,
            FinancialAdvisorAiPort advisorAiPort,
            JsonSerializationPort jsonSerializationPort,
            TransactionUseCase transactionUseCase,
            TransactionRunnerPort transactionRunner
    ) {
        this.profileRepository = profileRepository;
        this.obligationRepository = obligationRepository;
        this.budgetRepository = budgetRepository;
        this.reportRepository = reportRepository;
        this.analyticsQueryPort = analyticsQueryPort;
        this.budgetPlanPersistencePort = budgetPlanPersistencePort;
        this.advisorAiPort = advisorAiPort;
        this.jsonSerializationPort = jsonSerializationPort;
        this.transactionUseCase = transactionUseCase;
        this.transactionRunner = transactionRunner;
    }

    @Override
    public PeriodClosureReportView generatePeriodReportAndNextPlan(UUID userId, String periodSpec) {
        return transactionRunner.runInTransaction(() -> {
            PeriodWindow pw = PeriodWindow.parse(periodSpec);
            LocalDate start = pw.start();
            LocalDate end = pw.end();

            FinancialProfile profile = profileRepository.findByUserId(userId)
                    .orElseGet(() -> createDefaultProfileIfMissing(userId));

            BigDecimal proratedDeclaredIncome = pw.prorateMonthlyAmount(profile.getNetIncome());
            BigDecimal recordedIncome = analyticsQueryPort.sumRecordedIncomeBetween(userId, start, end);
            BigDecimal actualIncome = (recordedIncome != null && recordedIncome.compareTo(BigDecimal.ZERO) > 0)
                    ? recordedIncome
                    : proratedDeclaredIncome;

            BigDecimal needsSpent = analyticsQueryPort.sumConfirmedExpensesByBlock(userId, start, end, BudgetBlock.NEEDS);
            BigDecimal wantsSpent = analyticsQueryPort.sumConfirmedExpensesByBlock(userId, start, end, BudgetBlock.WANTS);
            BigDecimal explicitSavingsSpent = analyticsQueryPort.sumConfirmedExpensesByBlock(userId, start, end, BudgetBlock.SAVINGS);

            BigDecimal totalOutflows = needsSpent.add(wantsSpent);
            BigDecimal netRemaining = actualIncome.subtract(totalOutflows).max(BigDecimal.ZERO);
            BigDecimal savingsAchieved = explicitSavingsSpent.max(netRemaining);

            BigDecimal needsTarget = percentageOf(actualIncome, profile.getNeedsPct());
            BigDecimal wantsTarget = percentageOf(actualIncome, profile.getWantsPct());
            BigDecimal savingsTarget = percentageOf(actualIncome, profile.getSavingsPct());
            boolean essentialExceeded = needsSpent.compareTo(needsTarget) > 0;

            List<BackedRecommendation> recommendations = buildEvidenceBackedRecommendations(
                    userId, start, end, profile, actualIncome,
                    needsSpent, needsTarget, wantsSpent, wantsTarget,
                    savingsAchieved, savingsTarget, essentialExceeded
            );

            String verdict = buildVerdictSummary(
                    pw.label(), proratedDeclaredIncome, actualIncome,
                    profile.getNeedsPct(), profile.getWantsPct(), profile.getSavingsPct(),
                    needsSpent, needsTarget, wantsSpent, wantsTarget,
                    savingsAchieved, savingsTarget, essentialExceeded
            );

            PeriodWindow nextPw = PeriodWindow.parse(pw.nextLabel());
            Budget nextPlan = buildNextPeriodSuggestedPlan(
                    userId, nextPw, profile, actualIncome, needsSpent, essentialExceeded
            );

            MonthlyReport report = new MonthlyReport();
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
            report.setRecommendationsJson(jsonSerializationPort.serializeRecommendations(recommendations));
            report.setSuggestedBudgetId(nextPlan.getId());

            MonthlyReport savedReport = reportRepository.save(report);

            PersistedPlanView calculatedPlan = budgetPlanPersistencePort != null
                    ? budgetPlanPersistencePort.calculateAndPersistPlan(
                            userId, pw.targetBudgetMonth(), start, end, nextPlan, recommendations)
                    : null;

            return new PeriodClosureReportView(savedReport, recommendations, nextPlan, calculatedPlan);
        });
    }

    @Override
    public Budget updateSuggestedPlanDecision(UUID userId, UUID budgetId, PlanDecisionRequest request) {
        return transactionRunner.runInTransaction(() -> {
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
            if (budgetPlanPersistencePort != null) {
                budgetPlanPersistencePort.syncPlanDecision(userId, savedBudget);
            }
            return savedBudget;
        });
    }

    @Override
    public PeriodClosureReportView simulateFirstMonthAndGenerateReport(UUID userId, String yearMonthStr) {
        return transactionRunner.runInTransaction(() -> {
            YearMonth ym = (yearMonthStr != null && !yearMonthStr.isBlank())
                    ? YearMonth.parse(yearMonthStr.trim())
                    : YearMonth.of(2026, 10);

            FinancialProfile profile = profileRepository.findByUserId(userId)
                    .orElseGet(() -> createDefaultProfileIfMissing(userId));

            if (obligationRepository.findByUserIdOrderByDueDayAsc(userId).isEmpty()) {
                obligationRepository.save(new RecurringObligation(userId, "Alquiler / Arriendo", "Vivienda", BudgetBlock.NEEDS, new BigDecimal("1450000.00"), 5, true));
                obligationRepository.save(new RecurringObligation(userId, "Cuota Préstamo / Deuda", "Deudas y Préstamos", BudgetBlock.NEEDS, new BigDecimal("350000.00"), 12, true));
                obligationRepository.save(new RecurringObligation(userId, "Servicios e Internet", "Servicios", BudgetBlock.NEEDS, new BigDecimal("260000.00"), 16, true));
            }

            analyticsQueryPort.replaceSimulatedIncome(
                    userId, ym.atDay(1), ym.atEndOfMonth(), profile.getNetIncome(), profile.getPaymentFrequency()
            );

            if (analyticsQueryPort.countAllTransactionsBetween(userId, ym.atDay(1), ym.atEndOfMonth()) == 0) {
                transactionUseCase.registerManualExpense(userId, new ManualExpenseCommand("Inmobiliaria Central - Arriendo", ym.atDay(5), new BigDecimal("1450000.00"), "Vivienda", BudgetBlock.NEEDS, "Pago arriendo mensual apartamento"), null, null);
                transactionUseCase.registerManualExpense(userId, new ManualExpenseCommand("ALMACENES ÉXITO S.A.", ym.atDay(10), new BigDecimal("620000.00"), "Alimentación", BudgetBlock.NEEDS, "Mercado quincenal familiar"), null, null);
                transactionUseCase.registerManualExpense(userId, new ManualExpenseCommand("EPM Servicios Públicos", ym.atDay(16), new BigDecimal("260000.00"), "Servicios", BudgetBlock.NEEDS, "Energía y acueducto"), null, null);
                transactionUseCase.registerManualExpense(userId, new ManualExpenseCommand("RAPPI COLOMBIA S.A.S.", ym.atDay(18), new BigDecimal("195000.00"), "Domicilios y Restaurantes", BudgetBlock.WANTS, "Pedidos de comida a domicilio fin de semana"), null, null);
                transactionUseCase.registerManualExpense(userId, new ManualExpenseCommand("UBER EATS COLOMBIA", ym.atDay(24), new BigDecimal("140000.00"), "Domicilios y Restaurantes", BudgetBlock.WANTS, "Cenas a domicilio"), null, null);
            }

            return generatePeriodReportAndNextPlan(userId, ym.toString());
        });
    }

    @Override
    public List<MonthlyReport> listReports(UUID userId) {
        return transactionRunner.runInReadOnlyTransaction(() -> reportRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @Override
    public Optional<MonthlyReport> findLatestReportForPeriod(UUID userId, String periodLabel) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                reportRepository.findFirstByUserIdAndPeriodLabelOrderByCreatedAtDesc(userId, periodLabel)
        );
    }

    @Override
    public Optional<Budget> findSuggestedBudgetById(UUID budgetId) {
        return transactionRunner.runInReadOnlyTransaction(() -> budgetRepository.findById(budgetId));
    }

    @Override
    public MonthAnalysisSnapshot buildMonthSnapshot(UUID userId, String periodSpec) {
        return transactionRunner.runInReadOnlyTransaction(() -> {
            PeriodWindow pw = PeriodWindow.parse(periodSpec != null && !periodSpec.isBlank()
                    ? periodSpec
                    : YearMonth.now().minusMonths(1).toString());
            LocalDate start = pw.start();
            LocalDate end = pw.end();

            FinancialProfile profile = profileRepository.findByUserId(userId)
                    .orElseGet(() -> createDefaultProfileIfMissing(userId));

            BigDecimal proratedDeclaredIncome = pw.prorateMonthlyAmount(profile.getNetIncome());
            BigDecimal recordedIncome = analyticsQueryPort.sumRecordedIncomeBetween(userId, start, end);
            BigDecimal actualIncome = (recordedIncome != null && recordedIncome.compareTo(BigDecimal.ZERO) > 0)
                    ? recordedIncome
                    : proratedDeclaredIncome;

            BigDecimal fixedObligationsMonthly = analyticsQueryPort.sumRecurringObligationsMonthly(userId);
            BigDecimal fixedObligationsTotal = pw.prorateMonthlyAmount(fixedObligationsMonthly);

            BigDecimal needsSpent = analyticsQueryPort.sumConfirmedExpensesByBlock(userId, start, end, BudgetBlock.NEEDS);
            BigDecimal wantsSpent = analyticsQueryPort.sumConfirmedExpensesByBlock(userId, start, end, BudgetBlock.WANTS);
            BigDecimal explicitSavingsSpent = analyticsQueryPort.sumConfirmedExpensesByBlock(userId, start, end, BudgetBlock.SAVINGS);

            BigDecimal totalOutflows = needsSpent.add(wantsSpent);
            BigDecimal netRemaining = actualIncome.subtract(totalOutflows).max(BigDecimal.ZERO);
            BigDecimal savingsAchieved = explicitSavingsSpent.max(netRemaining);

            BigDecimal needsTarget = percentageOf(actualIncome, profile.getNeedsPct());
            BigDecimal wantsTarget = percentageOf(actualIncome, profile.getWantsPct());
            BigDecimal savingsTarget = percentageOf(actualIncome, profile.getSavingsPct());
            boolean essentialExceeded = needsSpent.compareTo(needsTarget) > 0;

            int txCount = analyticsQueryPort.countConfirmedTransactionsBetween(userId, start, end);
            TransactionAnalyticsQueryPort.SmallExpensesStats smallStats = analyticsQueryPort.querySmallWantsExpensesStats(userId, start, end);
            List<String> topNeeds = analyticsQueryPort.findTopCitationsForBlock(userId, start, end, BudgetBlock.NEEDS, 5);
            List<String> topWants = analyticsQueryPort.findTopCitationsForBlock(userId, start, end, BudgetBlock.WANTS, 5);

            return new MonthAnalysisSnapshot(
                    pw.label(), actualIncome, fixedObligationsTotal,
                    needsSpent, needsTarget, wantsSpent, wantsTarget,
                    savingsAchieved, savingsTarget,
                    profile.getNeedsPct(), profile.getWantsPct(), profile.getSavingsPct(),
                    essentialExceeded, smallStats.count(), smallStats.totalAmount(),
                    txCount, topNeeds, topWants
            );
        });
    }

    @Override
    public NextMonthAllocation computeNextMonthAllocation(MonthAnalysisSnapshot snapshot, Budget nextPlan) {
        PeriodWindow currentPw = PeriodWindow.parse(snapshot.periodLabel());
        BigDecimal indispensable = nextPlan.getNeedsLimit().max(snapshot.fixedObligationsTotal());
        BigDecimal recommendedSavings = nextPlan.getSavingsTarget();
        BigDecimal flexibleWants = nextPlan.getWantsLimit();
        BigDecimal potentialExtraSavings = snapshot.smallExpensesTotal()
                .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);

        return new NextMonthAllocation(
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

    @Override
    public Optional<PersistedPlanView> findPlanForTargetMonth(UUID userId, YearMonth targetMonth) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                budgetPlanPersistencePort != null
                        ? budgetPlanPersistencePort.findPlanForTargetMonth(userId, targetMonth)
                        : Optional.empty()
        );
    }

    @Override
    public boolean isAiAdvisorConfigured() {
        return advisorAiPort != null && advisorAiPort.isGeminiConfigured();
    }

    @Override
    public String getAiAdvisorModelName() {
        return advisorAiPort != null ? advisorAiPort.getActiveModelName() : "Determinista";
    }

    @Override
    public NarrativeOutput generateMonthSummaryNarrative(MonthAnalysisSnapshot snapshot) {
        return advisorAiPort.generateMonthSummary(snapshot);
    }

    @Override
    public NarrativeOutput generateNextMonthNarrative(MonthAnalysisSnapshot snapshot, NextMonthAllocation nextPlan) {
        return advisorAiPort.generateNextMonthRecommendation(snapshot, nextPlan);
    }

    private List<BackedRecommendation> buildEvidenceBackedRecommendations(
            UUID userId, LocalDate start, LocalDate end, FinancialProfile profile,
            BigDecimal actualIncome, BigDecimal needsSpent, BigDecimal needsTarget,
            BigDecimal wantsSpent, BigDecimal wantsTarget,
            BigDecimal savingsAchieved, BigDecimal savingsTarget, boolean essentialExceeded
    ) {
        List<BackedRecommendation> list = new ArrayList<>();
        List<String> topNeedsCitations = analyticsQueryPort.findTopCitationsForBlock(userId, start, end, BudgetBlock.NEEDS, 3);
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

        List<String> topWantsCitations = analyticsQueryPort.findTopCitationsForBlock(userId, start, end, BudgetBlock.WANTS, 3);
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

    private Budget buildNextPeriodSuggestedPlan(
            UUID userId, PeriodWindow nextPw, FinancialProfile profile,
            BigDecimal actualIncome, BigDecimal needsSpent, boolean essentialExceeded
    ) {
        int suggestedNeedsPct = profile.getNeedsPct();
        int suggestedWantsPct = profile.getWantsPct();
        int suggestedSavingsPct = profile.getSavingsPct();

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
            String period, BigDecimal declaredIncome, BigDecimal actualIncome,
            int needsPct, int wantsPct, int savingsPct,
            BigDecimal needsSpent, BigDecimal needsTarget,
            BigDecimal wantsSpent, BigDecimal wantsTarget,
            BigDecimal savingsAchieved, BigDecimal savingsTarget, boolean essentialExceeded
    ) {
        return String.format(
                "Cierre del periodo %s (basado en movimientos confirmados en SQL): Ingreso declarado $%,.2f COP vs. Ingreso real $%,.2f COP | Regla evaluada %d/%d/%d | Necesidades: $%,.2f / $%,.2f COP (%s) | Deseos: $%,.2f / $%,.2f COP | Ahorro logrado: $%,.2f / $%,.2f COP.",
                period, declaredIncome, actualIncome, needsPct, wantsPct, savingsPct,
                needsSpent, needsTarget, essentialExceeded ? "Excedió el objetivo esencial" : "Dentro del límite",
                wantsSpent, wantsTarget, savingsAchieved, savingsTarget
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

    private BigDecimal percentageOf(BigDecimal base, int pct) {
        return base.multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
