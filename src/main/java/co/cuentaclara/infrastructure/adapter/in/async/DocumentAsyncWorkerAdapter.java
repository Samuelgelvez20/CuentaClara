package co.cuentaclara.infrastructure.adapter.in.async;

import co.cuentaclara.application.service.DocumentIngestionApplicationService;
import co.cuentaclara.domain.exception.DuplicateInvoiceException;
import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.port.in.DocumentIngestionUseCase;
import co.cuentaclara.domain.port.out.DocumentJobQueuePort;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import co.cuentaclara.domain.port.out.TempFileStoragePort;
import co.cuentaclara.domain.port.out.TenantExecutionPort;
import co.cuentaclara.infrastructure.adapter.out.document.DocumentAsyncDispatchEvent;
import co.cuentaclara.infrastructure.adapter.out.document.LocalTempFileStorageAdapter;
import co.cuentaclara.infrastructure.adapter.out.document.RedisDocumentJobQueueAdapter;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentAsyncWorkerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DocumentAsyncWorkerAdapter.class);

    private final DocumentIngestionUseCase documentIngestionUseCase;
    private final DocumentRepositoryPort documentRepository;
    private final DocumentJobQueuePort jobQueuePort;
    private final TempFileStoragePort tempFileStorage;
    private final TenantExecutionPort tenantExecutionPort;
    private final LocalTempFileStorageAdapter localTempFileStorageAdapter;
    private final StringRedisTemplate redisTemplate;

    @Value("${cuentaclara.worker.consumer-enabled:true}")
    private boolean consumerEnabled = true;

    private volatile boolean shuttingDown = false;

    public DocumentAsyncWorkerAdapter(
            DocumentIngestionUseCase documentIngestionUseCase,
            DocumentRepositoryPort documentRepository,
            DocumentJobQueuePort jobQueuePort,
            TempFileStoragePort tempFileStorage,
            TenantExecutionPort tenantExecutionPort,
            LocalTempFileStorageAdapter localTempFileStorageAdapter,
            @Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this.documentIngestionUseCase = documentIngestionUseCase;
        this.documentRepository = documentRepository;
        this.jobQueuePort = jobQueuePort;
        this.tempFileStorage = tempFileStorage;
        this.tenantExecutionPort = tenantExecutionPort;
        this.localTempFileStorageAdapter = localTempFileStorageAdapter;
        this.redisTemplate = redisTemplate;
    }

    @PreDestroy
    void onShutdown() {
        this.shuttingDown = true;
    }

    @Async
    @EventListener
    public void onAsyncDispatch(DocumentAsyncDispatchEvent event) {
        try {
            documentIngestionUseCase.processQueuedDocument(event.userId(), event.documentId());
        } catch (DuplicateInvoiceException dupEx) {
            log.info("Documento duplicado rechazado en procesamiento async ({}): {}", event.documentId(), dupEx.getMessage());
        }
    }

    @Scheduled(fixedDelay = 300)
    public void pollRedisQueue() {
        if (shuttingDown || !consumerEnabled || redisTemplate == null) {
            return;
        }
        for (int i = 0; i < 10 && !shuttingDown; i++) {
            if (!consumeNextQueuedJob()) {
                break;
            }
        }
    }

    public boolean consumeNextQueuedJob() {
        if (redisTemplate == null) {
            return false;
        }
        String payload;
        try {
            payload = redisTemplate.opsForList().rightPop(RedisDocumentJobQueueAdapter.REDIS_QUEUE_KEY);
        } catch (Exception ex) {
            log.debug("No se pudo leer la cola Redis {}: {}", RedisDocumentJobQueueAdapter.REDIS_QUEUE_KEY, ex.getMessage());
            return false;
        }
        if (payload == null || payload.isBlank()) {
            return false;
        }

        String[] parts = payload.trim().split(":", 2);
        if (parts.length != 2) {
            log.warn("Carga inválida en cola Redis {}: {}", RedisDocumentJobQueueAdapter.REDIS_QUEUE_KEY, payload);
            return true;
        }
        try {
            UUID userId = UUID.fromString(parts[0]);
            UUID documentId = UUID.fromString(parts[1]);
            documentIngestionUseCase.processQueuedDocument(userId, documentId);
        } catch (DuplicateInvoiceException dupEx) {
            log.info("Documento duplicado rechazado al consumir cola ({}): {}", payload, dupEx.getMessage());
        } catch (Exception ex) {
            log.warn("Error procesando trabajo Redis {}: {}", payload, ex.getMessage());
        }
        return true;
    }

    @Scheduled(fixedDelay = 300_000)
    public void scheduledTempCleanup() {
        tempFileStorage.cleanupOrphanedTempFiles();
    }

    @Scheduled(fixedDelay = 120_000)
    public int retryPendingAndFailedDocuments() {
        return retryPendingAndFailedDocuments(Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    public int retryPendingAndFailedDocuments(Duration errorBackoff, Duration stuckProcessingThreshold) {
        Path tempRoot = localTempFileStorageAdapter.getTempStorageRoot();
        if (shuttingDown || !Files.exists(tempRoot) || !Files.isDirectory(tempRoot)) {
            return 0;
        }

        int requeued = 0;
        OffsetDateTime now = OffsetDateTime.now();

        try (Stream<Path> rootEntries = Files.list(tempRoot)) {
            List<Path> userDirs = rootEntries.filter(Files::isDirectory).toList();
            for (Path userDir : userDirs) {
                UUID userId;
                try {
                    userId = UUID.fromString(userDir.getFileName().toString());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                requeued += tenantExecutionPort.runAsUser(userId, () -> {
                    int userRequeued = 0;
                    List<Document> docs = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
                    for (Document doc : docs) {
                        if (doc.isTempFileDeleted() || !tempFileStorage.exists(doc.getTempFilePath())) {
                            continue;
                        }
                        boolean retryableError = doc.getStatus() == DocumentStatus.ERROR
                                && doc.getRetryCount() < DocumentIngestionApplicationService.MAX_RETRIES
                                && isBeforeCutoff(doc.getUpdatedAt(), now, errorBackoff);
                        boolean stuckProcessing = doc.getStatus() == DocumentStatus.PROCESSING
                                && doc.getRetryCount() < DocumentIngestionApplicationService.MAX_RETRIES
                                && isBeforeCutoff(doc.getUpdatedAt(), now, stuckProcessingThreshold);
                        if (retryableError || stuckProcessing) {
                            doc.setStatus(DocumentStatus.PENDING);
                            documentRepository.save(doc);
                            jobQueuePort.enqueueJob(userId, doc.getId());
                            userRequeued++;
                        }
                    }
                    return userRequeued;
                });
            }
        } catch (IOException ex) {
            log.warn("Error escaneando reintentos pendientes en {}: {}", tempRoot, ex.getMessage());
        }

        return requeued;
    }

    private boolean isBeforeCutoff(OffsetDateTime updatedAt, OffsetDateTime now, Duration threshold) {
        if (threshold.isZero() || threshold.isNegative() || updatedAt == null) {
            return true;
        }
        return updatedAt.plus(threshold).isBefore(now);
    }
}
