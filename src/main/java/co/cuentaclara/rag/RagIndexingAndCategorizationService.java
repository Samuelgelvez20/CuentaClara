package co.cuentaclara.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.cuentaclara.document.DocumentEntity;
import co.cuentaclara.document.DocumentExtraction;
import co.cuentaclara.document.DocumentRepository;
import co.cuentaclara.document.DocumentSourceType;
import co.cuentaclara.document.DocumentStatus;
import co.cuentaclara.profile.BudgetBlock;
import co.cuentaclara.transaction.TransactionEntity;
import co.cuentaclara.transaction.TransactionService;

/**
 * Servicio de Fragmentación, Indexación Vectorial en pgvector, Verificación y Eliminación Segura de
 * Archivos Temporales (RF-07, RF-08, RNF-04, RNF-05) y Categorización Semántica por Similitud con
 * Reutilización de Correcciones por Usuario (RF-09).
 */
@Service
public class RagIndexingAndCategorizationService implements TransactionService.DocumentConfirmationHook {

    private static final Logger log = LoggerFactory.getLogger(RagIndexingAndCategorizationService.class);
    private static final Pattern PAGE_SPLIT_PATTERN = Pattern.compile("\\[PÁGINA\\s+(\\d+)\\]");

    private final JdbcTemplate jdbcTemplate;
    private final DocumentRepository documentRepository;
    private final EmbeddingProvider embeddingProvider;
    private final ObjectMapper objectMapper;

    public RagIndexingAndCategorizationService(
            JdbcTemplate jdbcTemplate,
            DocumentRepository documentRepository,
            EmbeddingProvider embeddingProvider,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
        this.embeddingProvider = embeddingProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void onDocumentConfirmed(
            UUID userId,
            DocumentEntity document,
            DocumentExtraction extraction,
            TransactionEntity transaction,
            boolean saveUserCategoryPreference
    ) {
        // 1. Limpiar fragmentos previos de este documento si se está re-confirmando
        jdbcTemplate.update(
                "DELETE FROM document_chunks WHERE user_id = ? AND document_id = ?",
                userId, document.getId()
        );

        List<ChunkDraft> drafts = buildChunksForConfirmedDocument(document, extraction, transaction);
        UUID txId = transaction != null ? transaction.getId() : null;

        // 2. Generar embeddings versionados y guardar en document_chunks (RF-07)
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft draft = drafts.get(i);
            insertChunkWithVector(
                    userId,
                    document.getId(),
                    txId,
                    draft.chunkType(),
                    i,
                    draft.pageNumber(),
                    draft.sourceReference(),
                    draft.contentText()
            );
        }

        // 3. Verificar que los fragmentos realmente están indexados con vector no nulo en PostgreSQL
        // ANTES de eliminar la copia temporal (RF-08, RNF-04)
        Integer verifiedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE user_id = ? AND document_id = ? AND embedding IS NOT NULL",
                Integer.class,
                userId, document.getId()
        );

        if (verifiedCount == null || verifiedCount < drafts.size() || verifiedCount == 0) {
            throw new IllegalStateException("La verificación de indexación en pgvector falló; se conserva el archivo temporal para reintento (RF-08).");
        }

        // 4. Eliminar de forma segura el archivo temporal y actualizar el estado a INDEXED (RF-08)
        deleteTemporaryFileAfterVerification(document);
        document.setStatus(DocumentStatus.INDEXED);
        documentRepository.save(document);

