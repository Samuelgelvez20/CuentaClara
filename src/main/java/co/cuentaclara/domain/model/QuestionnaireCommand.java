package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record QuestionnaireCommand(
        BigDecimal netIncome,
        PaymentFrequency paymentFrequency,
        String paymentDays,
        int needsPct,
        int wantsPct,
        int savingsPct,
        String financialGoals,
        BigDecimal totalDebts,
        List<ObligationInput> obligations
) {
}
