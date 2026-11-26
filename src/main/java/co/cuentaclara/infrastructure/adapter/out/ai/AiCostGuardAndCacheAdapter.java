package co.cuentaclara.infrastructure.adapter.out.ai;

import co.cuentaclara.domain.port.out.AiCacheAndCostGuardPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caché LRU de embeddings y control de cuota diaria de llamadas IA por usuario (RNF-10).
 */
@Component
public class AiCostGuardAndCacheAdapter implements AiCacheAndCostGuardPort {

    private static final int MAX_CACHE_SIZE = 1000;

    private final Map<String, float[]> embeddingCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
    );

    private final Map<String, AtomicInteger> dailyCallsByUser = new ConcurrentHashMap<>();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final int dailyUserCallLimit;

    @Autowired
    public AiCostGuardAndCacheAdapter(
            @Value("${cuentaclara.ai.daily-user-call-limit:60}") int dailyUserCallLimit
    ) {
        this.dailyUserCallLimit = Math.max(1, dailyUserCallLimit);
    }

    @Override
    public float[] getCachedEmbedding(String provider, String model, String text) {
        String key = buildCacheKey(provider, model, text);
        float[] hit = embeddingCache.get(key);
        if (hit != null) {
            cacheHits.incrementAndGet();
            return hit.clone();
        }
        cacheMisses.incrementAndGet();
        return null;
    }

    @Override
    public void putCachedEmbedding(String provider, String model, String text, float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        String key = buildCacheKey(provider, model, text);
        embeddingCache.put(key, vector.clone());
    }

    @Override
    public boolean tryConsumeDailyAiCall(UUID userId) {
        if (userId == null) {
            return true;
        }
        String bucketKey = userId + "|" + LocalDate.now();
        AtomicInteger counter = dailyCallsByUser.computeIfAbsent(bucketKey, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > dailyUserCallLimit) {
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    @Override
    public int getRemainingDailyCalls(UUID userId) {
        if (userId == null) {
            return dailyUserCallLimit;
        }
        AtomicInteger counter = dailyCallsByUser.get(userId + "|" + LocalDate.now());
        int used = counter != null ? counter.get() : 0;
        return Math.max(0, dailyUserCallLimit - used);
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public int getCacheSize() {
        return embeddingCache.size();
    }

    private String buildCacheKey(String provider, String model, String text) {
        String raw = (provider != null ? provider : "") + "|" + (model != null ? model : "") + "|" + (text != null ? text : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return String.valueOf(raw.hashCode());
        }
    }
}
