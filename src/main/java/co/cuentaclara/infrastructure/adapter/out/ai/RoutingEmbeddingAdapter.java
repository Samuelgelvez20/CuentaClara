package co.cuentaclara.infrastructure.adapter.out.ai;

import co.cuentaclara.domain.port.out.AiCacheAndCostGuardPort;
import co.cuentaclara.domain.port.out.EmbeddingPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Enrutador intercambiable de {@link EmbeddingPort} con caché LRU integrada (RNF-08, RNF-10).
 */
@Primary
@Component
public class RoutingEmbeddingAdapter implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(RoutingEmbeddingAdapter.class);

    private final String providerMode;
    private final OpenAiEmbeddingAdapter openAiProvider;
    private final OllamaEmbeddingAdapter ollamaProvider;
    private final GeminiEmbeddingAdapter geminiProvider;
    private final OpenAiEmbeddingAdapter localFallbackProvider;
    private final AiCacheAndCostGuardPort costGuardAndCache;
    private final AtomicBoolean fellBackToLocal = new AtomicBoolean(false);

    public RoutingEmbeddingAdapter(
            String providerMode,
            OpenAiEmbeddingAdapter openAiProvider,
            OllamaEmbeddingAdapter ollamaProvider,
            AiCacheAndCostGuardPort costGuardAndCache,
            ObjectMapper objectMapper
    ) {
        this(
                providerMode,
                openAiProvider,
                ollamaProvider,
                new GeminiEmbeddingAdapter("", "gemini-embedding-001", "v1", objectMapper),
                costGuardAndCache,
                objectMapper
        );
    }

    @Autowired
    public RoutingEmbeddingAdapter(
            @Value("${cuentaclara.ai.provider:openai}") String providerMode,
            OpenAiEmbeddingAdapter openAiProvider,
            OllamaEmbeddingAdapter ollamaProvider,
            GeminiEmbeddingAdapter geminiProvider,
            AiCacheAndCostGuardPort costGuardAndCache,
            ObjectMapper objectMapper
    ) {
        this.providerMode = providerMode != null ? providerMode.trim().toLowerCase() : "openai";
        this.openAiProvider = openAiProvider;
        this.ollamaProvider = ollamaProvider;
        this.geminiProvider = geminiProvider;
        this.localFallbackProvider = new OpenAiEmbeddingAdapter("", "local-semantic-v1", "v1", objectMapper);
        this.costGuardAndCache = costGuardAndCache;
    }

    @Override
    public float[] generateEmbedding(String text) {
        String normalized = text != null ? text.trim() : "";
        String activeModel = modelName();

        float[] cached = costGuardAndCache.getCachedEmbedding(providerMode, activeModel, normalized);
        if (cached != null) {
            return cached;
        }

        float[] computed;
        if ("ollama".equals(providerMode)) {
            try {
                computed = ollamaProvider.generateEmbedding(normalized);
                fellBackToLocal.set(false);
            } catch (Exception ex) {
                log.warn("Ollama no disponible ({}), usando respaldo semántico local: {}", activeModel, ex.getMessage());
                fellBackToLocal.set(true);
                computed = localFallbackProvider.generateEmbedding(normalized);
            }
        } else if ("gemini".equals(providerMode)) {
            fellBackToLocal.set(!geminiProvider.isRealSemanticEmbeddingActive());
            computed = geminiProvider.generateEmbedding(normalized);
        } else if ("local".equals(providerMode)) {
            fellBackToLocal.set(true);
            computed = localFallbackProvider.generateEmbedding(normalized);
        } else {
            fellBackToLocal.set(!openAiProvider.isRealSemanticEmbeddingActive());
            computed = openAiProvider.generateEmbedding(normalized);
        }

        costGuardAndCache.putCachedEmbedding(providerMode, activeModel, normalized, computed);
        return computed;
    }

    @Override
    public String modelName() {
        return switch (providerMode) {
            case "ollama" -> ollamaProvider.modelName();
            case "gemini" -> geminiProvider.modelName();
            case "local" -> "local-semantic-v1";
            default -> openAiProvider.modelName();
        };
    }

    @Override
    public String modelVersion() {
        return switch (providerMode) {
            case "ollama" -> ollamaProvider.modelVersion();
            case "gemini" -> geminiProvider.modelVersion();
            default -> openAiProvider.modelVersion();
        };
    }

    @Override
    public boolean isRealSemanticEmbeddingActive() {
        if ("local".equals(providerMode)) {
            return false;
        }
        if ("ollama".equals(providerMode)) {
            return !fellBackToLocal.get();
        }
        if ("gemini".equals(providerMode)) {
            return geminiProvider.isRealSemanticEmbeddingActive();
        }
        return openAiProvider.isRealSemanticEmbeddingActive();
    }
}
