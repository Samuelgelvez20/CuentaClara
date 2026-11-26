package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepositoryPort {

    Optional<Document> findById(UUID id);

    Optional<Document> findByUserIdAndSha256Hash(UUID userId, String sha256Hash);

    Optional<Document> findByUserIdAndCufe(UUID userId, String cufe);

    Optional<Document> findByUserIdAndIssuerNitAndInvoiceNumber(UUID userId, String issuerNit, String invoiceNumber);

    List<Document> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Document> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, DocumentStatus status);

    Document save(Document document);

    void delete(Document document);

    void deleteById(UUID id);
}
