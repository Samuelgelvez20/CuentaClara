package co.cuentaclara.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proveedor intercambiable de embeddings locales mediante Ollama (`/api/embeddings`) (RNF-08).
 * Normaliza y adapta el vector devuelto (p. ej. nomic-embed-text de 768 dims) al espacio
 * pgvector de 1536 dimensiones de CuentaClara manteniendo longitud unitaria.
 */
@Component
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    public static final int TARGET_DIMENSIONS = OpenAiCompatibleEmbeddingProvider.DIMENSIONS;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final String version;

    @Autowired
    public OllamaEmbeddingProvider(
            ObjectMapper objectMapper,
            @Value("${cuentaclara.ai.ollama-base-url:http://localhost:11434}") String baseUrl,
            @Value("${cuentaclara.ai.ollama-embedding-model:nomic-embed-text}") String model,
            @Value("${cuentaclara.ai.embedding-version:v1}") String version
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "http://localhost:11434";
        this.model = model != null && !model.isBlank() ? model : "nomic-embed-text";
        this.version = version != null && !version.isBlank() ? version : "v1";
    }

    @Override
    public float[] generateEmbedding(String text) {
        String normalized = text != null ? text.trim() : "";
        try {
            float[] raw = requestOllamaEmbedding(normalized);
            return adaptAndNormalizeTo1536(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Error invocando Ollama Embeddings en " + baseUrl + ": " + ex.getMessage(), ex);
        }
    }

    protected float[] requestOllamaEmbedding(String input) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "prompt", input
        ));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embeddings"))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Ollama HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode vecNode = root.path("embedding");
        if (!vecNode.isArray() || vecNode.isEmpty()) {
            throw new IllegalStateException("Respuesta Ollama sin campo 'embedding'");
        }
        float[] vec = new float[vecNode.size()];
        for (int i = 0; i < vecNode.size(); i++) {
            vec[i] = (float) vecNode.get(i).asDouble();
        }
        return vec;
    }

    static float[] adaptAndNormalizeTo1536(float[] source) {
        float[] target = new float[TARGET_DIMENSIONS];
        int copyLen = Math.min(source.length, TARGET_DIMENSIONS);
        double normSq = 0.0;
        for (int i = 0; i < copyLen; i++) {
            target[i] = source[i];
            normSq += (double) target[i] * target[i];
        }
        if (normSq > 0.0) {
            float norm = (float) Math.sqrt(normSq);
            for (int i = 0; i < copyLen; i++) {
                target[i] /= norm;
            }
        }
        return target;
    }

    @Override
    public String modelName() {
        return "ollama:" + model;
    }

    @Override
    public String modelVersion() {
        return version;
    }
}
