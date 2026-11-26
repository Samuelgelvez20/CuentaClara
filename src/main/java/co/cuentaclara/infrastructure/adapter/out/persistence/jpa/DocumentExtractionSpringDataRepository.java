package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentExtractionSpringDataRepository extends JpaRepository<DocumentExtractionJpaEntity, UUID> {

    Optional<DocumentExtractionJpaEntity> findByDocumentId(UUID documentId);
}
