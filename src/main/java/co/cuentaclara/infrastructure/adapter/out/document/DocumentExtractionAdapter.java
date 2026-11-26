package co.cuentaclara.infrastructure.adapter.out.document;

import co.cuentaclara.domain.model.DocumentSourceType;
import co.cuentaclara.domain.model.ExtractionOutcome;
import co.cuentaclara.domain.model.ParsedUblInvoice;
import co.cuentaclara.domain.port.out.DocumentExtractionPort;
import co.cuentaclara.infrastructure.adapter.out.document.parser.DianUblXmlParser;
import co.cuentaclara.infrastructure.adapter.out.document.parser.PdfAndOcrExtractionService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentExtractionAdapter implements DocumentExtractionPort {

    private final DianUblXmlParser ublXmlParser;
    private final PdfAndOcrExtractionService pdfAndOcrExtractionService;

    public DocumentExtractionAdapter(
            DianUblXmlParser ublXmlParser,
            PdfAndOcrExtractionService pdfAndOcrExtractionService
    ) {
        this.ublXmlParser = ublXmlParser;
        this.pdfAndOcrExtractionService = pdfAndOcrExtractionService;
    }

    @Override
    public ExtractionOutcome extract(DocumentSourceType sourceType, byte[] fileBytes, String originalFilename) {
        if (sourceType == DocumentSourceType.XML_UBL) {
            ParsedUblInvoice parsed = ublXmlParser.parse(fileBytes);
            return new ExtractionOutcome(
                    DocumentSourceType.XML_UBL,
                    1,
                    List.of(),
                    parsed,
                    "UBL_2_1_DIRECT"
            );
        }
        return pdfAndOcrExtractionService.extractFromPdfOrImage(fileBytes, sourceType);
    }
}
