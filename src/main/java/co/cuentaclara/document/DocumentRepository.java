package co.cuentaclara.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    Optional<DocumentEntity> findByUserIdAndSha256Hash(UUID userId, String sha256Hash);

    Optional<DocumentEntity> findByUserIdAndCufe(UUID userId, String cufe);

    Optional<DocumentEntity> findByUserIdAndIssuerNitAndInvoiceNumber(UUID userId, String issuerNit, String invoiceNumber);

    List<DocumentEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<DocumentEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, DocumentStatus status);
}
