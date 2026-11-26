package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DocumentPersistenceAdapter implements DocumentRepositoryPort {

    private final DocumentSpringDataRepository repository;

    public DocumentPersistenceAdapter(DocumentSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Document> findById(UUID id) {
        return repository.findById(id).map(DocumentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Document> findByUserIdAndSha256Hash(UUID userId, String sha256Hash) {
        return repository.findByUserIdAndSha256Hash(userId, sha256Hash).map(DocumentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Document> findByUserIdAndCufe(UUID userId, String cufe) {
        return repository.findByUserIdAndCufe(userId, cufe).map(DocumentPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Document> findByUserIdAndIssuerNitAndInvoiceNumber(UUID userId, String issuerNit, String invoiceNumber) {
        return repository.findByUserIdAndIssuerNitAndInvoiceNumber(userId, issuerNit, invoiceNumber)
                .map(DocumentPersistenceAdapter::toDomain);
    }

    @Override
    public List<Document> findByUserIdOrderByCreatedAtDesc(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DocumentPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<Document> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, DocumentStatus status) {
        return repository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status).stream()
                .map(DocumentPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Document save(Document document) {
        DocumentJpaEntity saved = repository.saveAndFlush(toEntity(document));
        return toDomain(saved);
    }

    @Override
    public void delete(Document document) {
        if (document.getId() != null) {
            repository.deleteById(document.getId());
            repository.flush();
        }
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
        repository.flush();
    }

    static Document toDomain(DocumentJpaEntity entity) {
        Document domain = new Document();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setOriginalFilename(entity.getOriginalFilename());
        domain.setMimeType(entity.getMimeType());
        domain.setSourceType(entity.getSourceType());
        domain.setFileSizeBytes(entity.getFileSizeBytes());
        domain.setSha256Hash(entity.getSha256Hash());
        domain.setCufe(entity.getCufe());
        domain.setInvoiceNumber(entity.getInvoiceNumber());
        domain.setIssuerNit(entity.getIssuerNit());
        domain.setIssuerName(entity.getIssuerName());
        domain.setStatus(entity.getStatus());
        domain.setRetryCount(entity.getRetryCount());
        domain.setTempFilePath(entity.getTempFilePath());
        domain.setTempFileDeleted(entity.isTempFileDeleted());
        domain.setErrorMessage(entity.getErrorMessage());
        domain.setPageCount(entity.getPageCount());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    static DocumentJpaEntity toEntity(Document domain) {
        DocumentJpaEntity entity = new DocumentJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setOriginalFilename(domain.getOriginalFilename());
        entity.setMimeType(domain.getMimeType());
        entity.setSourceType(domain.getSourceType());
        entity.setFileSizeBytes(domain.getFileSizeBytes());
        entity.setSha256Hash(domain.getSha256Hash());
        entity.setCufe(domain.getCufe());
        entity.setInvoiceNumber(domain.getInvoiceNumber());
        entity.setIssuerNit(domain.getIssuerNit());
        entity.setIssuerName(domain.getIssuerName());
        entity.setStatus(domain.getStatus());
        entity.setRetryCount(domain.getRetryCount());
        entity.setTempFilePath(domain.getTempFilePath());
        entity.setTempFileDeleted(domain.isTempFileDeleted());
        entity.setErrorMessage(domain.getErrorMessage());
        entity.setPageCount(domain.getPageCount());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }
}