        // 5. Si el usuario confirmó/corrigió la categoría, registrarla en user_category_corrections para ese usuario (RF-09)
        if (saveUserCategoryPreference && transaction != null) {
            recordUserCategoryCorrection(
                    userId,
                    transaction.getMerchantName(),
                    transaction.getMerchantName() + " " + (transaction.getItemsSummary() != null ? transaction.getItemsSummary() : ""),
                    transaction.getCategory(),
                    transaction.getBudgetBlock()
            );
        }
    }

    @Override
    @Transactional
    public void onManualExpenseRegistered(
            UUID userId,
            TransactionEntity transaction,
            DocumentEntity optionalAttachment,
            boolean saveUserCategoryPreference
    ) {
        String content = String.format(
                "Gasto manual registrado | Comercio: %s | Fecha: %s | Categoría: %s | Total: %s COP | Detalle: %s | Cita: %s",
                transaction.getMerchantName(),
                transaction.getTransactionDate(),
                transaction.getCategory(),
                transaction.getTotalAmount().toPlainString(),
                transaction.getDescription() != null ? transaction.getDescription() : "Sin descripción adicional",
                transaction.getCitationReference()
        );

        UUID docId = optionalAttachment != null ? optionalAttachment.getId() : null;
        insertChunkWithVector(
                userId,
                docId,
                transaction.getId(),
                "MANUAL_EXPENSE",
                0,
                null,
                transaction.getCitationReference(),
                content
        );

        if (optionalAttachment != null) {
            Integer verifiedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM document_chunks WHERE user_id = ? AND transaction_id = ? AND embedding IS NOT NULL",
                    Integer.class,
                    userId, transaction.getId()
            );
            if (verifiedCount != null && verifiedCount > 0) {
                deleteTemporaryFileAfterVerification(optionalAttachment);
                optionalAttachment.setStatus(DocumentStatus.INDEXED);
                documentRepository.save(optionalAttachment);
            }
        }

        if (saveUserCategoryPreference) {
            recordUserCategoryCorrection(
                    userId,
                    transaction.getMerchantName(),
                    transaction.getMerchantName() + " " + (transaction.getDescription() != null ? transaction.getDescription() : ""),
                    transaction.getCategory(),
                    transaction.getBudgetBlock()
            );
        }
    }

    @Override
    @Transactional
    public void onManualCategoryCorrection(UUID userId, TransactionEntity transaction) {
        recordUserCategoryCorrection(
                userId,
                transaction.getMerchantName(),
                transaction.getMerchantName() + " " + (transaction.getDescription() != null ? transaction.getDescription() : ""),
                transaction.getCategory(),
                transaction.getBudgetBlock()
        );
    }

    /**
     * Sugiere una categoría para una compra nueva buscando por similitud vectorial en las correcciones
     * y compras previas confirmadas DEL MISMO USUARIO (RF-09). Nunca cruza datos entre usuarios.
     */
    @Override
    @Transactional(readOnly = true)
    public TransactionService.CategorySuggestion suggestCategoryForUser(
            UUID userId,
            String merchantName,
            String contextText
    ) {
        String queryText = ((merchantName != null ? merchantName : "") + " " + (contextText != null ? contextText : "")).trim();
        float[] queryEmbedding = embeddingProvider.generateEmbedding(queryText);
        String vectorLiteral = embeddingProvider.toPgVectorLiteral(queryEmbedding);

        // 1. Buscar coincidencia directa o por similitud vectorial en user_category_corrections del mismo usuario
        List<TransactionService.CategorySuggestion> matches = jdbcTemplate.query(
                """
                SELECT confirmed_category, confirmed_budget_block, merchant_pattern,
                       (1 - (embedding <=> CAST(? AS vector))) AS similarity
                FROM user_category_corrections
                WHERE user_id = ? AND embedding IS NOT NULL
                ORDER BY ( CASE WHEN LOWER(?) LIKE '%' || LOWER(merchant_pattern) || '%' THEN 0 ELSE 1 END ),
                         embedding <=> CAST(? AS vector) ASC
                LIMIT 1
                """,
                (rs, rowNum) -> {
                    double sim = rs.getDouble("similarity");
                    String pattern = rs.getString("merchant_pattern");
                    boolean nameMatches = merchantName != null
                            && pattern != null
                            && (merchantName.toLowerCase().contains(pattern.toLowerCase())
                            || pattern.toLowerCase().contains(merchantName.toLowerCase()));
                    if (nameMatches || sim >= 0.45) {
                        return new TransactionService.CategorySuggestion(
                                rs.getString("confirmed_category"),
                                BudgetBlock.valueOf(rs.getString("confirmed_budget_block")),
                                "Sugerida por similitud con tu corrección previa en '" + pattern + "'"
                        );
                    }
                    return null;
                },
                vectorLiteral, userId, merchantName != null ? merchantName : "", vectorLiteral
        );

        if (!matches.isEmpty() && matches.getFirst() != null) {
            return matches.getFirst();
        }

        // 2. Respaldo heurístico base
        return new TransactionService.CategorySuggestion("Otros", BudgetBlock.WANTS, "Categoría base por defecto");
    }

    public void recordUserCategoryCorrection(
            UUID userId,
            String merchantName,
            String contextText,
            String confirmedCategory,
            BudgetBlock confirmedBlock
    ) {
        String pattern = merchantName != null ? merchantName.trim() : "Comercio";
        String fullContext = (pattern + " " + (contextText != null ? contextText : "")).trim();
        float[] vec = embeddingProvider.generateEmbedding(fullContext);
        String vecLiteral = embeddingProvider.toPgVectorLiteral(vec);

        jdbcTemplate.update(
                "DELETE FROM user_category_corrections WHERE user_id = ? AND LOWER(merchant_pattern) = LOWER(?)",
                userId, pattern
        );

        jdbcTemplate.update(
                """
                INSERT INTO user_category_corrections
                    (user_id, merchant_pattern, context_text, confirmed_category, confirmed_budget_block, embedding)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector))
                """,
                userId,
                pattern,
                fullContext,
                confirmedCategory,
                confirmedBlock.name(),
                vecLiteral
        );
    }

    private List<ChunkDraft> buildChunksForConfirmedDocument(
            DocumentEntity doc,
            DocumentExtraction extraction,
            TransactionEntity tx
    ) {
        List<ChunkDraft> chunks = new ArrayList<>();

        // Caso A: Extracto bancario PDF digital -> ventanas de 500–1.000 tokens con solapamiento preservando página (RF-07)
        if (doc.getSourceType() == DocumentSourceType.STATEMENT_PDF) {
            String raw = extraction.getRawExtractedText() != null ? extraction.getRawExtractedText() : "";
            List<PageSection> sections = splitByPageMarkers(raw);
            for (PageSection pageSec : sections) {
                List<String> windows = splitIntoOverlappingWindows(pageSec.text(), 600, 90);
                for (String win : windows) {
                    String citation = TransactionService.buildCitationReference(doc, pageSec.pageNumber());
                    chunks.add(new ChunkDraft(
                            "STATEMENT_WINDOW",
                            pageSec.pageNumber(),
                            citation,
                            "Extracto bancario " + doc.getOriginalFilename() + " (Página " + pageSec.pageNumber() + "): " + win
                    ));
                }
            }
            if (chunks.isEmpty()) {
                String citation = TransactionService.buildCitationReference(doc, 1);
                chunks.add(new ChunkDraft("STATEMENT_WINDOW", 1, citation, "Extracto bancario: " + doc.getOriginalFilename()));
            }
            return chunks;
        }

        // Caso B: Factura (XML, PDF o Imagen) -> (1) un fragmento por resumen de factura y (2) fragmentos por grupo de ítems (RF-07)
        Integer page = (doc.getSourceType() == DocumentSourceType.PDF_DIGITAL || doc.getSourceType() == DocumentSourceType.PDF_SCANNED)
                ? 1
                : null;
        String citation = TransactionService.buildCitationReference(doc, page);

        String invoiceSummaryText = String.format(
                "Factura confirmada | Comercio: %s (NIT %s) | Factura No: %s | Fecha: %s | Categoría: %s (%s) | Subtotal: %s COP | Impuestos: %s COP | Total: %s COP | Fuente: %s",
                extraction.getMerchantName(),
                extraction.getMerchantNit(),
                extraction.getInvoiceNumber(),
                extraction.getIssueDate(),
                tx != null ? tx.getCategory() : extraction.getSuggestedCategory(),
                tx != null ? tx.getBudgetBlock() : extraction.getSuggestedBudgetBlock(),
                extraction.getSubtotalAmount(),
                extraction.getTaxAmount(),
                extraction.getTotalAmount(),
                citation
        );
        chunks.add(new ChunkDraft("INVOICE_SUMMARY", page, citation, invoiceSummaryText));

        // Fragmento(s) por grupo de ítems
        List<Map<String, Object>> parsedItems = parseItemsJsonSafely(extraction.getItemsJson());
        if (!parsedItems.isEmpty()) {
            int groupSize = 5;
            for (int start = 0; start < parsedItems.size(); start += groupSize) {
                List<Map<String, Object>> subList = parsedItems.subList(start, Math.min(parsedItems.size(), start + groupSize));
                StringBuilder groupBuilder = new StringBuilder();
                groupBuilder.append("Detalle de ítems de factura ").append(extraction.getInvoiceNumber())
                        .append(" en ").append(extraction.getMerchantName())
                        .append(" (Fecha ").append(extraction.getIssueDate()).append("): ");
                for (Map<String, Object> item : subList) {
                    groupBuilder.append("[")
                            .append(item.getOrDefault("description", "Ítem"))
                            .append(" x").append(item.getOrDefault("quantity", 1))
                            .append(" = ").append(item.getOrDefault("lineTotal", 0))
                            .append(" COP] ");
                }
                chunks.add(new ChunkDraft("ITEM_GROUP", page, citation, groupBuilder.toString().trim()));
            }
        }

        // Caso C: PDFs generales (digital o escaneado) -> indexar además el contenido completo por páginas y ventanas con solapamiento
        if ((doc.getSourceType() == DocumentSourceType.PDF_DIGITAL || doc.getSourceType() == DocumentSourceType.PDF_SCANNED)
                && extraction.getRawExtractedText() != null
                && !extraction.getRawExtractedText().isBlank()) {
            List<PageSection> sections = splitByPageMarkers(extraction.getRawExtractedText());
            for (PageSection pageSec : sections) {
                List<String> windows = splitIntoOverlappingWindows(pageSec.text(), 600, 90);
                for (String win : windows) {
                    String pageCitation = TransactionService.buildCitationReference(doc, pageSec.pageNumber());
                    chunks.add(new ChunkDraft(
                            "DOCUMENT_CONTENT",
                            pageSec.pageNumber(),
                            pageCitation,
                            "Contenido " + doc.getOriginalFilename() + " (Página " + pageSec.pageNumber() + "): " + win
                    ));
                }
            }
        }

        return chunks;
    }

    /**
     * Re-vectoriza fragmentos existentes en `document_chunks` que pertenezcan a un modelo o versión
     * distinta al proveedor activo, usando `content_text` almacenado sin requerir el PDF original.
     */
    @Transactional
    public int reindexChunksForCurrentEmbeddingModel(UUID userId) {
        String activeModel = embeddingProvider.modelName();
        String activeVersion = embeddingProvider.modelVersion();

        List<Map<String, Object>> staleChunks = jdbcTemplate.queryForList(
                """
                SELECT id, content_text
                FROM document_chunks
                WHERE user_id = ?
                  AND (embedding IS NULL OR embedding_model <> ? OR embedding_version <> ?)
                """,
                userId, activeModel, activeVersion
        );

        int updated = 0;
        for (Map<String, Object> row : staleChunks) {
            UUID chunkId = (UUID) row.get("id");
            String contentText = (String) row.get("content_text");
            float[] vector = embeddingProvider.generateEmbedding(contentText != null ? contentText : "");
            String vectorLiteral = embeddingProvider.toPgVectorLiteral(vector);

            jdbcTemplate.update(
                    """
                    UPDATE document_chunks
                    SET embedding = CAST(? AS vector),
                        embedding_model = ?,
                        embedding_version = ?
                    WHERE id = ? AND user_id = ?
                    """,
                    vectorLiteral, activeModel, activeVersion, chunkId, userId
            );
            updated++;
        }
        return updated;
    }

    private void insertChunkWithVector(
            UUID userId,
            UUID documentId,
            UUID transactionId,
            String chunkType,
            int chunkOrder,
            Integer pageNumber,
            String sourceReference,
            String contentText
    ) {
        float[] embedding = embeddingProvider.generateEmbedding(contentText);
        String vectorLiteral = embeddingProvider.toPgVectorLiteral(embedding);

        jdbcTemplate.update(
                """
                INSERT INTO document_chunks
                    (user_id, document_id, transaction_id, chunk_type, chunk_order, page_number,
                     source_reference, content_text, embedding, embedding_model, embedding_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, ?)
                """,
                userId,
                documentId,
                transactionId,
                chunkType,
                chunkOrder,
                pageNumber,
                sourceReference,
                contentText,
                vectorLiteral,
                embeddingProvider.modelName(),
                embeddingProvider.modelVersion()
        );
    }

    private void deleteTemporaryFileAfterVerification(DocumentEntity document) {
        String tempPath = document.getTempFilePath();
        if (tempPath != null && !tempPath.isBlank()) {
            try {
                Files.deleteIfExists(Path.of(tempPath));
                document.setTempFileDeleted(true);
            } catch (IOException ex) {
                log.warn("No se pudo borrar inmediatamente el archivo temporal {}: {}", tempPath, ex.getMessage());
            }
        } else {
            document.setTempFileDeleted(true);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private co.cuentaclara.document.TempFileLifecycleAndRetryService tempFileLifecycleService;

    /**
     * Limpieza bajo demanda de archivos temporales huérfanos; la ejecución periódica
     * programada reside en {@link co.cuentaclara.document.TempFileLifecycleAndRetryService} (RF-08, RNF-05).
     */
    public void cleanupOrphanedTempFiles() {
        if (tempFileLifecycleService != null) {
            tempFileLifecycleService.cleanupOrphanedTempFiles();
        }
    }

    private List<Map<String, Object>> parseItemsJsonSafely(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<PageSection> splitByPageMarkers(String rawText) {
        List<PageSection> result = new ArrayList<>();
        Matcher matcher = PAGE_SPLIT_PATTERN.matcher(rawText);
        List<Integer> pageNumbers = new ArrayList<>();
        List<Integer> startOffsets = new ArrayList<>();
        List<Integer> endMarkerOffsets = new ArrayList<>();

        while (matcher.find()) {
            pageNumbers.add(Integer.parseInt(matcher.group(1)));
            startOffsets.add(matcher.start());
            endMarkerOffsets.add(matcher.end());
        }

        if (pageNumbers.isEmpty()) {
            result.add(new PageSection(1, rawText));
            return result;
        }

        for (int i = 0; i < pageNumbers.size(); i++) {
            int contentStart = endMarkerOffsets.get(i);
            int contentEnd = (i + 1 < startOffsets.size()) ? startOffsets.get(i + 1) : rawText.length();
            String pageContent = rawText.substring(contentStart, contentEnd).trim();
            if (!pageContent.isBlank()) {
                result.add(new PageSection(pageNumbers.get(i), pageContent));
            }
        }
        return result;
    }

    /**
     * Divide texto en fragmentos superpuestos por presupuesto aproximado de tokens (~500-800 tokens por chunk, RF-07).
     */
    public List<String> splitIntoOverlappingWindows(String text, int targetMaxTokens, int overlapTokens) {
        List<String> windows = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return windows;
        }
        String[] words = text.trim().split("\\s+");
        if (estimateTokenCount(text) <= targetMaxTokens && text.length() <= 3200) {
            windows.add(text.trim());
            return windows;
        }

        int maxTokens = Math.max(120, Math.min(targetMaxTokens, 800));
        int overlap = Math.max(20, Math.min(overlapTokens, maxTokens / 3));

        int startIdx = 0;
        while (startIdx < words.length) {
            StringBuilder chunk = new StringBuilder();
            int tokenSum = 0;
            int endIdx = startIdx;

            while (endIdx < words.length) {
                int wTokens = estimateWordTokens(words[endIdx]);
                if (endIdx > startIdx && (tokenSum + wTokens > maxTokens || chunk.length() + words[endIdx].length() + 1 > 3200)) {
                    break;
                }
                if (chunk.length() > 0) {
                    chunk.append(' ');
                }
                chunk.append(words[endIdx]);
                tokenSum += wTokens;
                endIdx++;
            }

            String built = chunk.toString().trim();
            if (!built.isEmpty()) {
                windows.add(built);
            }
            if (endIdx >= words.length) {
                break;
            }

            // Retroceder palabras suficientes para cubrir `overlap` tokens de superposición contextual
            int overlapSum = 0;
            int nextStart = endIdx;
            while (nextStart > startIdx + 1 && overlapSum < overlap) {
                nextStart--;
                overlapSum += estimateWordTokens(words[nextStart]);
            }
            startIdx = Math.max(startIdx + 1, nextStart);
        }
        return windows;
    }

    public int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int total = 0;
        for (String word : text.trim().split("\\s+")) {
            total += estimateWordTokens(word);
        }
        return total;
    }

    private int estimateWordTokens(String word) {
        if (word == null || word.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(word.length() / 4.0));
    }

    private record PageSection(int pageNumber, String text) {
    }

    private record ChunkDraft(String chunkType, Integer pageNumber, String sourceReference, String contentText) {
    }
}
