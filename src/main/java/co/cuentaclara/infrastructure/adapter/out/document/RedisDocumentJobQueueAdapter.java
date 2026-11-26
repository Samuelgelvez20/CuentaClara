package co.cuentaclara.infrastructure.adapter.out.document;

import co.cuentaclara.domain.port.out.DocumentJobQueuePort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisDocumentJobQueueAdapter implements DocumentJobQueuePort {

    private static final Logger log = LoggerFactory.getLogger(RedisDocumentJobQueueAdapter.class);
    public static final String REDIS_QUEUE_KEY = "cuentaclara:queue:document-jobs";

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${cuentaclara.worker.inline-async-fallback:true}")
    private boolean inlineAsyncFallback = true;

    public RedisDocumentJobQueueAdapter(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public boolean enqueueJob(UUID userId, UUID documentId) {
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForList().leftPush(REDIS_QUEUE_KEY, userId + ":" + documentId);
                return true;
            } catch (Exception ex) {
                log.debug("Redis no disponible localmente, usando fallback async: {}", ex.getMessage());
            }
        }
        return false;
    }

    @Override
    public boolean isInlineAsyncFallbackEnabled() {
        return inlineAsyncFallback;
    }

    @Override
    public void dispatchAsyncProcessing(UUID userId, UUID documentId) {
        eventPublisher.publishEvent(new DocumentAsyncDispatchEvent(userId, documentId));
    }

    public long getQueueDepth() {
        if (redisTemplate == null) {
            return 0L;
        }
        try {
            Long size = redisTemplate.opsForList().size(REDIS_QUEUE_KEY);
            return size != null ? size : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }
}
