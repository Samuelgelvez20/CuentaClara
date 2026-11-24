package co.cuentaclara.document;

import co.cuentaclara.security.rls.RlsDataSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Gestión periódica del ciclo de vida de archivos temporales y reintentos diferidos (RF-08, RNF-05).
 * Opera por subdirectorio de usuario bajo `TenantContext` explícito para respetar estrictamente
 * PostgreSQL Row Level Security (RNF-01).
 */
@Service
public class TempFileLifecycleAndRetryService {

    private static final Logger log = LoggerFactory.getLogger(TempFileLifecycleAndRetryService.class);

    private final DocumentRepository documentRepository;
    private final DocumentIngestionWorker ingestionWorker;
    private final Path tempStorageRoot;
    private volatile boolean shuttingDown = false;

    @jakarta.annotation.PreDestroy
    void onShutdown() {
        this.shuttingDown = true;
    }

    public TempFileLifecycleAndRetryService(
            DocumentRepository documentRepository,
            @Lazy DocumentIngestionWorker ingestionWorker,
            @Value("${cuentaclara.storage.temp-dir:./tmp/uploads}") String tempStorageDir
    ) {
        this.documentRepository = documentRepository;
        this.ingestionWorker = ingestionWorker;
        this.tempStorageRoot = Path.of(tempStorageDir).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelay = 300_000)
    public CleanupStats cleanupOrphanedTempFiles() {
        return cleanupOrphanedTempFiles(
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                Duration.ofHours(24)
        );
    }

