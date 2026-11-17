package co.cuentaclara.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proveedor de embeddings de 1536 dimensiones (RNF-08).
 * Usa la API de OpenAI (text-embedding-3-small) cuando OPENAI_API_KEY está configurada,
 * y un vectorizador semántico determinista de 1536 dimensiones cuando corre en local/pruebas sin clave externa.
 */
@Component
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingProvider.class);
    public static final int DIMENSIONS = 1536;

    // Clústeres semánticos para proyectar conceptos equivalentes (ej. Rappi / Uber Eats -> Domicilios) en las primeras dimensiones
    private static final Map<Integer, List<String>> SEMANTIC_CONCEPTS = Map.of(
            0, List.of("domicilio", "domicilios", "rappi", "uber eats", "didi food", "restaurante", "hamburguesa", "pizza", "sushi", "almuerzo", "cena", "comida fuera"),
            1, List.of("mercado", "supermercado", "alimentacion", "alimentación", "exito", "éxito", "carulla", "d1", "ara", "olimpica", "olímpica", "alkosto", "cafe", "café", "despensa", "leche", "arroz"),
            2, List.of("servicios", "energia", "energía", "agua", "acueducto", "gas", "internet", "fibra", "claro", "movistar", "tigo", "epm", "enel", "vanti", "codensa", "celular"),
            3, List.of("transporte", "gasolina", "combustible", "terpel", "primax", "biomax", "uber", "cabify", "taxi", "peaje", "bus", "metro"),
            4, List.of("salud", "drogueria", "droguería", "farmacia", "farmatodo", "cruz verde", "medicamento", "medicina", "consulta", "odontologia"),
            5, List.of("vivienda", "arriendo", "alquiler", "administracion", "administración", "hipoteca", "apartamento"),
            6, List.of("extracto", "bancario", "bancolombia", "davivienda", "nequi", "saldo", "cuota", "manejo", "transferencia", "interes", "interés")
    );

    private final String apiKey;
    private final String model;
    private final String version;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleEmbeddingProvider(
            @Value("${cuentaclara.ai.api-key:}") String apiKey,
            @Value("${cuentaclara.ai.embedding-model:text-embedding-3-small}") String model,
            @Value("${cuentaclara.ai.embedding-version:v1}") String version,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model;
        this.version = version;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    @Override
    public float[] generateEmbedding(String text) {
        String normalizedText = text != null ? text.trim() : "";
        if (isLiveOpenAiKeyConfigured()) {
            try {
                return callOpenAiEmbeddingsApi(normalizedText);
            } catch (Exception ex) {
                log.warn("Fallo llamada a OpenAI Embeddings API, usando vectorizador semántico local de respaldo: {}", ex.getMessage());
            }
        }
        return computeSemanticDeterministicEmbedding(normalizedText);
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String modelVersion() {
        return version;
    }

    private boolean isLiveOpenAiKeyConfigured() {
        return !apiKey.isBlank()
                && apiKey.startsWith("sk-")
                && !apiKey.contains("tu-api-key");
    }

    private float[] callOpenAiEmbeddingsApi(String input) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "input", input
        ));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/embeddings"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("OpenAI HTTP " + res.statusCode());
        }
        JsonNode data = objectMapper.readTree(res.body()).path("data").get(0).path("embedding");
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < Math.min(DIMENSIONS, data.size()); i++) {
            vector[i] = (float) data.get(i).asDouble();
        }
        return normalizeL2(vector);
    }

    public static float[] computeSemanticDeterministicEmbedding(String text) {
        float[] vec = new float[DIMENSIONS];
        String lower = text.toLowerCase(Locale.ROOT);

        // 1. Proyección en ejes conceptuales (dimensiones 0..63) para alta similitud semántica (ej. Rappi <-> Uber Eats <-> Domicilios)
        SEMANTIC_CONCEPTS.forEach((clusterIdx, keywords) -> {
            for (String kw : keywords) {
                if (lower.contains(kw)) {
                    int baseDim = clusterIdx * 8;
                    for (int offset = 0; offset < 8; offset++) {
                        vec[baseDim + offset] += 3.5f;
                    }
                }
            }
        });

        // 2. Proyección de tokens y trigramas (dimensiones 64..1535)
        String[] tokens = lower.split("[^a-záéíóúñ0-9]+");
        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            int hash = Math.abs(token.hashCode());
            int dim1 = 64 + (hash % (DIMENSIONS - 64));
            int dim2 = 64 + ((hash / 31) % (DIMENSIONS - 64));
            vec[dim1] += 1.5f;
            vec[dim2] += 0.8f;
        }

        // Asegurar que ningún vector quede en cero absoluto
        vec[DIMENSIONS - 1] += 0.01f;
        return normalizeL2(vec);
    }

    private static float[] normalizeL2(float[] vector) {
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }
        double norm = Math.sqrt(sumSq);
        if (norm < 1e-9) {
            return vector;
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }
}
