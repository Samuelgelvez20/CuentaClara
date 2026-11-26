package co.cuentaclara.application.service;

import co.cuentaclara.domain.model.Budget;
import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.FinancialProfile;
import co.cuentaclara.domain.model.ObligationInput;
import co.cuentaclara.domain.model.PaymentFrequency;
import co.cuentaclara.domain.model.ProfileWithInitialBudget;
import co.cuentaclara.domain.model.QuestionnaireCommand;
import co.cuentaclara.domain.model.RecurringObligation;
import co.cuentaclara.domain.port.in.FinancialProfileUseCase;
import co.cuentaclara.domain.port.out.BudgetRepositoryPort;
import co.cuentaclara.domain.port.out.FinancialProfileRepositoryPort;
import co.cuentaclara.domain.port.out.RecurringObligationRepositoryPort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FinancialProfileApplicationService implements FinancialProfileUseCase {

    private final FinancialProfileRepositoryPort profileRepository;
    private final RecurringObligationRepositoryPort obligationRepository;
    private final BudgetRepositoryPort budgetRepository;
    private final TransactionRunnerPort transactionRunner;

    public FinancialProfileApplicationService(
            FinancialProfileRepositoryPort profileRepository,
            RecurringObligationRepositoryPort obligationRepository,
            BudgetRepositoryPort budgetRepository,
            TransactionRunnerPort transactionRunner
    ) {
        this.profileRepository = profileRepository;
        this.obligationRepository = obligationRepository;
        this.budgetRepository = budgetRepository;
        this.transactionRunner = transactionRunner;
    }

    @Override
    public ProfileWithInitialBudget saveOrUpdateQuestionnaire(UUID userId, QuestionnaireCommand request) {
        return transactionRunner.runInTransaction(() -> {
            int sumPct = request.needsPct() + request.wantsPct() + request.savingsPct();
            if (sumPct != 100) {
                throw new IllegalArgumentException(
                        "La suma de los porcentajes de Necesidades (" + request.needsPct()
                                + "%), Deseos (" + request.wantsPct()
                                + "%) y Ahorro (" + request.savingsPct()
                                + "%) debe ser exactamente 100 % (actual: " + sumPct + " %)."
                );
            }

            FinancialProfile profile = profileRepository.findByUserId(userId)
                    .orElseGet(() -> {
                        FinancialProfile fp = new FinancialProfile();
                        fp.setUserId(userId);
                        return fp;
                    });

            profile.setNetIncome(request.netIncome());
            profile.setPaymentFrequency(request.paymentFrequency());
            profile.setPaymentDays(request.paymentDays() != null && !request.paymentDays().isBlank() ? request.paymentDays().trim() : "30");
            profile.setNeedsPct(request.needsPct());
            profile.setWantsPct(request.wantsPct());
            profile.setSavingsPct(request.savingsPct());
            profile.setFinancialGoals(request.financialGoals());
            profile.setTotalDebts(request.totalDebts() != null ? request.totalDebts() : BigDecimal.ZERO);
            profile.setQuestionnaireCompleted(true);

            FinancialProfile savedProfile = profileRepository.save(profile);

            List<RecurringObligation> savedObligations = new ArrayList<>();
            if (request.obligations() != null) {
                obligationRepository.deleteByUserId(userId);
                for (ObligationInput item : request.obligations()) {
                    if (item.name() != null && !item.name().isBlank()
                            && item.estimatedAmount() != null
                            && item.estimatedAmount().compareTo(BigDecimal.ZERO) > 0) {
                        RecurringObligation obligation = new RecurringObligation(
                                userId,
                                item.name().trim(),
                                item.category() != null && !item.category().isBlank() ? item.category().trim() : "Vivienda / Servicios",
                                item.budgetBlock() != null ? item.budgetBlock() : BudgetBlock.NEEDS,
                                item.estimatedAmount(),
                                item.dueDay() != null && item.dueDay() >= 1 && item.dueDay() <= 31 ? item.dueDay() : 5,
                                item.essential() == null || item.essential()
                        );
                        savedObligations.add(obligationRepository.save(obligation));
                    }
                }
            } else {
                savedObligations.addAll(obligationRepository.findByUserIdOrderByDueDayAsc(userId));
            }

            Budget initialBudget = buildAndSaveInitialEstimatedBudget(userId, savedProfile);
            List<String> preliminaryRecommendations = buildDeterministicInitialRecommendations(savedProfile, savedObligations, initialBudget);

            return new ProfileWithInitialBudget(
                    savedProfile,
                    savedObligations,
                    initialBudget,
                    preliminaryRecommendations
            );
        });
    }

    @Override
    public Optional<ProfileWithInitialBudget> getCurrentProfileAndBudget(UUID userId) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                profileRepository.findByUserId(userId).map(profile -> {
                    List<RecurringObligation> obligations = obligationRepository.findByUserIdOrderByDueDayAsc(userId);
                    Budget budget = budgetRepository
                            .findFirstByUserIdAndBudgetStageOrderByCreatedAtDesc(userId, "INITIAL_ESTIMATE")
                            .orElseGet(() -> calculateVolatileInitialBudget(profile));
                    List<String> recommendations = buildDeterministicInitialRecommendations(profile, obligations, budget);
                    return new ProfileWithInitialBudget(profile, obligations, budget, recommendations);
                })
        );
    }

    @Override
    public List<RecurringObligation> listObligations(UUID userId) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                obligationRepository.findByUserIdOrderByDueDayAsc(userId)
        );
    }

    @Override
    public RecurringObligation addRecurringObligation(UUID userId, ObligationInput item) {
        return transactionRunner.runInTransaction(() -> {
            if (item.name() == null || item.name().isBlank() || item.estimatedAmount() == null) {
                throw new IllegalArgumentException("El nombre y el monto del compromiso o deuda mensual son obligatorios.");
            }
            int dueDay = item.dueDay() != null && item.dueDay() >= 1 && item.dueDay() <= 31 ? item.dueDay() : 5;
            RecurringObligation obligation = new RecurringObligation(
                    userId,
                    item.name().trim(),
                    item.category() != null && !item.category().isBlank() ? item.category().trim() : "Deudas y Pagos Fijos",
                    item.budgetBlock() != null ? item.budgetBlock() : BudgetBlock.NEEDS,
                    item.estimatedAmount(),
                    dueDay,
                    item.essential() == null || item.essential()
            );
            return obligationRepository.save(obligation);
        });
    }

    @Override
    public void deleteRecurringObligation(UUID userId, UUID obligationId) {
        transactionRunner.runInTransactionVoid(() ->
                obligationRepository.findById(obligationId).ifPresent(ob -> {
                    if (ob.getUserId().equals(userId)) {
                        obligationRepository.delete(ob);
                    }
                })
        );
    }

    private Budget buildAndSaveInitialEstimatedBudget(UUID userId, FinancialProfile profile) {
        YearMonth currentMonth = YearMonth.now();
        LocalDate start = currentMonth.atDay(1);
        LocalDate end = currentMonth.atEndOfMonth();

        Budget budget = budgetRepository
                .findFirstByUserIdAndBudgetStageOrderByCreatedAtDesc(userId, "INITIAL_ESTIMATE")
                .orElseGet(Budget::new);

        budget.setUserId(userId);
        budget.setPeriodLabel("Estimación Inicial (" + currentMonth + ")");
        budget.setPeriodStart(start);
        budget.setPeriodEnd(end);
        budget.setBudgetStage("INITIAL_ESTIMATE");
        budget.setStatus("SUGGESTED");
        budget.setReferenceIncome(profile.getNetIncome());
        budget.setNeedsPct(profile.getNeedsPct());
        budget.setWantsPct(profile.getWantsPct());
        budget.setSavingsPct(profile.getSavingsPct());

        BigDecimal income = profile.getNetIncome();
        budget.setNeedsLimit(percentageOf(income, profile.getNeedsPct()));
        budget.setWantsLimit(percentageOf(income, profile.getWantsPct()));
        budget.setSavingsTarget(percentageOf(income, profile.getSavingsPct()));

        return budgetRepository.save(budget);
    }

    private Budget calculateVolatileInitialBudget(FinancialProfile profile) {
        YearMonth currentMonth = YearMonth.now();
        Budget budget = new Budget();
        budget.setUserId(profile.getUserId());
        budget.setPeriodLabel("Estimación Inicial (" + currentMonth + ")");
        budget.setPeriodStart(currentMonth.atDay(1));
        budget.setPeriodEnd(currentMonth.atEndOfMonth());
        budget.setBudgetStage("INITIAL_ESTIMATE");
        budget.setStatus("SUGGESTED");
        budget.setReferenceIncome(profile.getNetIncome());
        budget.setNeedsPct(profile.getNeedsPct());
        budget.setWantsPct(profile.getWantsPct());
        budget.setSavingsPct(profile.getSavingsPct());
        budget.setNeedsLimit(percentageOf(profile.getNetIncome(), profile.getNeedsPct()));
        budget.setWantsLimit(percentageOf(profile.getNetIncome(), profile.getWantsPct()));
        budget.setSavingsTarget(percentageOf(profile.getNetIncome(), profile.getSavingsPct()));
        return budget;
    }

    private List<String> buildDeterministicInitialRecommendations(
            FinancialProfile profile,
            List<RecurringObligation> obligations,
            Budget budget
    ) {
        List<String> notes = new ArrayList<>();
        notes.add("Este presupuesto se calcula con tus datos actuales y se irá ajustando conforme registres los gastos del mes.");

        BigDecimal essentialDeclared = obligations.stream()
                .filter(RecurringObligation::isEssential)
                .map(RecurringObligation::getEstimatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (essentialDeclared.compareTo(budget.getNeedsLimit()) > 0) {
            BigDecimal excess = essentialDeclared.subtract(budget.getNeedsLimit());
            notes.add(String.format(
                    "Tus pagos fijos esenciales ($%,.0f COP) superan en $%,.0f COP el monto asignado a Necesidades (%d%% = $%,.0f COP). Puedes compensarlo reduciendo el porcentaje de Deseos (%d%%) o ajustando la distribución.",
                    essentialDeclared, excess, profile.getNeedsPct(), budget.getNeedsLimit(), profile.getWantsPct()
            ));
        } else {
            BigDecimal remainingNeedsMargin = budget.getNeedsLimit().subtract(essentialDeclared);
            notes.add(String.format(
                    "Tus pagos fijos suman $%,.0f COP, dejando un margen de $%,.0f COP dentro de Necesidades (%d%%) para alimentación y transporte.",
                    essentialDeclared, remainingNeedsMargin, profile.getNeedsPct()
            ));
        }

        if (profile.getPaymentFrequency() == PaymentFrequency.BIWEEKLY) {
            BigDecimal firstHalfObligations = obligations.stream()
                    .filter(o -> o.getDueDay() <= 15)
                    .map(RecurringObligation::getEstimatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal secondHalfObligations = obligations.stream()
                    .filter(o -> o.getDueDay() > 15)
                    .map(RecurringObligation::getEstimatedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            notes.add(String.format(
                    "Flujo de caja quincenal (días de pago %s): Entre los días 1 y 15 vencen obligaciones por $%,.2f COP, y del día 16 a fin de mes vencen $%,.2f COP. Reserva liquidez según las fechas reales de vencimiento en lugar de partir la quincena en mitades iguales.",
                    profile.getPaymentDays(), firstHalfObligations, secondHalfObligations
            ));
        } else if (profile.getPaymentFrequency() == PaymentFrequency.VARIABLE) {
            BigDecimal conservativeIncome = profile.getNetIncome().multiply(new BigDecimal("0.85")).setScale(2, RoundingMode.HALF_UP);
            notes.add(String.format(
                    "Ingreso variable detectado: Te recomendamos operar con un escenario conservador del 85%% ($%,.2f COP) para cubrir obligaciones fijas y destinar cualquier excedente al fondo de estabilidad.",
                    conservativeIncome
            ));
        }

        return notes;
    }

    private BigDecimal percentageOf(BigDecimal amount, int pct) {
        return amount
                .multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