    /**
     * Limpia archivos temporales de documentos ya indexados, errores agotados, cargas abandonadas
     * y archivos huérfanos sin registro en BD (por ejemplo tras eliminar la cuenta o fallar una subida).
     */
    public CleanupStats cleanupOrphanedTempFiles(
            Duration unreferencedOrphanGrace,
            Duration exhaustedErrorGrace,
            Duration abandonedPendingGrace
    ) {
        if (shuttingDown || !Files.exists(tempStorageRoot) || !Files.isDirectory(tempStorageRoot)) {
            return new CleanupStats(0, 0);
        }

        int deletedFiles = 0;
        int deletedDirectories = 0;
        Instant now = Instant.now();

        try (Stream<Path> rootEntries = Files.list(tempStorageRoot)) {
            List<Path> entries = rootEntries.toList();
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    if (isOlderThan(entry, now, unreferencedOrphanGrace) && deleteQuietly(entry)) {
                        deletedFiles++;
                    }
                    continue;
                }
                if (!Files.isDirectory(entry)) {
                    continue;
                }

                UUID userId = parseUuidOrNull(entry.getFileName().toString());
                if (userId == null) {
                    continue;
                }

                UserCleanupResult userResult = RlsDataSourceConfig.runAsUser(userId, () ->
                        cleanupUserDirectory(entry, userId, now, unreferencedOrphanGrace, exhaustedErrorGrace, abandonedPendingGrace)
                );
                deletedFiles += userResult.deletedFiles();
                if (userResult.directoryRemoved()) {
                    deletedDirectories++;
                }
            }
        } catch (IOException ex) {
            log.warn("No se pudo recorrer el directorio temporal {}: {}", tempStorageRoot, ex.getMessage());
        }

        if (deletedFiles > 0 || deletedDirectories > 0) {
            log.info("Limpieza temporal completada: {} archivo(s) y {} carpeta(s) eliminados",
                    deletedFiles, deletedDirectories);
        }
        return new CleanupStats(deletedFiles, deletedDirectories);
    }

    /**
     * Reencola documentos fallidos recuperables (retry_count < MAX_RETRIES) y trabajos PROCESSING bloqueados (RF-08).
     */
    @Scheduled(fixedDelay = 120_000)
    public int retryPendingAndFailedDocuments() {
        return retryPendingAndFailedDocuments(Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    public int retryPendingAndFailedDocuments(Duration errorBackoff, Duration stuckProcessingThreshold) {
        if (shuttingDown || !Files.exists(tempStorageRoot) || !Files.isDirectory(tempStorageRoot)) {
            return 0;
        }

        int requeued = 0;
        OffsetDateTime now = OffsetDateTime.now();

        try (Stream<Path> rootEntries = Files.list(tempStorageRoot)) {
            List<Path> userDirs = rootEntries.filter(Files::isDirectory).toList();
            for (Path userDir : userDirs) {
                UUID userId = parseUuidOrNull(userDir.getFileName().toString());
                if (userId == null) {
                    continue;
                }
                requeued += RlsDataSourceConfig.runAsUser(userId, () -> {
                    int userRequeued = 0;
                    List<DocumentEntity> docs = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
                    for (DocumentEntity doc : docs) {
                        if (!hasExistingTempFile(doc)) {
                            continue;
                        }
                        boolean retryableError = doc.getStatus() == DocumentStatus.ERROR
                                && doc.getRetryCount() < DocumentIngestionWorker.MAX_RETRIES
                                && isBeforeCutoff(doc.getUpdatedAt(), now, errorBackoff);
                        boolean stuckProcessing = doc.getStatus() == DocumentStatus.PROCESSING
                                && doc.getRetryCount() < DocumentIngestionWorker.MAX_RETRIES
                                && isBeforeCutoff(doc.getUpdatedAt(), now, stuckProcessingThreshold);
                        if (retryableError || stuckProcessing) {
                            doc.setStatus(DocumentStatus.PENDING);
                            documentRepository.save(doc);
                            ingestionWorker.enqueueJob(userId, doc.getId());
                            userRequeued++;
                        }
                    }
                    return userRequeued;
                });
            }
        } catch (IOException ex) {
            log.warn("Error escaneando reintentos pendientes en {}: {}", tempStorageRoot, ex.getMessage());
        }

        return requeued;
    }

    private UserCleanupResult cleanupUserDirectory(
            Path userDir,
            UUID userId,
            Instant now,
            Duration unreferencedOrphanGrace,
            Duration exhaustedErrorGrace,
            Duration abandonedPendingGrace
    ) {
        List<DocumentEntity> docs = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Map<Path, DocumentEntity> docsByNormalizedPath = new HashMap<>();
        for (DocumentEntity doc : docs) {
            if (doc.getTempFilePath() != null && !doc.getTempFilePath().isBlank()) {
                Path normalized = Path.of(doc.getTempFilePath()).toAbsolutePath().normalize();
                docsByNormalizedPath.put(normalized, doc);
            }
        }

        int deletedFiles = 0;
        try (Stream<Path> files = Files.list(userDir)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Path normalizedFile = file.toAbsolutePath().normalize();
                DocumentEntity ownerDoc = docsByNormalizedPath.get(normalizedFile);

                if (ownerDoc == null) {
                    if (isOlderThan(file, now, unreferencedOrphanGrace) && deleteQuietly(file)) {
                        deletedFiles++;
                    }
                    continue;
                }

                boolean shouldDelete = switch (ownerDoc.getStatus()) {
                    case INDEXED -> true;
                    case ERROR -> ownerDoc.isTempFileDeleted()
                            || (ownerDoc.getRetryCount() >= DocumentIngestionWorker.MAX_RETRIES
                            && isOlderThan(file, now, exhaustedErrorGrace));
                    case PENDING, PROCESSING -> ownerDoc.isTempFileDeleted()
                            || isOlderThan(file, now, abandonedPendingGrace);
                    case AWAITING_CONFIRMATION -> ownerDoc.isTempFileDeleted()
                            || isOlderThan(file, now, Duration.ofDays(7));
                };

                if (shouldDelete && deleteQuietly(file)) {
                    deletedFiles++;
                    if (!ownerDoc.isTempFileDeleted() || ownerDoc.getTempFilePath() != null) {
                        ownerDoc.setTempFileDeleted(true);
                        ownerDoc.setTempFilePath(null);
                        documentRepository.save(ownerDoc);
                    }
                }
            }
        } catch (IOException ex) {
            log.warn("No se pudo limpiar la carpeta temporal de {}: {}", userId, ex.getMessage());
        }

        boolean removedDir = false;
        try (Stream<Path> remaining = Files.list(userDir)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(userDir);
                removedDir = true;
            }
        } catch (IOException ignored) {
            // Ignorar si todavía queda algún archivo activo
        }

        return new UserCleanupResult(deletedFiles, removedDir);
    }

    private boolean hasExistingTempFile(DocumentEntity doc) {
        if (doc.isTempFileDeleted() || doc.getTempFilePath() == null || doc.getTempFilePath().isBlank()) {
            return false;
        }
        return Files.exists(Path.of(doc.getTempFilePath()));
    }

    private boolean isOlderThan(Path file, Instant now, Duration grace) {
        if (grace.isZero() || grace.isNegative()) {
            return true;
        }
        try {
            Instant lastModified = Files.getLastModifiedTime(file).toInstant();
            return lastModified.plus(grace).isBefore(now);
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean isBeforeCutoff(OffsetDateTime updatedAt, OffsetDateTime now, Duration threshold) {
        if (threshold.isZero() || threshold.isNegative()) {
            return true;
        }
        if (updatedAt == null) {
            return true;
        }
        return updatedAt.plus(threshold).isBefore(now);
    }

    private boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("No se pudo eliminar archivo temporal {}: {}", path, ex.getMessage());
            return false;
        }
    }

    private UUID parseUuidOrNull(String candidate) {
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public record CleanupStats(int deletedFiles, int deletedDirectories) {
    }

    private record UserCleanupResult(int deletedFiles, boolean directoryRemoved) {
    }
}
