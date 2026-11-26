package co.cuentaclara.domain.model;

import java.util.List;

public record BackedRecommendation(
        String block,
        String explanation,
        List<String> backingCitations
) {
}
