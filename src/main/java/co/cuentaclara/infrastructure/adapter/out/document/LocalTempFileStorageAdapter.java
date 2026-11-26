package co.cuentaclara.infrastructure.adapter.out.document;

import co.cuentaclara.application.service.DocumentIngestionApplicationService;
import co.cuentaclara.domain.model.CleanupStats;
import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.port.out.DocumentRepositoryPort;
import co.cuentaclara.domain.port.out.TempFileStoragePort;
import co.cuentaclara.domain.port.out.TenantExecutionPort;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalTempFileStorageAdapter implements TempFileStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalTempFileStorageAdapter.class);

    private final DocumentRepositoryPort documentRepository;
    private final TenantExecutionPort tenantExecutionPort;
    private final Path tempStorageRoot;
    private volatile boolean shuttingDown = false;

    public LocalTempFileStorageAdapter(
            DocumentRepositoryPort documentRepository,
            TenantExecutionPort tenantExecutionPort,
            @Value("${cuentaclara.storage.temp-dir:./tmp/uploads}") String tempStorageDir
    ) {
        this.documentRepository = documentRepository;
        this.tenantExecutionPort = tenantExecutionPort;
        this.tempStorageRoot = Path.of(tempStorageDir).toAbsolutePath().normalize();
    }

    @PreDestroy
    void onShutdown() {
        this.shuttingDown = true;
    }

    @Override
    public String storeUserTempFile(UUID userId, String originalFilename, byte[] content) {
        try {
            Path userDir = tempStorageRoot.resolve(userId.toString());
            Files.createDirectories(userDir);
            String safeName = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path target = userDir.resolve(UUID.randomUUID() + "_" + safeName);
            Files.write(target, content);
            return target.toString();
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo almacenar el archivo temporal para su procesamiento", ex);
        }
    }

    @Override
    public byte[] readTempFile(String storedPath) {
        try {
            return Files.readAllBytes(Path.of(storedPath));
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el archivo temporal: " + storedPath, ex);
        }
    }

    @Override
    public boolean exists(String storedPath) {
        return storedPath != null && !storedPath.isBlank() && Files.exists(Path.of(storedPath));
    }

    @Override
    public void deleteFileQuietly(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        deleteQuietly(Path.of(storedPath));
    }

    @Override
    public void deleteUserTempDirectory(UUID userId) {
        Path userDir = tempStorageRoot.resolve(userId.toString()).normalize();
        if (!userDir.startsWith(tempStorageRoot) || !Files.exists(userDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(userDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("No se pudo eliminar archivo temporal de cuenta {}: {}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("Error limpiando carpeta temporal del usuario {}: {}", userId, ex.getMessage());
        }
    }

    @Override
    public CleanupStats cleanupOrphanedTempFiles() {
        return cleanupOrphanedTempFiles(
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                Duration.ofHours(24)
        );
    }

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

                UserCleanupResult userResult = tenantExecutionPort.runAsUser(userId, () ->
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

    private UserCleanupResult cleanupUserDirectory(
            Path userDir,
            UUID userId,
            Instant now,
            Duration unreferencedOrphanGrace,
            Duration exhaustedErrorGrace,
            Duration abandonedPendingGrace
    ) {
        List<Document> docs = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Map<Path, Document> docsByNormalizedPath = new HashMap<>();
        for (Document doc : docs) {
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
                Document ownerDoc = docsByNormalizedPath.get(normalizedFile);

                if (ownerDoc == null) {
                    if (isOlderThan(file, now, unreferencedOrphanGrace) && deleteQuietly(file)) {
                        deletedFiles++;
                    }
                    continue;
                }

                boolean shouldDelete = switch (ownerDoc.getStatus()) {
                    case INDEXED -> true;
                    case ERROR -> ownerDoc.isTempFileDeleted()
                            || (ownerDoc.getRetryCount() >= DocumentIngestionApplicationService.MAX_RETRIES
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
        }

        return new UserCleanupResult(deletedFiles, removedDir);
    }

    public Path getTempStorageRoot() {
        return tempStorageRoot;
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

    private record UserCleanupResult(int deletedFiles, boolean directoryRemoved) {
    }
}
