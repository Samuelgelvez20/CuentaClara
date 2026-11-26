package co.cuentaclara.domain.model;

import java.util.List;

public record ExtractionOutcome(
        DocumentSourceType resolvedSourceType,
        int pageCount,
        List<PageText> pages,
        ParsedUblInvoice parsedInvoice,
        String extractionMethod
) {
}
