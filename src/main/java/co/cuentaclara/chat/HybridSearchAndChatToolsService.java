package co.cuentaclara.chat;

import co.cuentaclara.rag.EmbeddingProvider;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa las dos herramientas fijas y deterministas del chat (RF-12, RNF-06):
 * 1) consultar_transacciones(filtros): ejecuta SQL parametrizado para totales, conteos y listados con citas.
 * 2) buscar_documentos(texto): ejecuta búsqueda híbrida (pgvector + tsvector) fusionada con Reciprocal Rank Fusion (RRF).
 * El modelo nunca escribe SQL libre ni suma cifras por su cuenta.
 */
@Service
public class HybridSearchAndChatToolsService {

    private static final int RRF_K = 60;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingProvider embeddingProvider;

    public HybridSearchAndChatToolsService(
            JdbcTemplate jdbcTemplate,
            EmbeddingProvider embeddingProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Herramienta 1: consultar_transacciones(filtros) con parámetros validados y SQL parametrizado.
     */
    @Transactional(readOnly = true)
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

    /**
     * Herramienta 2: buscar_documentos(texto) con Búsqueda Híbrida (pgvector + tsvector)
     * fusionada mediante Reciprocal Rank Fusion (RRF) y filtrada por usuario antes de recuperar fragmentos (RF-12, RNF-01).
     */
    @Transactional(readOnly = true)
    public List<HybridChunkEvidence> buscarDocumentos(UUID userId, String queryText, int topK) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        int safeTopK = Math.max(1, Math.min(topK, 15));

        // 1. Búsqueda vectorial sobre pgvector (filtrada primero por user_id)
        float[] queryEmbedding = embeddingProvider.generateEmbedding(queryText);
        String vectorLiteral = embeddingProvider.toPgVectorLiteral(queryEmbedding);

        List<RankedChunk> vectorHits = jdbcTemplate.query(
                """
                SELECT id, chunk_type, page_number, source_reference, content_text,
                       (1 - (embedding <=> CAST(? AS vector))) AS vector_score
                FROM document_chunks
                WHERE user_id = ? AND embedding IS NOT NULL
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
                vectorLiteral, userId, vectorLiteral
        );

        // 2. Búsqueda textual exacta/léxica (tsvector en español + ILIKE para NIT, CUFE y nombres de comercios)
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

        // 3. Fusión con Reciprocal Rank Fusion: RRF_score(d) = 1 / (k + rank_vec) + 1 / (k + rank_lex)
        Map<UUID, FusedAccumulator> fusedMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            RankedChunk hit = vectorHits.get(i);
            int rank = i + 1;
            double rrfContribution = 1.0 / (RRF_K + rank);
            // Solo considerar aporte vectorial si tiene similitud positiva o vocabulario compartido
            if (hit.rawScore() > 0.10) {
                fusedMap.computeIfAbsent(hit.id(), id -> new FusedAccumulator(hit))
                        .addScore(rrfContribution, true);
            }
        }

        for (int i = 0; i < lexicalHits.size(); i++) {
            RankedChunk hit = lexicalHits.get(i);
            int rank = i + 1;
            // Le damos un peso ligeramente superior a coincidencias léxicas exactas de comercios/NIT/ítems
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
                        acc.matchedLexically
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

        private FusedAccumulator(RankedChunk chunk) {
            this.chunk = chunk;
        }

        void addScore(double delta, boolean fromVector) {
            this.rrfScore += delta;
            if (!fromVector) {
                this.matchedLexically = true;
            }
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

    public record TransactionQueryFilters(
            String yearMonth,
            LocalDate startDate,
            LocalDate endDate,
            String category,
            String budgetBlock,
            String merchantKeyword
    ) {
    }

    public record TransactionEvidenceRow(
            UUID transactionId,
            String merchantName,
            String merchantNit,
            String invoiceNumber,
            LocalDate transactionDate,
            BigDecimal subtotalAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String category,
            String budgetBlock,
            String description,
            String citationReference
    ) {
    }

    public record SqlTransactionsToolResult(
            BigDecimal totalAmount,
            BigDecimal totalTaxAmount,
            int count,
            List<TransactionEvidenceRow> rows
    ) {
    }

    public record HybridChunkEvidence(
            UUID chunkId,
            String chunkType,
            Integer pageNumber,
            String sourceReference,
            String contentText,
            double rrfScore,
            boolean matchedLexically
    ) {
    }
}
