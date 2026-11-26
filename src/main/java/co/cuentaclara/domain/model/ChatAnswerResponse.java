package co.cuentaclara.domain.model;

import java.math.BigDecimal;
import java.util.List;

public record ChatAnswerResponse(
        String question,
        String answer,
        List<String> citations,
        BigDecimal sqlComputedTotal,
        boolean hasEvidence
) {
}
