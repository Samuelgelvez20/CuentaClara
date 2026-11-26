package co.cuentaclara.domain.port.in;

import co.cuentaclara.domain.model.DocumentStatusView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentIngestionUseCase {

    DocumentStatusView uploadAndProcessDocument(
            UUID userId,
            String originalFilename,
            byte[] fileBytes,
            boolean isStatement,
            boolean asyncExecution
    );

    Optional<DocumentStatusView> getDocumentStatus(UUID documentId);

    List<DocumentStatusView> listUserDocuments(UUID userId);

    void cancelPendingDocument(UUID userId, UUID documentId);

    void processQueuedDocument(UUID userId, UUID documentId);
}
