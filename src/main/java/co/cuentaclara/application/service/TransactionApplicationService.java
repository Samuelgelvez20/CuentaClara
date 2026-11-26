package co.cuentaclara.application.service;

import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.CategorySuggestion;
import co.cuentaclara.domain.model.ConfirmDocumentCommand;
import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentExtraction;
import co.cuentaclara.domain.model.DocumentSourceType;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.model.DocumentStatusView;
import co.cuentaclara.domain.model.ExpenseHistorySummary;
import co.cuentaclara.domain.model.ManualExpenseCommand;
import co.cuentaclara.domain.model.Transaction;
import co.cuentaclara.domain.model.TransactionSourceType;
import co.cuentaclara.domain.port.in.DocumentIngestionUseCase;
import co.cuentaclara.domain.port.in.TransactionUseCase;
import co.cuentaclara.domain.port.out.DocumentConfirmationHookPort;
import co.cuentaclara.domain.port.out.DocumentExtractionRepositoryPort;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import co.cuentaclara.domain.port.out.TransactionAnalyticsQueryPort;
import co.cuentaclara.domain.port.out.TransactionRepositoryPort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

public class TransactionApplicationService implements TransactionUseCase {

    private final TransactionRepositoryPort transactionRepository;
    private final DocumentRepositoryPort documentRepository;
    private final DocumentExtractionRepositoryPort extractionRepository;
    private final DocumentIngestionUseCase documentIngestionUseCase;
    private final TransactionAnalyticsQueryPort analyticsQueryPort;
    private final DocumentConfirmationHookPort confirmationHook;
    private final TransactionRunnerPort transactionRunner;

    public TransactionApplicationService(
            TransactionRepositoryPort transactionRepository,
            DocumentRepositoryPort documentRepository,
            DocumentExtractionRepositoryPort extractionRepository,
            DocumentIngestionUseCase documentIngestionUseCase,
            TransactionAnalyticsQueryPort analyticsQueryPort,
            DocumentConfirmationHookPort confirmationHook,
            TransactionRunnerPort transactionRunner
    ) {
        this.transactionRepository = transactionRepository;
        this.documentRepository = documentRepository;
        this.extractionRepository = extractionRepository;
        this.documentIngestionUseCase = documentIngestionUseCase;
        this.analyticsQueryPort = analyticsQueryPort;
        this.confirmationHook = confirmationHook;
        this.transactionRunner = transactionRunner;
    }

