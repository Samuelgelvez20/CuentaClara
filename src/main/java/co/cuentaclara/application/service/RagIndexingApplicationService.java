package co.cuentaclara.application.service;

import co.cuentaclara.domain.model.CategorySuggestion;
import co.cuentaclara.domain.model.CleanupStats;
import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentExtraction;
import co.cuentaclara.domain.model.DocumentSourceType;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.model.ParsedLineItem;
import co.cuentaclara.domain.model.Transaction;
import co.cuentaclara.domain.port.in.RagMaintenanceUseCase;
import co.cuentaclara.domain.port.out.DocumentConfirmationHookPort;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import co.cuentaclara.domain.port.out.JsonSerializationPort;
import co.cuentaclara.domain.port.out.TempFileStoragePort;
import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import co.cuentaclara.domain.port.out.VectorIndexPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RagIndexingApplicationService implements DocumentConfirmationHookPort, RagMaintenanceUseCase {

    private static final Pattern PAGE_SPLIT_PATTERN = Pattern.compile("\\[PÁGINA\\s+(\\d+)\\]");

    private final VectorIndexPort vectorIndexPort;
    private final DocumentRepositoryPort documentRepository;
    private final TempFileStoragePort tempFileStorage;
    private final JsonSerializationPort jsonSerializationPort;
    private final TransactionRunnerPort transactionRunner;

    public RagIndexingApplicationService(
            VectorIndexPort vectorIndexPort,
            DocumentRepositoryPort documentRepository,
            TempFileStoragePort tempFileStorage,
            JsonSerializationPort jsonSerializationPort,
            TransactionRunnerPort transactionRunner
    ) {
        this.vectorIndexPort = vectorIndexPort;
        this.documentRepository = documentRepository;
        this.tempFileStorage = tempFileStorage;
        this.jsonSerializationPort = jsonSerializationPort;
        this.transactionRunner = transactionRunner;
    }

    @Override
    public void onDocumentConfirmed(
            UUID userId,
            Document document,
            DocumentExtraction extraction,
            Transaction transaction,
            boolean saveUserCategoryPreference
    ) {
        transactionRunner.runInTransactionVoid(() -> {
            vectorIndexPort.deleteChunksForDocument(userId, document.getId());

            List<ChunkDraft> drafts = buildChunksForConfirmedDocument(document, extraction, transaction);
            UUID txId = transaction != null ? transaction.getId() : null;

            for (int i = 0; i < drafts.size(); i++) {
                ChunkDraft draft = drafts.get(i);
                vectorIndexPort.insertChunkWithVector(
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

            int verifiedCount = vectorIndexPort.countIndexedChunksForDocument(userId, document.getId());
            if (verifiedCount < drafts.size() || verifiedCount == 0) {
                throw new IllegalStateException("La verificación de indexación en pgvector falló; se conserva el archivo temporal para reintento (RF-08).");
            }

            deleteTemporaryFileAfterVerification(document);
            document.setStatus(DocumentStatus.INDEXED);
            documentRepository.save(document);

            if (saveUserCategoryPreference && transaction != null) {
                vectorIndexPort.recordUserCategoryCorrection(
                        userId,
                        transaction.getMerchantName(),
                        transaction.getMerchantName() + " " + (transaction.getItemsSummary() != null ? transaction.getItemsSummary() : ""),
                        transaction.getCategory(),
                        transaction.getBudgetBlock()
                );
            }
        });
    }

    @Override
    public void onManualExpenseRegistered(
            UUID userId,
            Transaction transaction,
            Document optionalAttachment,
            boolean saveUserCategoryPreference
    ) {
        transactionRunner.runInTransactionVoid(() -> {
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
            vectorIndexPort.insertChunkWithVector(
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
                int verifiedCount = vectorIndexPort.countIndexedChunksForTransaction(userId, transaction.getId());
                if (verifiedCount > 0) {
                    deleteTemporaryFileAfterVerification(optionalAttachment);
                    optionalAttachment.setStatus(DocumentStatus.INDEXED);
                    documentRepository.save(optionalAttachment);
                }
            }

            if (saveUserCategoryPreference) {
                vectorIndexPort.recordUserCategoryCorrection(
                        userId,
                        transaction.getMerchantName(),
                        transaction.getMerchantName() + " " + (transaction.getDescription() != null ? transaction.getDescription() : ""),
                        transaction.getCategory(),
                        transaction.getBudgetBlock()
                );
            }
        });
    }

    @Override
    public void onManualCategoryCorrection(UUID userId, Transaction transaction) {
        transactionRunner.runInTransactionVoid(() ->
                vectorIndexPort.recordUserCategoryCorrection(
                        userId,
                        transaction.getMerchantName(),
                        transaction.getMerchantName() + " " + (transaction.getDescription() != null ? transaction.getDescription() : ""),
                        transaction.getCategory(),
                        transaction.getBudgetBlock()
                )
        );
    }

    @Override
    public CategorySuggestion suggestCategoryForUser(UUID userId, String merchantName, String contextText) {
        return transactionRunner.runInReadOnlyTransaction(() ->
                vectorIndexPort.findUserCategorySuggestion(userId, merchantName, contextText)
        );
    }

    @Override
    public int reindexChunksForCurrentEmbeddingModel(UUID userId) {
        return transactionRunner.runInTransaction(() ->
                vectorIndexPort.reindexChunksForCurrentEmbeddingModel(userId)
        );
    }

    @Override
    public CleanupStats cleanupOrphanedTempFiles() {
        return tempFileStorage.cleanupOrphanedTempFiles();
    }

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

    private List<ChunkDraft> buildChunksForConfirmedDocument(
            Document doc,
            DocumentExtraction extraction,
            Transaction tx
    ) {
        List<ChunkDraft> chunks = new ArrayList<>();

        if (doc.getSourceType() == DocumentSourceType.STATEMENT_PDF) {
            String raw = extraction.getRawExtractedText() != null ? extraction.getRawExtractedText() : "";
            List<PageSection> sections = splitByPageMarkers(raw);
            for (PageSection pageSec : sections) {
                List<String> windows = splitIntoOverlappingWindows(pageSec.text(), 600, 90);
                for (String win : windows) {
                    String citation = TransactionApplicationService.buildCitationReference(doc, pageSec.pageNumber());
                    chunks.add(new ChunkDraft(
                            "STATEMENT_WINDOW",
                            pageSec.pageNumber(),
                            citation,
                            "Extracto bancario " + doc.getOriginalFilename() + " (Página " + pageSec.pageNumber() + "): " + win
                    ));
                }
            }
            if (chunks.isEmpty()) {
                String citation = TransactionApplicationService.buildCitationReference(doc, 1);
                chunks.add(new ChunkDraft("STATEMENT_WINDOW", 1, citation, "Extracto bancario: " + doc.getOriginalFilename()));
            }
            return chunks;
        }

        Integer page = (doc.getSourceType() == DocumentSourceType.PDF_DIGITAL || doc.getSourceType() == DocumentSourceType.PDF_SCANNED)
                ? 1
                : null;
        String citation = TransactionApplicationService.buildCitationReference(doc, page);

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

        List<ParsedLineItem> parsedItems = jsonSerializationPort.deserializeItemsJson(extraction.getItemsJson());
        if (!parsedItems.isEmpty()) {
            int groupSize = 5;
            for (int start = 0; start < parsedItems.size(); start += groupSize) {
                List<ParsedLineItem> subList = parsedItems.subList(start, Math.min(parsedItems.size(), start + groupSize));
                StringBuilder groupBuilder = new StringBuilder();
                groupBuilder.append("Detalle de ítems de factura ").append(extraction.getInvoiceNumber())
                        .append(" en ").append(extraction.getMerchantName())
                        .append(" (Fecha ").append(extraction.getIssueDate()).append("): ");
                for (ParsedLineItem item : subList) {
                    groupBuilder.append("[")
                            .append(item.description())
                            .append(" x").append(item.quantity())
                            .append(" = ").append(item.lineTotal())
                            .append(" COP] ");
                }
                chunks.add(new ChunkDraft("ITEM_GROUP", page, citation, groupBuilder.toString().trim()));
            }
        }

        if ((doc.getSourceType() == DocumentSourceType.PDF_DIGITAL || doc.getSourceType() == DocumentSourceType.PDF_SCANNED)
                && extraction.getRawExtractedText() != null
                && !extraction.getRawExtractedText().isBlank()) {
            List<PageSection> sections = splitByPageMarkers(extraction.getRawExtractedText());
            for (PageSection pageSec : sections) {
                List<String> windows = splitIntoOverlappingWindows(pageSec.text(), 600, 90);
                for (String win : windows) {
                    String pageCitation = TransactionApplicationService.buildCitationReference(doc, pageSec.pageNumber());
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

    private void deleteTemporaryFileAfterVerification(Document document) {
        String tempPath = document.getTempFilePath();
        if (tempPath != null && !tempPath.isBlank()) {
            tempFileStorage.deleteFileQuietly(tempPath);
        }
        document.setTempFileDeleted(true);
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

    private record PageSection(int pageNumber, String text) {
    }

    private record ChunkDraft(String chunkType, Integer pageNumber, String sourceReference, String contentText) {
    }
}
