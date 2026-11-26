package co.cuentaclara.infrastructure.adapter.out.ai;

import co.cuentaclara.domain.port.out.EmbeddingPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proveedor gratuito en la nube de embeddings semánticos de 1536 dimensiones usando Google Gemini ({@code gemini-embedding-001}).
 */
@Component
public class GeminiEmbeddingAdapter implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingAdapter.class);
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;
    private final String version;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiEmbeddingAdapter(
            @Value("${cuentaclara.ai.gemini-api-key:}") String apiKey,
            @Value("${cuentaclara.ai.gemini-embedding-model:gemini-embedding-001}") String model,
            @Value("${cuentaclara.ai.gemini-embedding-version:v1}") String version,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = (model != null && !model.isBlank()) ? model.trim() : "gemini-embedding-001";
        this.version = (version != null && !version.isBlank()) ? version.trim() : "v1";
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    @Override
    public float[] generateEmbedding(String text) {
        String normalizedText = text != null ? text.trim() : "";
        if (isRealSemanticEmbeddingActive()) {
            try {
                return callGeminiEmbeddingsApi(normalizedText);
            } catch (Exception ex) {
                log.warn("Fallo llamada a Gemini Embeddings API ({}), usando vectorizador semántico local de respaldo: {}",
                        model, ex.getMessage());
            }
        }
        return OpenAiEmbeddingAdapter.computeSemanticDeterministicEmbedding(normalizedText);
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String modelVersion() {
        return version;
    }

    @Override
    public boolean isRealSemanticEmbeddingActive() {
        return !apiKey.isBlank() && !apiKey.contains("tu-gemini-api-key");
    }

    private float[] callGeminiEmbeddingsApi(String input) throws Exception {
        String modelPath = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String payload = objectMapper.writeValueAsString(Map.of(
                "model", "models/" + modelPath,
                "content", Map.of("parts", List.of(Map.of("text", input.isBlank() ? " " : input))),
                "outputDimensionality", OpenAiEmbeddingAdapter.DIMENSIONS
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_BASE_URL + modelPath + ":embedContent"))
                .timeout(Duration.ofSeconds(12))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Gemini Embedding HTTP " + res.statusCode());
        }

        JsonNode values = objectMapper.readTree(res.body()).path("embedding").path("values");
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("Respuesta vacía de Gemini Embedding");
        }

        float[] vector = new float[OpenAiEmbeddingAdapter.DIMENSIONS];
        for (int i = 0; i < Math.min(OpenAiEmbeddingAdapter.DIMENSIONS, values.size()); i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return OpenAiEmbeddingAdapter.normalizeL2(vector);
    }
}
