package co.cuentaclara.transaction;

import co.cuentaclara.document.DocumentEntity;
import co.cuentaclara.document.DocumentExtraction;
import co.cuentaclara.document.DocumentExtractionRepository;
import co.cuentaclara.document.DocumentIngestionService;
import co.cuentaclara.document.DocumentRepository;
import co.cuentaclara.document.DocumentSourceType;
import co.cuentaclara.document.DocumentStatus;
import co.cuentaclara.profile.BudgetBlock;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de confirmación/corrección de facturas extraídas (RF-05),
 * registro de gastos manuales con adjunto de respaldo opcional (RF-02),
 * e historial con filtros por mes, categoría y comercio con totales SQL exactos (RF-10, RNF-06).
 */
@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentExtractionRepository extractionRepository;
    private final DocumentIngestionService documentIngestionService;
    private final JdbcTemplate jdbcTemplate;
    private DocumentConfirmationHook confirmationHook;

    public TransactionService(
            TransactionRepository transactionRepository,
            DocumentRepository documentRepository,
            DocumentExtractionRepository extractionRepository,
            DocumentIngestionService documentIngestionService,
            JdbcTemplate jdbcTemplate
    ) {
        this.transactionRepository = transactionRepository;
        this.documentRepository = documentRepository;
        this.extractionRepository = extractionRepository;
        this.documentIngestionService = documentIngestionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Autowired(required = false)
    public void setConfirmationHook(DocumentConfirmationHook confirmationHook) {
        this.confirmationHook = confirmationHook;
    }

    /**
     * Confirma (y permite corregir) los datos extraídos de una factura antes de convertirla en transacción
     * y disparar la indexación vectorial + borrado del temporal (RF-05, RF-07, RF-08, RF-09).
     */
    @Transactional
    public TransactionEntity confirmExtractedDocument(UUID userId, UUID documentId, ConfirmDocumentRequest request) {
        DocumentEntity doc = documentRepository.findById(documentId)
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

        // Actualizar extracción como confirmada por el usuario (RF-05)
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
        documentRepository.save(doc);

        // Si es un extracto bancario PDF digital, solo se indexa como texto por páginas sin crear transacción (Alcance MVP)
        if (doc.getSourceType() == DocumentSourceType.STATEMENT_PDF) {
            if (confirmationHook != null) {
                confirmationHook.onDocumentConfirmed(userId, doc, extraction, null, false);
            } else {
                doc.setStatus(DocumentStatus.INDEXED);
                documentRepository.save(doc);
            }
            TransactionEntity statementPlaceholder = new TransactionEntity();
            statementPlaceholder.setUserId(userId);
            statementPlaceholder.setDocumentId(doc.getId());
            statementPlaceholder.setMerchantName(merchantName);
            statementPlaceholder.setTotalAmount(BigDecimal.ZERO);
            statementPlaceholder.setCategory("Extracto Bancario");
            statementPlaceholder.setBudgetBlock(BudgetBlock.NEEDS);
            statementPlaceholder.setCitationReference(buildCitationReference(doc, null));
            return statementPlaceholder;
        }

        TransactionEntity tx = new TransactionEntity();
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

        TransactionEntity savedTx = transactionRepository.save(tx);

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
    }

    /**
     * Registra un gasto diario manual en segundos, con adjunto de respaldo opcional (RF-02, RF-09).
     */
    @Transactional
    public TransactionEntity registerManualExpense(
            UUID userId,
            ManualExpenseRequest request,
            String optionalAttachmentFilename,
            byte[] optionalAttachmentBytes
    ) {
        UUID attachedDocumentId = null;
        DocumentEntity attachedDoc = null;
        if (optionalAttachmentBytes != null && optionalAttachmentBytes.length > 0) {
            DocumentIngestionService.DocumentStatusView uploaded = documentIngestionService.uploadAndProcessDocument(
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

        TransactionEntity tx = new TransactionEntity();
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

        TransactionEntity saved = transactionRepository.save(tx);
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
    }

    /**
     * Permite al usuario corregir manualmente la categoría de una transacción ya registrada y
     * guarda esa corrección como referencia futura para ese mismo usuario (RF-09).
     */
    @Transactional
    public TransactionEntity updateTransactionCategory(
            UUID userId,
            UUID transactionId,
            String newCategory,
            BudgetBlock newBudgetBlock
    ) {
        TransactionEntity tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transacción no encontrada: " + transactionId));

        tx.setCategory(newCategory.trim());
        if (newBudgetBlock != null) {
            tx.setBudgetBlock(newBudgetBlock);
        }
        TransactionEntity saved = transactionRepository.save(tx);

        if (confirmationHook != null) {
            confirmationHook.onManualCategoryCorrection(userId, saved);
        }
        return saved;
    }

    /**
     * Consulta el historial de gastos con filtros opcionales (mes YYYY-MM, categoría, comercio)
     * y calcula los totales generales y por categoría directamente en SQL (RF-10, RNF-06).
     */
    @Transactional(readOnly = true)
    public ExpenseHistorySummary getExpenseHistory(
            UUID userId,
            String yearMonthFilter,
            String categoryFilter,
            String merchantFilter
    ) {
        StringBuilder whereClause = new StringBuilder(" WHERE confirmed = TRUE AND user_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (yearMonthFilter != null && !yearMonthFilter.isBlank()) {
            YearMonth ym = YearMonth.parse(yearMonthFilter.trim());
            whereClause.append(" AND transaction_date >= ? AND transaction_date <= ? ");
            params.add(java.sql.Date.valueOf(ym.atDay(1)));
            params.add(java.sql.Date.valueOf(ym.atEndOfMonth()));
        }
        if (categoryFilter != null && !categoryFilter.isBlank()) {
            whereClause.append(" AND LOWER(category) = LOWER(?) ");
            params.add(categoryFilter.trim());
        }
        if (merchantFilter != null && !merchantFilter.isBlank()) {
            whereClause.append(" AND LOWER(merchant_name) LIKE LOWER(?) ");
            params.add("%" + merchantFilter.trim() + "%");
        }

        String listSql = "SELECT id FROM transactions " + whereClause + " ORDER BY transaction_date DESC, created_at DESC";
        List<UUID> ids = jdbcTemplate.queryForList(listSql, UUID.class, params.toArray());
        List<TransactionEntity> transactions = ids.stream()
                .map(transactionRepository::findById)
                .flatMap(Optional::stream)
                .toList();

        // Totales por categoría calculados estrictamente en SQL (RF-10, RNF-06)
        String categoryTotalsSql = "SELECT category, COALESCE(SUM(total_amount), 0) AS cat_total "
                + "FROM transactions " + whereClause
                + " GROUP BY category ORDER BY cat_total DESC";
        Map<String, BigDecimal> totalsByCategory = new LinkedHashMap<>();
        jdbcTemplate.query(categoryTotalsSql, rs -> {
            totalsByCategory.put(rs.getString("category"), rs.getBigDecimal("cat_total"));
        }, params.toArray());

        String grandTotalSql = "SELECT COALESCE(SUM(total_amount), 0) FROM transactions " + whereClause;
        BigDecimal grandTotal = jdbcTemplate.queryForObject(grandTotalSql, BigDecimal.class, params.toArray());

        return new ExpenseHistorySummary(
                transactions,
                totalsByCategory,
                grandTotal != null ? grandTotal : BigDecimal.ZERO
        );
    }

    public static String buildCitationReference(DocumentEntity doc, Integer pageNumber) {
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

    public interface DocumentConfirmationHook {
        void onDocumentConfirmed(
                UUID userId,
                DocumentEntity document,
                DocumentExtraction extraction,
                TransactionEntity transaction,
                boolean saveUserCategoryPreference
        );

        void onManualExpenseRegistered(
                UUID userId,
                TransactionEntity transaction,
                DocumentEntity optionalAttachment,
                boolean saveUserCategoryPreference
        );

        void onManualCategoryCorrection(UUID userId, TransactionEntity transaction);

        CategorySuggestion suggestCategoryForUser(UUID userId, String merchantName, String contextText);
    }

    public record CategorySuggestion(String category, BudgetBlock budgetBlock, String reason) {
    }

    public record ConfirmDocumentRequest(
            String merchantName,
            String merchantNit,
            String invoiceNumber,
            String cufe,
            LocalDate transactionDate,
            BigDecimal subtotalAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String category,
            BudgetBlock budgetBlock,
            String description,
            String itemsSummary
    ) {
    }

    public record ManualExpenseRequest(
            @NotBlank(message = "El comercio o concepto es obligatorio")
            String merchantName,

            LocalDate transactionDate,

            @NotNull(message = "El monto del gasto es obligatorio")
            @DecimalMin(value = "0.01", message = "El monto debe ser mayor a cero")
            BigDecimal totalAmount,

            String category,

            BudgetBlock budgetBlock,

            String description
    ) {
    }

    public record ExpenseHistorySummary(
            List<TransactionEntity> transactions,
            Map<String, BigDecimal> totalsByCategory,
            BigDecimal grandTotal
    ) {
    }
}
