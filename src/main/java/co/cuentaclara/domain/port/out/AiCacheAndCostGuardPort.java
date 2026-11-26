package co.cuentaclara.domain.port.out;

import java.util.UUID;

public interface AiCacheAndCostGuardPort {

    float[] getCachedEmbedding(String provider, String model, String text);

    void putCachedEmbedding(String provider, String model, String text, float[] vector);

    boolean tryConsumeDailyAiCall(UUID userId);

    int getRemainingDailyCalls(UUID userId);
}
