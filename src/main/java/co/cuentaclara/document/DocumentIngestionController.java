package co.cuentaclara.document;

import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentIngestionController {

    private final DocumentIngestionService ingestionService;

    public DocumentIngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "statement", defaultValue = "false") boolean statement,
            @RequestParam(value = "async", defaultValue = "false") boolean async
    ) throws IOException {
        try {
            DocumentIngestionService.DocumentStatusView view = ingestionService.uploadAndProcessDocument(
                    principal.userId(),
                    file.getOriginalFilename(),
                    file.getBytes(),
                    statement,
                    async
            );
            HttpStatus responseStatus = async && (view.document().getStatus() == DocumentStatus.PENDING
                    || view.document().getStatus() == DocumentStatus.PROCESSING)
                    ? HttpStatus.ACCEPTED
                    : HttpStatus.CREATED;
            return ResponseEntity.status(responseStatus).body(view);
        } catch (DocumentIngestionWorker.DuplicateInvoiceException dupEx) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", dupEx.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<DocumentIngestionService.DocumentStatusView>> listDocuments(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal
    ) {
        return ResponseEntity.ok(ingestionService.listUserDocuments(principal.userId()));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<?> getDocumentStatus(@PathVariable UUID documentId) {
        return ingestionService.getDocumentStatus(documentId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Documento no encontrado o sin acceso.")));
    }
}
