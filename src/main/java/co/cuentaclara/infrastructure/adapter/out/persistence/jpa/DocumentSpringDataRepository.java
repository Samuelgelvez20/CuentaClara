package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.DocumentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSpringDataRepository extends JpaRepository<DocumentJpaEntity, UUID> {

    Optional<DocumentJpaEntity> findByUserIdAndSha256Hash(UUID userId, String sha256Hash);

    Optional<DocumentJpaEntity> findByUserIdAndCufe(UUID userId, String cufe);

    Optional<DocumentJpaEntity> findByUserIdAndIssuerNitAndInvoiceNumber(UUID userId, String issuerNit, String invoiceNumber);

    List<DocumentJpaEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<DocumentJpaEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, DocumentStatus status);
}
