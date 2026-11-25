package co.cuentaclara.rag;

/**
 * Interfaz de generación de embeddings versionados (1536 dimensiones) (RF-07, RNF-08).
 * Permite intercambiar el proveedor (OpenAI text-embedding-3-small u Ollama local después)
 * sin acoplar el dominio.
 */
public interface EmbeddingProvider {

    float[] generateEmbedding(String text);

    String modelName();

    String modelVersion();

    /**
     * Indica si el proveedor activo está usando un modelo semántico real (OpenAI u Ollama)
     * frente al vectorizador determinista local de respaldo para pruebas/offline.
     */
    default boolean isRealSemanticEmbeddingActive() {
        return false;
    }

    default String toPgVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
