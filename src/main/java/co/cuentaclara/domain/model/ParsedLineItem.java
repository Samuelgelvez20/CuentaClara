package co.cuentaclara.domain.model;

import java.math.BigDecimal;

public record ParsedLineItem(
        String description,
        BigDecimal quantity,
        BigDecimal lineTotal
) {
}
