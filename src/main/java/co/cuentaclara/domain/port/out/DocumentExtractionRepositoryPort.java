package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.DocumentExtraction;
import java.util.Optional;
import java.util.UUID;

public interface DocumentExtractionRepositoryPort {

    Optional<DocumentExtraction> findByDocumentId(UUID documentId);

    DocumentExtraction save(DocumentExtraction extraction);

    void delete(DocumentExtraction extraction);

    void flush();
}
