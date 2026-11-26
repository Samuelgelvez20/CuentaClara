package co.cuentaclara.infrastructure.adapter.out.persistence.jpa;

import co.cuentaclara.domain.model.DocumentExtraction;
import co.cuentaclara.domain.port.out.DocumentExtractionRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DocumentExtractionPersistenceAdapter implements DocumentExtractionRepositoryPort {

    private final DocumentExtractionSpringDataRepository repository;

    public DocumentExtractionPersistenceAdapter(DocumentExtractionSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<DocumentExtraction> findByDocumentId(UUID documentId) {
        return repository.findByDocumentId(documentId).map(DocumentExtractionPersistenceAdapter::toDomain);
    }

    @Override
    public DocumentExtraction save(DocumentExtraction extraction) {
        DocumentExtractionJpaEntity saved = repository.save(toEntity(extraction));
        return toDomain(saved);
    }

    @Override
    public void delete(DocumentExtraction extraction) {
        if (extraction.getId() != null) {
            repository.deleteById(extraction.getId());
        }
    }

    @Override
    public void flush() {
        repository.flush();
    }

    static DocumentExtraction toDomain(DocumentExtractionJpaEntity entity) {
        DocumentExtraction domain = new DocumentExtraction();
        domain.setId(entity.getId());
        domain.setUserId(entity.getUserId());
        domain.setDocumentId(entity.getDocumentId());
        domain.setMerchantName(entity.getMerchantName());
        domain.setMerchantNit(entity.getMerchantNit());
        domain.setInvoiceNumber(entity.getInvoiceNumber());
        domain.setCufe(entity.getCufe());
        domain.setIssueDate(entity.getIssueDate());
        domain.setSubtotalAmount(entity.getSubtotalAmount());
        domain.setTaxAmount(entity.getTaxAmount());
        domain.setDiscountAmount(entity.getDiscountAmount());
        domain.setTotalAmount(entity.getTotalAmount());
        domain.setSuggestedCategory(entity.getSuggestedCategory());
        domain.setSuggestedBudgetBlock(entity.getSuggestedBudgetBlock());
        domain.setItemsJson(entity.getItemsJson());
        domain.setRawExtractedText(entity.getRawExtractedText());
        domain.setExtractionMethod(entity.getExtractionMethod());
        domain.setConfirmedByUser(entity.isConfirmedByUser());
        domain.setConfirmedAt(entity.getConfirmedAt());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }

    static DocumentExtractionJpaEntity toEntity(DocumentExtraction domain) {
        DocumentExtractionJpaEntity entity = new DocumentExtractionJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setDocumentId(domain.getDocumentId());
        entity.setMerchantName(domain.getMerchantName());
        entity.setMerchantNit(domain.getMerchantNit());
        entity.setInvoiceNumber(domain.getInvoiceNumber());
        entity.setCufe(domain.getCufe());
        entity.setIssueDate(domain.getIssueDate());
        entity.setSubtotalAmount(domain.getSubtotalAmount());
        entity.setTaxAmount(domain.getTaxAmount());
        entity.setDiscountAmount(domain.getDiscountAmount());
        entity.setTotalAmount(domain.getTotalAmount());
        entity.setSuggestedCategory(domain.getSuggestedCategory());
        entity.setSuggestedBudgetBlock(domain.getSuggestedBudgetBlock());
        entity.setItemsJson(domain.getItemsJson());
        entity.setRawExtractedText(domain.getRawExtractedText());
        entity.setExtractionMethod(domain.getExtractionMethod());
        entity.setConfirmedByUser(domain.isConfirmedByUser());
        entity.setConfirmedAt(domain.getConfirmedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }
}
