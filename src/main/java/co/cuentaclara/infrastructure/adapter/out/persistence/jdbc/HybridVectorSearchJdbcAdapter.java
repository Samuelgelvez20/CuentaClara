package co.cuentaclara.infrastructure.adapter.out.persistence.jdbc;

import co.cuentaclara.domain.model.HybridChunkEvidence;
import co.cuentaclara.domain.model.SqlTransactionsToolResult;
import co.cuentaclara.domain.model.TransactionEvidenceRow;
import co.cuentaclara.domain.model.TransactionQueryFilters;
import co.cuentaclara.domain.port.out.EmbeddingPort;
import co.cuentaclara.domain.port.out.HybridVectorSearchPort;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HybridVectorSearchJdbcAdapter implements HybridVectorSearchPort {

    private static final int RRF_K = 60;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingPort embeddingProvider;

    public HybridVectorSearchJdbcAdapter(
            JdbcTemplate jdbcTemplate,
            EmbeddingPort embeddingProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public SqlTransactionsToolResult consultarTransacciones(UUID userId, TransactionQueryFilters filters) {
        StringBuilder where = new StringBuilder(" WHERE user_id = ? AND confirmed = TRUE ");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (filters.yearMonth() != null && !filters.yearMonth().isBlank()) {
            YearMonth ym = YearMonth.parse(filters.yearMonth().trim());
            where.append(" AND transaction_date >= ? AND transaction_date <= ? ");
            params.add(Date.valueOf(ym.atDay(1)));
            params.add(Date.valueOf(ym.atEndOfMonth()));
        } else {
            if (filters.startDate() != null) {
                where.append(" AND transaction_date >= ? ");
                params.add(Date.valueOf(filters.startDate()));
            }
            if (filters.endDate() != null) {
                where.append(" AND transaction_date <= ? ");
                params.add(Date.valueOf(filters.endDate()));
            }
        }

        if (filters.category() != null && !filters.category().isBlank()) {
            where.append(" AND LOWER(category) LIKE LOWER(?) ");
            params.add("%" + filters.category().trim() + "%");
        }
        if (filters.budgetBlock() != null && !filters.budgetBlock().isBlank()) {
            where.append(" AND UPPER(budget_block) = UPPER(?) ");
            params.add(filters.budgetBlock().trim());
        }
        if (filters.merchantKeyword() != null && !filters.merchantKeyword().isBlank()) {
            where.append(" AND (LOWER(merchant_name) LIKE LOWER(?) OR LOWER(COALESCE(merchant_nit, '')) LIKE LOWER(?) OR LOWER(COALESCE(invoice_number, '')) LIKE LOWER(?) OR LOWER(COALESCE(cufe, '')) LIKE LOWER(?)) ");
            String pattern = "%" + filters.merchantKeyword().trim() + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        String totalSql = "SELECT COALESCE(SUM(total_amount), 0) FROM transactions " + where;
        BigDecimal totalAmount = jdbcTemplate.queryForObject(totalSql, BigDecimal.class, params.toArray());

        String taxSql = "SELECT COALESCE(SUM(tax_amount), 0) FROM transactions " + where;
        BigDecimal totalTax = jdbcTemplate.queryForObject(taxSql, BigDecimal.class, params.toArray());

        String rowsSql = """
                SELECT id, merchant_name, merchant_nit, invoice_number, transaction_date,
                       subtotal_amount, tax_amount, total_amount, category, budget_block,
                       description, citation_reference
                FROM transactions
                """ + where + " ORDER BY total_amount DESC, transaction_date DESC LIMIT 20";

        List<TransactionEvidenceRow> rows = jdbcTemplate.query(
                rowsSql,
                (rs, rowNum) -> new TransactionEvidenceRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("merchant_name"),
                        rs.getString("merchant_nit"),
                        rs.getString("invoice_number"),
                        rs.getDate("transaction_date").toLocalDate(),
                        rs.getBigDecimal("subtotal_amount"),
                        rs.getBigDecimal("tax_amount"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("category"),
                        rs.getString("budget_block"),
                        rs.getString("description"),
                        rs.getString("citation_reference")
                ),
                params.toArray()
        );

        return new SqlTransactionsToolResult(
                totalAmount != null ? totalAmount : BigDecimal.ZERO,
                totalTax != null ? totalTax : BigDecimal.ZERO,
                rows.size(),
                rows
        );
    }

    @Override
    public List<HybridChunkEvidence> buscarDocumentos(UUID userId, String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        int safeTopK = Math.max(1, Math.min(topK, 15));

        float[] queryEmbedding = embeddingProvider.generateEmbedding(queryText);
        String vectorLiteral = embeddingProvider.toPgVectorLiteral(queryEmbedding);
        String activeModel = embeddingProvider.modelName();
        String activeVersion = embeddingProvider.modelVersion();

        List<RankedChunk> vectorHits = jdbcTemplate.query(
                """
                SELECT id, chunk_type, page_number, source_reference, content_text,
                       (1 - (embedding <=> CAST(? AS vector))) AS vector_score
                FROM document_chunks
                WHERE user_id = ?
                  AND embedding IS NOT NULL
                  AND embedding_model = ?
                  AND embedding_version = ?
                ORDER BY embedding <=> CAST(? AS vector) ASC
                LIMIT 20
                """,
                (rs, rowNum) -> new RankedChunk(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("chunk_type"),
                        rs.getObject("page_number") != null ? rs.getInt("page_number") : null,
                        rs.getString("source_reference"),
                        rs.getString("content_text"),
                        rs.getDouble("vector_score")
                ),
                vectorLiteral, userId, activeModel, activeVersion, vectorLiteral
        );

        String cleanKeywords = extractSearchKeywords(queryText);
        List<RankedChunk> lexicalHits = new ArrayList<>();
        if (!cleanKeywords.isBlank()) {
            lexicalHits = jdbcTemplate.query(
                    """
                    SELECT id, chunk_type, page_number, source_reference, content_text,
                           ts_rank_cd(content_tsv, websearch_to_tsquery('spanish', ?)) AS text_score
                    FROM document_chunks
                    WHERE user_id = ?
                      AND (
                          content_tsv @@ websearch_to_tsquery('spanish', ?)
                          OR LOWER(content_text) LIKE LOWER(?)
                          OR LOWER(source_reference) LIKE LOWER(?)
                      )
                    ORDER BY text_score DESC
                    LIMIT 20
                    """,
                    (rs, rowNum) -> new RankedChunk(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("chunk_type"),
                            rs.getObject("page_number") != null ? rs.getInt("page_number") : null,
                            rs.getString("source_reference"),
                            rs.getString("content_text"),
                            rs.getDouble("text_score")
                    ),
                    cleanKeywords, userId, cleanKeywords, "%" + cleanKeywords + "%", "%" + cleanKeywords + "%"
            );
        }

        Map<UUID, FusedAccumulator> fusedMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            RankedChunk hit = vectorHits.get(i);
            int rank = i + 1;
            double rrfContribution = 1.0 / (RRF_K + rank);
            if (hit.rawScore() > 0.10) {
                FusedAccumulator acc = fusedMap.computeIfAbsent(hit.id(), id -> new FusedAccumulator(hit));
                acc.addScore(rrfContribution, true);
                acc.setVectorScore(hit.rawScore());
            }
        }

        for (int i = 0; i < lexicalHits.size(); i++) {
            RankedChunk hit = lexicalHits.get(i);
            int rank = i + 1;
            double rrfContribution = 1.35 / (RRF_K + rank);
            fusedMap.computeIfAbsent(hit.id(), id -> new FusedAccumulator(hit))
                    .addScore(rrfContribution, false);
        }

        return fusedMap.values().stream()
                .sorted(Comparator.comparingDouble(FusedAccumulator::rrfScore).reversed())
                .limit(safeTopK)
                .map(acc -> new HybridChunkEvidence(
                        acc.chunk.id(),
                        acc.chunk.chunkType(),
                        acc.chunk.pageNumber(),
                        acc.chunk.sourceReference(),
                        acc.chunk.contentText(),
                        acc.rrfScore(),
                        acc.matchedLexically,
                        acc.vectorScore
                ))
                .toList();
    }

    private String extractSearchKeywords(String rawQuery) {
        String lower = rawQuery.toLowerCase(Locale.ROOT)
                .replaceAll("[¿?¡!.,;:()\"']", " ");
        List<String> stopWords = List.of(
                "cuanto", "cuánto", "gaste", "gasté", "gasto", "gastos", "en", "el", "la", "los", "las",
                "de", "del", "por", "para", "con", "mi", "mis", "que", "qué", "cual", "cuál", "cuales",
                "cuáles", "fue", "fueron", "hay", "tengo", "factura", "facturas", "mes", "octubre",
                "noviembre", "septiembre", "total", "suma", "compra", "compras", "pague", "pagué", "pago",
                "donde", "dónde", "como", "cómo", "dice", "aparece", "muestra", "dime"
        );
        List<String> kept = new ArrayList<>();
        for (String token : lower.split("\\s+")) {
            if (token.length() >= 2 && !stopWords.contains(token)) {
                kept.add(token);
            }
        }
        return String.join(" ", kept);
    }

    private static final class FusedAccumulator {
        private final RankedChunk chunk;
        private double rrfScore = 0.0;
        private boolean matchedLexically = false;
        private double vectorScore = 0.0;

        private FusedAccumulator(RankedChunk chunk) {
            this.chunk = chunk;
        }

        void addScore(double delta, boolean fromVector) {
            this.rrfScore += delta;
            if (!fromVector) {
                this.matchedLexically = true;
            }
        }

        void setVectorScore(double score) {
            this.vectorScore = Math.max(this.vectorScore, score);
        }

        double rrfScore() {
            return rrfScore;
        }
    }

    private record RankedChunk(
            UUID id,
            String chunkType,
            Integer pageNumber,
            String sourceReference,
            String contentText,
            double rawScore
    ) {
    }
}
