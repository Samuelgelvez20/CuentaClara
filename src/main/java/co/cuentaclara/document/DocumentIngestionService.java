package co.cuentaclara.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de carga segura de facturas y extractos (RF-02, RF-03, RF-06, RNF-03).
 * Valida tamaño (<=10MB), tipo por contenido real (magic bytes), antivirus ClamAV y duplicados por SHA-256,
 * guarda copia temporal y dispara el procesamiento en el worker.
 */
@Service
public class DocumentIngestionService {

    private final FileSecurityAndClamAvValidator fileValidator;
    private final DocumentRepository documentRepository;
    private final DocumentExtractionRepository extractionRepository;
    private final DocumentIngestionWorker ingestionWorker;
    private final Path tempStorageDir;

    public DocumentIngestionService(
            FileSecurityAndClamAvValidator fileValidator,
            DocumentRepository documentRepository,
            DocumentExtractionRepository extractionRepository,
            DocumentIngestionWorker ingestionWorker,
            @Value("${cuentaclara.storage.temp-dir:./tmp/uploads}") String tempStorageDir
    ) {
        this.fileValidator = fileValidator;
        this.documentRepository = documentRepository;
        this.extractionRepository = extractionRepository;
        this.ingestionWorker = ingestionWorker;
        this.tempStorageDir = Path.of(tempStorageDir).toAbsolutePath().normalize();
    }

    public DocumentStatusView uploadAndProcessDocument(
            UUID userId,
            String originalFilename,
            byte[] fileBytes,
            boolean isStatement,
            boolean asyncExecution
    ) {
        // 1. Validar tamaño, firma de contenido (magic bytes) y ClamAV (RF-02, RNF-03)
        FileSecurityAndClamAvValidator.ValidatedFile validated =
                fileValidator.inspectAndValidate(originalFilename, fileBytes, isStatement);

        // 2. Verificar duplicado por hash SHA-256 para el mismo usuario (RF-06)
        documentRepository.findByUserIdAndSha256Hash(userId, validated.sha256Hash())
                .ifPresent(existing -> {
                    throw new DocumentIngestionWorker.DuplicateInvoiceException(
                            "Este archivo ya fue cargado anteriormente (duplicado detectado por hash SHA-256)."
                    );
                });

        // 3. Guardar copia temporal en disco (se eliminará solo tras verificar indexación en RF-08)
        Path savedTempFile = storeTemporaryFile(userId, validated.originalFilename(), fileBytes);

        // 4. Registrar documento en estado PENDING
        DocumentEntity doc = createPendingDocumentRecord(userId, validated, savedTempFile);

        // 5. Encolar en Redis y procesar en el Worker
        if (asyncExecution) {
            boolean enqueuedInRedis = ingestionWorker.enqueueJob(userId, doc.getId());
            if (!enqueuedInRedis && ingestionWorker.isInlineAsyncFallbackEnabled()) {
                ingestionWorker.processDocumentAsync(userId, doc.getId());
            }
        } else {
            try {
                ingestionWorker.processDocumentDeterministically(userId, doc.getId());
            } catch (DocumentIngestionWorker.DuplicateInvoiceException dupEx) {
                deleteTempQuietly(savedTempFile);
                documentRepository.deleteById(doc.getId());
                throw dupEx;
            }
        }

        return getDocumentStatus(doc.getId())
                .orElseThrow(() -> new IllegalStateException("No fue posible consultar el documento recién creado."));
    }

    @Transactional
    protected DocumentEntity createPendingDocumentRecord(
            UUID userId,
            FileSecurityAndClamAvValidator.ValidatedFile validated,
            Path savedTempFile
    ) {
        DocumentEntity doc = new DocumentEntity();
        doc.setUserId(userId);
        doc.setOriginalFilename(validated.originalFilename());
        doc.setMimeType(validated.mimeType());
        doc.setSourceType(validated.sourceType());
        doc.setFileSizeBytes(validated.fileSizeBytes());
        doc.setSha256Hash(validated.sha256Hash());
        doc.setStatus(DocumentStatus.PENDING);
        doc.setTempFilePath(savedTempFile.toString());
        doc.setTempFileDeleted(false);
        return documentRepository.save(doc);
    }

    @Transactional(readOnly = true)
    public Optional<DocumentStatusView> getDocumentStatus(UUID documentId) {
        return documentRepository.findById(documentId).map(doc -> {
            DocumentExtraction extraction = extractionRepository.findByDocumentId(doc.getId()).orElse(null);
            return new DocumentStatusView(doc, extraction);
        });
    }

    @Transactional(readOnly = true)
    public List<DocumentStatusView> listUserDocuments(UUID userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(doc -> new DocumentStatusView(
                        doc,
                        extractionRepository.findByDocumentId(doc.getId()).orElse(null)
                ))
                .toList();
    }

    private Path storeTemporaryFile(UUID userId, String filename, byte[] content) {
        try {
            Path userDir = tempStorageDir.resolve(userId.toString());
            Files.createDirectories(userDir);
            String safeName = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path target = userDir.resolve(UUID.randomUUID() + "_" + safeName);
            Files.write(target, content);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo almacenar el archivo temporal para su procesamiento", ex);
        }
    }

    /**
     * Descarta un comprobante pendiente sin registrar gasto y libera sus claves de duplicado.
     */
    @Transactional
    public void cancelPendingDocument(UUID userId, UUID documentId) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado: " + documentId));
        if (!doc.getUserId().equals(userId)) {
            throw new IllegalArgumentException("No tienes permiso para descartar este comprobante.");
        }
        if (doc.getStatus() == DocumentStatus.INDEXED) {
            throw new IllegalStateException("Este comprobante ya fue confirmado y guardado como gasto.");
        }
        if (doc.getTempFilePath() != null && !doc.getTempFilePath().isBlank()) {
            deleteTempQuietly(Path.of(doc.getTempFilePath()));
        }
        extractionRepository.findByDocumentId(documentId).ifPresent(ext -> {
            extractionRepository.delete(ext);
            extractionRepository.flush();
        });
        documentRepository.delete(doc);
    }

    private void deleteTempQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    public record DocumentStatusView(
            DocumentEntity document,
            DocumentExtraction extraction
    ) {
    }
}
