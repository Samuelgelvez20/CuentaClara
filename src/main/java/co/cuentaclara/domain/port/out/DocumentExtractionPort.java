package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.DocumentSourceType;
import co.cuentaclara.domain.model.ExtractionOutcome;

public interface DocumentExtractionPort {

    ExtractionOutcome extract(DocumentSourceType sourceType, byte[] fileBytes, String originalFilename);
}
