package co.cuentaclara.infrastructure.adapter.out.persistence.jdbc;

import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.CategorySuggestion;
import co.cuentaclara.domain.port.out.EmbeddingPort;
import co.cuentaclara.domain.port.out.VectorIndexPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VectorIndexJdbcAdapter implements VectorIndexPort {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingPort embeddingProvider;

    public VectorIndexJdbcAdapter(JdbcTemplate jdbcTemplate, EmbeddingPort embeddingProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public void deleteChunksForDocument(UUID userId, UUID documentId) {
        jdbcTemplate.update(
                "DELETE FROM document_chunks WHERE user_id = ? AND document_id = ?",
                userId, documentId
        );
    }

    @Override
    public void insertChunkWithVector(
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

    @Override
    public int countIndexedChunksForDocument(UUID userId, UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE user_id = ? AND document_id = ? AND embedding IS NOT NULL",
                Integer.class,
                userId, documentId
        );
        return count != null ? count : 0;
    }

    @Override
    public int countIndexedChunksForTransaction(UUID userId, UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE user_id = ? AND transaction_id = ? AND embedding IS NOT NULL",
                Integer.class,
                userId, transactionId
        );
        return count != null ? count : 0;
    }

    @Override
    public CategorySuggestion findUserCategorySuggestion(UUID userId, String merchantName, String contextText) {
        String queryText = ((merchantName != null ? merchantName : "") + " " + (contextText != null ? contextText : "")).trim();
        float[] queryEmbedding = embeddingProvider.generateEmbedding(queryText);
        String vectorLiteral = embeddingProvider.toPgVectorLiteral(queryEmbedding);

        List<CategorySuggestion> matches = jdbcTemplate.query(
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
                        return new CategorySuggestion(
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
        return new CategorySuggestion("Otros", BudgetBlock.WANTS, "Categoría base por defecto");
    }

    @Override
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

    @Override
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
}