    @Override
    public Transaction confirmExtractedDocument(UUID userId, UUID documentId, ConfirmDocumentCommand request) {
        return transactionRunner.runInTransaction(() -> {
            Document doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado: " + documentId));

            DocumentExtraction extraction = extractionRepository.findByDocumentId(documentId)
                    .orElseGet(() -> {
                        DocumentExtraction ext = new DocumentExtraction();
                        ext.setUserId(userId);
                        ext.setDocumentId(documentId);
                        ext.setExtractionMethod("MANUAL_CONFIRMATION");
                        return ext;
                    });

            String originalSuggestedCategory = extraction.getSuggestedCategory();

            String merchantName = firstNonBlank(request.merchantName(), extraction.getMerchantName(), doc.getIssuerName(), "Comercio Confirmado");
            String merchantNit = firstNonBlank(request.merchantNit(), extraction.getMerchantNit(), doc.getIssuerNit(), "0");
            String invoiceNumber = firstNonBlank(request.invoiceNumber(), extraction.getInvoiceNumber(), doc.getInvoiceNumber(), "SIN-NUMERO");
            String cufe = firstNonBlank(request.cufe(), extraction.getCufe(), doc.getCufe(), null);
            LocalDate txDate = request.transactionDate() != null
                    ? request.transactionDate()
                    : (extraction.getIssueDate() != null ? extraction.getIssueDate() : LocalDate.now());
            BigDecimal subtotal = request.subtotalAmount() != null
                    ? request.subtotalAmount()
                    : (extraction.getSubtotalAmount() != null ? extraction.getSubtotalAmount() : BigDecimal.ZERO);
            BigDecimal tax = request.taxAmount() != null
                    ? request.taxAmount()
                    : (extraction.getTaxAmount() != null ? extraction.getTaxAmount() : BigDecimal.ZERO);
            BigDecimal total = request.totalAmount() != null
                    ? request.totalAmount()
                    : (extraction.getTotalAmount() != null ? extraction.getTotalAmount() : subtotal.add(tax));
            String category = firstNonBlank(request.category(), extraction.getSuggestedCategory(), "Otros");
            BudgetBlock budgetBlock = request.budgetBlock() != null
                    ? request.budgetBlock()
                    : (extraction.getSuggestedBudgetBlock() != null ? extraction.getSuggestedBudgetBlock() : BudgetBlock.NEEDS);
            String itemsSummary = firstNonBlank(request.itemsSummary(), extraction.getItemsJson(), "");

            extraction.setMerchantName(merchantName);
            extraction.setMerchantNit(merchantNit);
            extraction.setInvoiceNumber(invoiceNumber);
            extraction.setCufe(cufe);
            extraction.setIssueDate(txDate);
            extraction.setSubtotalAmount(subtotal);
            extraction.setTaxAmount(tax);
            extraction.setTotalAmount(total);
            extraction.setSuggestedCategory(category);
            extraction.setSuggestedBudgetBlock(budgetBlock);
            extraction.setConfirmedByUser(true);
            extraction.setConfirmedAt(OffsetDateTime.now());
            extractionRepository.save(extraction);

            doc.setIssuerName(merchantName);
            doc.setIssuerNit(merchantNit);
            doc.setInvoiceNumber(invoiceNumber);
            doc.setCufe(cufe);
            doc = documentRepository.save(doc);

            if (doc.getSourceType() == DocumentSourceType.STATEMENT_PDF) {
                if (confirmationHook != null) {
                    confirmationHook.onDocumentConfirmed(userId, doc, extraction, null, false);
                } else {
                    doc.setStatus(DocumentStatus.INDEXED);
                    documentRepository.save(doc);
                }
                Transaction statementPlaceholder = new Transaction();
                statementPlaceholder.setUserId(userId);
                statementPlaceholder.setDocumentId(doc.getId());
                statementPlaceholder.setMerchantName(merchantName);
                statementPlaceholder.setTotalAmount(BigDecimal.ZERO);
                statementPlaceholder.setCategory("Extracto Bancario");
                statementPlaceholder.setBudgetBlock(BudgetBlock.NEEDS);
                statementPlaceholder.setCitationReference(buildCitationReference(doc, null));
                return statementPlaceholder;
            }

            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setDocumentId(doc.getId());
            tx.setSourceType(resolveTransactionSourceType(doc.getSourceType()));
            tx.setMerchantName(merchantName);
            tx.setMerchantNit(merchantNit);
            tx.setInvoiceNumber(invoiceNumber);
            tx.setCufe(cufe);
            tx.setTransactionDate(txDate);
            tx.setSubtotalAmount(subtotal);
            tx.setTaxAmount(tax);
            tx.setTotalAmount(total);
            tx.setCategory(category);
            tx.setBudgetBlock(budgetBlock);
            tx.setDescription(request.description());
            tx.setItemsSummary(itemsSummary);
            tx.setCitationReference(buildCitationReference(doc, null));
            tx.setConfirmed(true);

            Transaction savedTx = transactionRepository.save(tx);

            boolean categoryChangedOrConfirmed = originalSuggestedCategory == null
                    || !originalSuggestedCategory.equalsIgnoreCase(category)
                    || request.category() != null;

            if (confirmationHook != null) {
                confirmationHook.onDocumentConfirmed(userId, doc, extraction, savedTx, categoryChangedOrConfirmed);
            } else {
                doc.setStatus(DocumentStatus.INDEXED);
                documentRepository.save(doc);
            }

            return savedTx;
        });
    }

    @Override
    public Transaction registerManualExpense(
            UUID userId,
            ManualExpenseCommand request,
            String optionalAttachmentFilename,
            byte[] optionalAttachmentBytes
    ) {
        return transactionRunner.runInTransaction(() -> {
            if (request.merchantName() == null || request.merchantName().isBlank()
                    || request.totalAmount() == null || request.totalAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("El comercio/concepto y un monto mayor a cero son obligatorios.");
            }

            UUID attachedDocumentId = null;
            Document attachedDoc = null;
            if (optionalAttachmentBytes != null && optionalAttachmentBytes.length > 0) {
                DocumentStatusView uploaded = documentIngestionUseCase.uploadAndProcessDocument(
                        userId,
                        optionalAttachmentFilename != null ? optionalAttachmentFilename : "respaldo_gasto.pdf",
                        optionalAttachmentBytes,
                        false,
                        false
                );
                attachedDoc = uploaded.document();
                attachedDocumentId = attachedDoc.getId();
            }

            String category = request.category();
            BudgetBlock block = request.budgetBlock();

            if ((category == null || category.isBlank()) && confirmationHook != null) {
                CategorySuggestion suggestion = confirmationHook.suggestCategoryForUser(
                        userId,
                        request.merchantName(),
                        request.description()
                );
                category = suggestion.category();
                if (block == null) {
                    block = suggestion.budgetBlock();
                }
            }
            if (category == null || category.isBlank()) {
                category = inferFallbackCategory(request.merchantName(), request.description());
            }
            if (block == null) {
                block = inferFallbackBudgetBlock(category);
            }

            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setDocumentId(attachedDocumentId);
            tx.setSourceType(TransactionSourceType.MANUAL);
            tx.setMerchantName(request.merchantName().trim());
            tx.setTransactionDate(request.transactionDate() != null ? request.transactionDate() : LocalDate.now());
            tx.setSubtotalAmount(request.totalAmount());
            tx.setTaxAmount(BigDecimal.ZERO);
            tx.setTotalAmount(request.totalAmount());
            tx.setCategory(category.trim());
            tx.setBudgetBlock(block);
            tx.setDescription(request.description());
            tx.setItemsSummary(request.description());
            tx.setCitationReference("MANUAL:PENDIENTE");
            tx.setConfirmed(true);

            Transaction saved = transactionRepository.save(tx);
            String finalCitation = attachedDoc != null
                    ? "MANUAL:" + saved.getId() + " | Respaldo: " + buildCitationReference(attachedDoc, null)
                    : "MANUAL:" + saved.getId();
            saved.setCitationReference(finalCitation);
            saved = transactionRepository.save(saved);

            if (confirmationHook != null) {
                confirmationHook.onManualExpenseRegistered(
                        userId,
                        saved,
                        attachedDoc,
                        request.category() != null && !request.category().isBlank()
                );
            }

            return saved;
        });
    }

