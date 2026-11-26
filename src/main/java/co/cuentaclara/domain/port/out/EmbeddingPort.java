package co.cuentaclara.domain.port.out;

import java.util.Locale;

public interface EmbeddingPort {

    float[] generateEmbedding(String text);

    String modelName();

    String modelVersion();

    default boolean isRealSemanticEmbeddingActive() {
        return false;
    }

    default String toPgVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
