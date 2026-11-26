package co.cuentaclara.application.service;

import co.cuentaclara.domain.exception.DuplicateInvoiceException;
import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentExtraction;
import co.cuentaclara.domain.model.DocumentSourceType;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.model.DocumentStatusView;
import co.cuentaclara.domain.model.ExtractionOutcome;
import co.cuentaclara.domain.model.ParsedUblInvoice;
import co.cuentaclara.domain.model.ValidatedFile;
import co.cuentaclara.domain.port.in.DocumentIngestionUseCase;
import co.cuentaclara.domain.port.out.DocumentExtractionPort;
import co.cuentaclara.domain.port.out.DocumentExtractionRepositoryPort;
import co.cuentaclara.domain.port.out.DocumentJobQueuePort;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import co.cuentaclara.domain.port.out.FileSecurityValidatorPort;
import co.cuentaclara.domain.port.out.TempFileStoragePort;
import co.cuentaclara.domain.port.out.TenantExecutionPort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DocumentIngestionApplicationService implements DocumentIngestionUseCase {

    public static final int MAX_RETRIES = 3;

    private final FileSecurityValidatorPort fileValidator;
    private final DocumentRepositoryPort documentRepository;
    private final DocumentExtractionRepositoryPort extractionRepository;
    private final TempFileStoragePort tempFileStorage;
    private final DocumentExtractionPort documentExtractor;
    private final DocumentJobQueuePort jobQueuePort;
    private final TenantExecutionPort tenantExecutionPort;
    private final TransactionRunnerPort transactionRunner;

    public DocumentIngestionApplicationService(
            FileSecurityValidatorPort fileValidator,
            DocumentRepositoryPort documentRepository,
            DocumentExtractionRepositoryPort extractionRepository,
            TempFileStoragePort tempFileStorage,
            DocumentExtractionPort documentExtractor,
            DocumentJobQueuePort jobQueuePort,
            TenantExecutionPort tenantExecutionPort,
            TransactionRunnerPort transactionRunner
    ) {
        this.fileValidator = fileValidator;
        this.documentRepository = documentRepository;
        this.extractionRepository = extractionRepository;
        this.tempFileStorage = tempFileStorage;
        this.documentExtractor = documentExtractor;
        this.jobQueuePort = jobQueuePort;
        this.tenantExecutionPort = tenantExecutionPort;
        this.transactionRunner = transactionRunner;
    }

    @Override
    public DocumentStatusView uploadAndProcessDocument(
            UUID userId,
            String originalFilename,
            byte[] fileBytes,
            boolean isStatement,
            boolean asyncExecution
    ) {
        ValidatedFile validated = fileValidator.inspectAndValidate(originalFilename, fileBytes, isStatement);

        documentRepository.findByUserIdAndSha256Hash(userId, validated.sha256Hash())
                .ifPresent(existing -> {
                    throw new DuplicateInvoiceException(
                            "Este archivo ya fue cargado anteriormente (duplicado detectado por hash SHA-256)."
                    );
                });

        String savedTempPath = tempFileStorage.storeUserTempFile(userId, validated.originalFilename(), fileBytes);

        Document doc = transactionRunner.runInTransaction(() -> {
            Document pending = new Document();
            pending.setUserId(userId);
            pending.setOriginalFilename(validated.originalFilename());
            pending.setMimeType(validated.mimeType());
            pending.setSourceType(validated.sourceType());
            pending.setFileSizeBytes(validated.fileSizeBytes());
            pending.setSha256Hash(validated.sha256Hash());
            pending.setStatus(DocumentStatus.PENDING);
            pending.setTempFilePath(savedTempPath);
            pending.setTempFileDeleted(false);
            return documentRepository.save(pending);
        });

        if (asyncExecution) {
            boolean enqueuedInRedis = jobQueuePort.enqueueJob(userId, doc.getId());
            if (!enqueuedInRedis && jobQueuePort.isInlineAsyncFallbackEnabled()) {
                jobQueuePort.dispatchAsyncProcessing(userId, doc.getId());
            }
        } else {
            try {
                processQueuedDocument(userId, doc.getId());
            } catch (DuplicateInvoiceException dupEx) {
                tempFileStorage.deleteFileQuietly(savedTempPath);
                transactionRunner.runInTransactionVoid(() -> documentRepository.deleteById(doc.getId()));
                throw dupEx;
            }
        }

        return getDocumentStatus(doc.getId())
                .orElseThrow(() -> new IllegalStateException("No fue posible consultar el documento recién creado."));
    }

    @Override
    public Optional<DocumentStatusView> getDocumentStatus(UUID documentId) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                documentRepository.findById(documentId).map(doc -> {
                    DocumentExtraction extraction = extractionRepository.findByDocumentId(doc.getId()).orElse(null);
                    return new DocumentStatusView(doc, extraction);
                })
        );
    }

    @Override
    public List<DocumentStatusView> listUserDocuments(UUID userId) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                documentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(doc -> new DocumentStatusView(
                                doc,
                                extractionRepository.findByDocumentId(doc.getId()).orElse(null)
                        ))
                        .toList()
        );
    }

    @Override
    public void cancelPendingDocument(UUID userId, UUID documentId) {
        transactionRunner.runInTransactionVoid(() -> {
            Document doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado: " + documentId));
            if (!doc.getUserId().equals(userId)) {
                throw new IllegalArgumentException("No tienes permiso para descartar este comprobante.");
            }
            if (doc.getStatus() == DocumentStatus.INDEXED) {
                throw new IllegalStateException("Este comprobante ya fue confirmado y guardado como gasto.");
            }
            if (doc.getTempFilePath() != null && !doc.getTempFilePath().isBlank()) {
                tempFileStorage.deleteFileQuietly(doc.getTempFilePath());
            }
            extractionRepository.findByDocumentId(documentId).ifPresent(ext -> {
                extractionRepository.delete(ext);
                extractionRepository.flush();
            });
            documentRepository.delete(doc);
        });
    }

    @Override
    public void processQueuedDocument(UUID userId, UUID documentId) {
        tenantExecutionPort.runAsUser(userId, () -> {
            int attempt = 0;
            while (attempt < MAX_RETRIES) {
                attempt++;
                final int currentAttempt = attempt;
                try {
                    transactionRunner.runInTransactionVoid(() -> executeSingleExtractionAttempt(userId, documentId));
                    return;
                } catch (DuplicateInvoiceException dupEx) {
                    markAsFailed(documentId, currentAttempt, dupEx.getMessage());
                    throw dupEx;
                } catch (Exception ex) {
                    if (currentAttempt >= MAX_RETRIES) {
                        markAsFailed(documentId, currentAttempt, ex.getMessage());
                    }
                }
            }
        });
    }

    private void executeSingleExtractionAttempt(UUID userId, UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado: " + documentId));

        if (doc.getStatus() == DocumentStatus.PROCESSING
                || doc.getStatus() == DocumentStatus.AWAITING_CONFIRMATION
                || doc.getStatus() == DocumentStatus.INDEXED) {
            return;
        }

        doc.setStatus(DocumentStatus.PROCESSING);
        doc = documentRepository.save(doc);

        try {
            byte[] fileBytes = tempFileStorage.readTempFile(doc.getTempFilePath());
            ExtractionOutcome outcome = documentExtractor.extract(doc.getSourceType(), fileBytes, doc.getOriginalFilename());
            ParsedUblInvoice parsed = outcome.parsedInvoice();

            if (doc.getSourceType() == DocumentSourceType.XML_UBL
                    && parsed.cufe() != null && !parsed.cufe().isBlank()) {
                UUID currentDocId = doc.getId();
                documentRepository.findByUserIdAndCufe(userId, parsed.cufe())
                        .filter(existing -> !existing.getId().equals(currentDocId))
                        .ifPresent(existing -> {
                            throw new DuplicateInvoiceException(
                                    "Factura duplicada detectada: ya registraste una factura con el mismo CUFE (" + parsed.cufe() + ")."
                            );
                        });
            }

            if (parsed.issuerValidForDuplicateCheck()) {
                UUID currentDocId = doc.getId();
                documentRepository.findByUserIdAndIssuerNitAndInvoiceNumber(userId, parsed.merchantNit(), parsed.invoiceNumber())
                        .filter(existing -> !existing.getId().equals(currentDocId))
                        .ifPresent(existing -> {
                            throw new DuplicateInvoiceException(
                                    "Factura duplicada detectada: ya existe la factura No. "
                                            + parsed.invoiceNumber() + " del emisor NIT " + parsed.merchantNit() + "."
                            );
                        });
            }

            if (doc.getSourceType() != DocumentSourceType.XML_UBL) {
                doc.setSourceType(outcome.resolvedSourceType());
                doc.setPageCount(outcome.pageCount());
            }
            doc.setCufe(parsed.cufe());
            doc.setInvoiceNumber(parsed.invoiceNumber());
            doc.setIssuerNit(parsed.merchantNit());
            doc.setIssuerName(parsed.merchantName());
            doc.setStatus(DocumentStatus.AWAITING_CONFIRMATION);
            documentRepository.save(doc);

            DocumentExtraction extraction = extractionRepository.findByDocumentId(doc.getId())
                    .orElseGet(DocumentExtraction::new);
            extraction.setUserId(userId);
            extraction.setDocumentId(doc.getId());
            extraction.setMerchantName(parsed.merchantName());
            extraction.setMerchantNit(parsed.merchantNit());
            extraction.setInvoiceNumber(parsed.invoiceNumber());
            extraction.setCufe(parsed.cufe());
            extraction.setIssueDate(parsed.issueDate());
            extraction.setSubtotalAmount(parsed.subtotalAmount());
            extraction.setTaxAmount(parsed.taxAmount());
            extraction.setDiscountAmount(parsed.discountAmount());
            extraction.setTotalAmount(parsed.totalAmount());
            extraction.setSuggestedCategory(parsed.suggestedCategory());
            extraction.setSuggestedBudgetBlock(parsed.suggestedBudgetBlock());
            extraction.setItemsJson(parsed.itemsJson());
            extraction.setRawExtractedText(parsed.rawSummaryText());
            extraction.setExtractionMethod(outcome.extractionMethod());
            extraction.setConfirmedByUser(false);
            extractionRepository.save(extraction);
        } catch (DuplicateInvoiceException dupEx) {
            throw dupEx;
        } catch (Exception ex) {
            throw new RuntimeException("Error extrayendo datos del documento: " + ex.getMessage(), ex);
        }
    }

    private void markAsFailed(UUID documentId, int retryCount, String errorMessage) {
        transactionRunner.runInTransactionVoid(() -> documentRepository.findById(documentId).ifPresent(doc -> {
            doc.setRetryCount(Math.min(retryCount, MAX_RETRIES));
            doc.setStatus(DocumentStatus.ERROR);
            doc.setErrorMessage(errorMessage);
            documentRepository.save(doc);
        }));
    }
}
