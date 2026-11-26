package co.cuentaclara.domain.model;

import java.util.UUID;

public record HybridChunkEvidence(
        UUID chunkId,
        String chunkType,
        Integer pageNumber,
        String sourceReference,
        String contentText,
        double rrfScore,
        boolean matchedLexically,
        double vectorScore
) {
    public HybridChunkEvidence(
            UUID chunkId,
            String chunkType,
            Integer pageNumber,
            String sourceReference,
            String contentText,
            double rrfScore,
            boolean matchedLexically
    ) {
        this(chunkId, chunkType, pageNumber, sourceReference, contentText, rrfScore, matchedLexically, 0.0d);
    }
}