    @Override
    public Transaction updateTransactionCategory(
            UUID userId,
            UUID transactionId,
            String newCategory,
            BudgetBlock newBudgetBlock
    ) {
        return transactionRunner.runInTransaction(() -> {
            if (newCategory == null || newCategory.isBlank()) {
                throw new IllegalArgumentException("La categoría es obligatoria.");
            }
            Transaction tx = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transacción no encontrada: " + transactionId));

            tx.setCategory(newCategory.trim());
            if (newBudgetBlock != null) {
                tx.setBudgetBlock(newBudgetBlock);
            }
            Transaction saved = transactionRepository.save(tx);

            if (confirmationHook != null) {
                confirmationHook.onManualCategoryCorrection(userId, saved);
            }
            return saved;
        });
    }

    @Override
    public ExpenseHistorySummary getExpenseHistory(
            UUID userId,
            String monthFilter,
            String categoryFilter,
            String merchantFilter
    ) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                analyticsQueryPort.queryExpenseHistory(userId, monthFilter, null, null, categoryFilter, merchantFilter)
        );
    }

    @Override
    public ExpenseHistorySummary getExpenseHistory(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate,
            String categoryFilter,
            String merchantFilter
    ) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                analyticsQueryPort.queryExpenseHistory(userId, null, startDate, endDate, categoryFilter, merchantFilter)
        );
    }

    public static String buildCitationReference(Document doc, Integer pageNumber) {
        if (doc.getSourceType() == DocumentSourceType.XML_UBL) {
            String inv = doc.getInvoiceNumber() != null ? doc.getInvoiceNumber() : doc.getOriginalFilename();
            String cufePart = doc.getCufe() != null ? " | CUFE:" + doc.getCufe() : "";
            String issuerPart = doc.getIssuerName() != null ? " | Emisor:" + doc.getIssuerName() : "";
            return "XML:" + inv + cufePart + issuerPart;
        }
        if (doc.getSourceType() == DocumentSourceType.PDF_DIGITAL
                || doc.getSourceType() == DocumentSourceType.PDF_SCANNED
                || doc.getSourceType() == DocumentSourceType.STATEMENT_PDF) {
            int page = pageNumber != null && pageNumber >= 1 ? pageNumber : 1;
            return "PDF:" + doc.getOriginalFilename() + " | PÁG:" + page;
        }
        return "IMG:" + doc.getOriginalFilename() + " | REF:" + doc.getId();
    }

    private TransactionSourceType resolveTransactionSourceType(DocumentSourceType docType) {
        return switch (docType) {
            case XML_UBL -> TransactionSourceType.INVOICE_XML;
            case PDF_DIGITAL, PDF_SCANNED, STATEMENT_PDF -> TransactionSourceType.INVOICE_PDF;
            case IMAGE -> TransactionSourceType.INVOICE_IMAGE;
        };
    }

    private String inferFallbackCategory(String merchant, String description) {
        String text = ((merchant != null ? merchant : "") + " " + (description != null ? description : "")).toLowerCase(Locale.ROOT);
        if (text.contains("rappi") || text.contains("uber eats") || text.contains("didi") || text.contains("domicilio") || text.contains("restaurante")) {
            return "Domicilios y Restaurantes";
        }
        if (text.contains("éxito") || text.contains("exito") || text.contains("carulla") || text.contains("d1") || text.contains("ara") || text.contains("mercado")) {
            return "Alimentación";
        }
        if (text.contains("epm") || text.contains("enel") || text.contains("claro") || text.contains("servicio") || text.contains("internet")) {
            return "Servicios";
        }
        if (text.contains("uber") || text.contains("taxi") || text.contains("terpel") || text.contains("transporte") || text.contains("bus")) {
            return "Transporte";
        }
        return "Otros";
    }

    private BudgetBlock inferFallbackBudgetBlock(String category) {
        String lower = category.toLowerCase(Locale.ROOT);
        if (lower.contains("domicilio") || lower.contains("restaurante") || lower.contains("ocio") || lower.contains("entretenimiento") || lower.contains("otros")) {
            return BudgetBlock.WANTS;
        }
        if (lower.contains("ahorro") || lower.contains("inversión") || lower.contains("deuda")) {
            return BudgetBlock.SAVINGS;
        }
        return BudgetBlock.NEEDS;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
