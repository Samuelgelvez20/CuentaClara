package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.CategorySuggestion;
import java.util.UUID;

public interface VectorIndexPort {

    void deleteChunksForDocument(UUID userId, UUID documentId);

    void insertChunkWithVector(
            UUID userId,
            UUID documentId,
            UUID transactionId,
            String chunkType,
            int chunkIndex,
            Integer pageNumber,
            String sourceReference,
            String contentText
    );

    int countIndexedChunksForDocument(UUID userId, UUID documentId);

    int countIndexedChunksForTransaction(UUID userId, UUID transactionId);

    CategorySuggestion findUserCategorySuggestion(UUID userId, String merchantName, String contextText);

    void recordUserCategoryCorrection(
            UUID userId,
            String merchantName,
            String contextText,
            String confirmedCategory,
            BudgetBlock confirmedBlock
    );

    int reindexChunksForCurrentEmbeddingModel(UUID userId);
}
