package co.cuentaclara.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Enrutador intercambiable de {@link EmbeddingProvider} con caché LRU integrada (RNF-08, RNF-10).
 * Permite cambiar entre `openai`, `ollama` y `local` mediante `cuentaclara.ai.provider`
 * sin acoplar el dominio ni las migraciones pgvector.
 */
@Primary
@Component
public class RoutingEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(RoutingEmbeddingProvider.class);

    private final String providerMode;
    private final OpenAiCompatibleEmbeddingProvider openAiProvider;
    private final OllamaEmbeddingProvider ollamaProvider;
    private final OpenAiCompatibleEmbeddingProvider localFallbackProvider;
    private final AiCostGuardAndCacheService costGuardAndCache;
    private final AtomicBoolean fellBackToLocal = new AtomicBoolean(false);

    @Autowired
    public RoutingEmbeddingProvider(
            @Value("${cuentaclara.ai.provider:openai}") String providerMode,
            OpenAiCompatibleEmbeddingProvider openAiProvider,
            OllamaEmbeddingProvider ollamaProvider,
            AiCostGuardAndCacheService costGuardAndCache,
            ObjectMapper objectMapper
    ) {
        this.providerMode = providerMode != null ? providerMode.trim().toLowerCase() : "openai";
        this.openAiProvider = openAiProvider;
        this.ollamaProvider = ollamaProvider;
        this.localFallbackProvider = new OpenAiCompatibleEmbeddingProvider("", "local-semantic-v1", "v1", objectMapper);
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
            case "local" -> "local-semantic-v1";
            default -> openAiProvider.modelName();
        };
    }

    @Override
    public String modelVersion() {
        return "ollama".equals(providerMode) ? ollamaProvider.modelVersion() : openAiProvider.modelVersion();
    }

    @Override
    public boolean isRealSemanticEmbeddingActive() {
        if ("local".equals(providerMode)) {
            return false;
        }
        if ("ollama".equals(providerMode)) {
            return !fellBackToLocal.get();
        }
        return openAiProvider.isRealSemanticEmbeddingActive();
    }
}
