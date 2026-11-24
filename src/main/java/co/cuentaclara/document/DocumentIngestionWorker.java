package co.cuentaclara.document;

import co.cuentaclara.document.ocr.PdfAndOcrExtractionService;
import co.cuentaclara.document.xml.DianUblXmlParser;
import co.cuentaclara.security.rls.RlsDataSourceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker de procesamiento asíncrono de documentos (RF-03, RF-04, RNF-05).
 * Registra el trabajo en la cola de Redis (cuando está disponible) y procesa con hasta 3 reintentos,
 * dejando el documento en estado AWAITING_CONFIRMATION ("Por confirmar").
 */
@Service
public class DocumentIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionWorker.class);
    public static final String REDIS_QUEUE_KEY = "cuentaclara:queue:document-jobs";
    public static final int MAX_RETRIES = 3;

    private final DocumentRepository documentRepository;
    private final DocumentExtractionRepository extractionRepository;
    private final DianUblXmlParser ublXmlParser;
    private final PdfAndOcrExtractionService pdfAndOcrExtractionService;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redisTemplate;

    @org.springframework.beans.factory.annotation.Value("${cuentaclara.worker.consumer-enabled:true}")
    private boolean consumerEnabled = true;

    @org.springframework.beans.factory.annotation.Value("${cuentaclara.worker.inline-async-fallback:true}")
    private boolean inlineAsyncFallback = true;

    public DocumentIngestionWorker(
            DocumentRepository documentRepository,
            DocumentExtractionRepository extractionRepository,
            DianUblXmlParser ublXmlParser,
            PdfAndOcrExtractionService pdfAndOcrExtractionService,
            TransactionTemplate transactionTemplate,
            @Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this.documentRepository = documentRepository;
        this.extractionRepository = extractionRepository;
        this.ublXmlParser = ublXmlParser;
        this.pdfAndOcrExtractionService = pdfAndOcrExtractionService;
        this.transactionTemplate = transactionTemplate;
        this.redisTemplate = redisTemplate;
    }

    public boolean enqueueJob(UUID userId, UUID documentId) {
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForList().leftPush(REDIS_QUEUE_KEY, userId + ":" + documentId);
                return true;
            } catch (Exception ex) {
                log.debug("Redis no disponible localmente, procesando directamente en worker: {}", ex.getMessage());
            }
        }
        return false;
    }

    public boolean isConsumerEnabled() {
        return consumerEnabled;
    }

    public boolean isInlineAsyncFallbackEnabled() {
        return inlineAsyncFallback;
    }

    private volatile boolean shuttingDown = false;

    @jakarta.annotation.PreDestroy
    void onShutdown() {
        this.shuttingDown = true;
    }

    /**
     * Consumidor periódico de la cola Redis (`cuentaclara:queue:document-jobs`) (RF-03).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300)
    public void pollRedisQueue() {
        if (shuttingDown || !consumerEnabled || redisTemplate == null) {
            return;
        }
        for (int i = 0; i < 10 && !shuttingDown; i++) {
            if (!consumeNextQueuedJob()) {
                break;
            }
        }
    }

    /**
     * Extrae y procesa un trabajo pendiente desde Redis bajo contexto RLS explícito.
     */
    public boolean consumeNextQueuedJob() {
        if (redisTemplate == null) {
            return false;
        }
        String payload;
        try {
            payload = redisTemplate.opsForList().rightPop(REDIS_QUEUE_KEY);
        } catch (Exception ex) {
            log.debug("No se pudo leer la cola Redis {}: {}", REDIS_QUEUE_KEY, ex.getMessage());
            return false;
        }
        if (payload == null || payload.isBlank()) {
            return false;
        }

        String[] parts = payload.trim().split(":", 2);
        if (parts.length != 2) {
            log.warn("Carga inválida en cola Redis {}: {}", REDIS_QUEUE_KEY, payload);
            return true;
        }
        try {
            UUID userId = UUID.fromString(parts[0]);
            UUID documentId = UUID.fromString(parts[1]);
            processDocumentDeterministically(userId, documentId);
        } catch (DuplicateInvoiceException dupEx) {
            log.info("Documento duplicado rechazado al consumir cola ({}): {}", payload, dupEx.getMessage());
        } catch (Exception ex) {
            log.warn("Error procesando trabajo Redis {}: {}", payload, ex.getMessage());
        }
        return true;
    }

    public long getQueueDepth() {
        if (redisTemplate == null) {
            return 0L;
        }
        try {
            Long size = redisTemplate.opsForList().size(REDIS_QUEUE_KEY);
            return size != null ? size : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }

    @Async
    public void processDocumentAsync(UUID userId, UUID documentId) {
        processDocumentDeterministically(userId, documentId);
    }

    /**
     * Ejecuta el procesamiento del documento bajo el contexto RLS del usuario propietario,
     * con soporte de hasta 3 reintentos ante fallos (RNF-05).
     */
    public void processDocumentDeterministically(UUID userId, UUID documentId) {
        RlsDataSourceConfig.runAsUser(userId, () -> {
            int attempt = 0;
            while (attempt < MAX_RETRIES) {
                attempt++;
                final int currentAttempt = attempt;
                try {
                    transactionTemplate.executeWithoutResult(status -> executeSingleExtractionAttempt(userId, documentId));
                    return;
                } catch (DuplicateInvoiceException dupEx) {
                    markAsFailed(documentId, currentAttempt, dupEx.getMessage());
                    throw dupEx;
                } catch (Exception ex) {
                    log.warn("Intento {}/{} falló al procesar documento {}: {}", currentAttempt, MAX_RETRIES, documentId, ex.getMessage());
                    if (currentAttempt >= MAX_RETRIES) {
                        markAsFailed(documentId, currentAttempt, ex.getMessage());
                    }
                }
            }
        });
    }

    private void executeSingleExtractionAttempt(UUID userId, UUID documentId) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado: " + documentId));

        if (doc.getStatus() == DocumentStatus.PROCESSING
                || doc.getStatus() == DocumentStatus.AWAITING_CONFIRMATION
                || doc.getStatus() == DocumentStatus.INDEXED) {
            return;
        }

        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.saveAndFlush(doc);

        try {
            byte[] fileBytes = Files.readAllBytes(Path.of(doc.getTempFilePath()));

            if (doc.getSourceType() == DocumentSourceType.XML_UBL) {
                DianUblXmlParser.ParsedUblInvoice parsed = ublXmlParser.parse(fileBytes);

                // Verificación de duplicados por CUFE o por NIT + Número de Factura (RF-06)
                if (parsed.cufe() != null && !parsed.cufe().isBlank()) {
                    documentRepository.findByUserIdAndCufe(userId, parsed.cufe())
                            .filter(existing -> !existing.getId().equals(doc.getId()))
                            .ifPresent(existing -> {
                                throw new DuplicateInvoiceException(
                                        "Factura duplicada detectada: ya registraste una factura con el mismo CUFE (" + parsed.cufe() + ")."
                                );
                            });
                }
                if (parsed.issuerValidForDuplicateCheck()) {
                    documentRepository.findByUserIdAndIssuerNitAndInvoiceNumber(userId, parsed.merchantNit(), parsed.invoiceNumber())
                            .filter(existing -> !existing.getId().equals(doc.getId()))
                            .ifPresent(existing -> {
                                throw new DuplicateInvoiceException(
                                        "Factura duplicada detectada: ya existe la factura No. "
                                                + parsed.invoiceNumber() + " del emisor NIT " + parsed.merchantNit() + "."
                                );
                            });
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
                extraction.setExtractionMethod("UBL_2_1_DIRECT");
                extraction.setConfirmedByUser(false);
                extractionRepository.save(extraction);
            } else {
                PdfAndOcrExtractionService.ExtractionOutcome outcome =
                        pdfAndOcrExtractionService.extractFromPdfOrImage(fileBytes, doc.getSourceType());
                DianUblXmlParser.ParsedUblInvoice parsed = outcome.parsedInvoice();

                if (parsed.issuerValidForDuplicateCheck()) {
                    documentRepository.findByUserIdAndIssuerNitAndInvoiceNumber(userId, parsed.merchantNit(), parsed.invoiceNumber())
                            .filter(existing -> !existing.getId().equals(doc.getId()))
                            .ifPresent(existing -> {
                                throw new DuplicateInvoiceException(
                                        "Factura duplicada detectada: ya existe la factura No. "
                                                + parsed.invoiceNumber() + " del emisor NIT " + parsed.merchantNit() + "."
                                );
                            });
                }

                doc.setSourceType(outcome.resolvedSourceType());
                doc.setPageCount(outcome.pageCount());
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
            }
        } catch (DuplicateInvoiceException dupEx) {
            throw dupEx;
        } catch (Exception ex) {
            throw new RuntimeException("Error extrayendo datos del documento: " + ex.getMessage(), ex);
        }
    }

    private void markAsFailed(UUID documentId, int retryCount, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> documentRepository.findById(documentId).ifPresent(doc -> {
            doc.setRetryCount(Math.min(retryCount, MAX_RETRIES));
            doc.setStatus(DocumentStatus.ERROR);
            doc.setErrorMessage(errorMessage);
            documentRepository.save(doc);
        }));
    }

    public static class DuplicateInvoiceException extends RuntimeException {
        public DuplicateInvoiceException(String message) {
            super(message);
        }
    }
}
