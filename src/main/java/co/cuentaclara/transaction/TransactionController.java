package co.cuentaclara.transaction;

import co.cuentaclara.profile.BudgetBlock;
import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/documents/{documentId}/confirm")
    public ResponseEntity<?> confirmDocument(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID documentId,
            @RequestBody(required = false) TransactionService.ConfirmDocumentRequest request
    ) {
        try {
            TransactionService.ConfirmDocumentRequest effectiveRequest = request != null
                    ? request
                    : new TransactionService.ConfirmDocumentRequest(null, null, null, null, null, null, null, null, null, null, null, null);
            TransactionEntity confirmedTx = transactionService.confirmExtractedDocument(
                    principal.userId(),
                    documentId,
                    effectiveRequest
            );
            return ResponseEntity.ok(confirmedTx);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping(value = "/transactions/manual", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> registerManualExpenseJson(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody TransactionService.ManualExpenseRequest request
    ) {
        try {
            TransactionEntity created = transactionService.registerManualExpense(
                    principal.userId(),
                    request,
                    null,
                    null
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping(value = "/transactions/manual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> registerManualExpenseMultipart(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("merchantName") String merchantName,
            @RequestParam("totalAmount") BigDecimal totalAmount,
            @RequestParam(value = "transactionDate", required = false) LocalDate transactionDate,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "budgetBlock", required = false) BudgetBlock budgetBlock,
            @RequestParam(value = "description", required = false) String description,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment
    ) throws IOException {
        try {
            byte[] attachmentBytes = attachment != null && !attachment.isEmpty() ? attachment.getBytes() : null;
            String attachmentName = attachment != null ? attachment.getOriginalFilename() : null;

            TransactionEntity created = transactionService.registerManualExpense(
                    principal.userId(),
                    new TransactionService.ManualExpenseRequest(
                            merchantName,
                            transactionDate,
                            totalAmount,
                            category,
                            budgetBlock,
                            description
                    ),
                    attachmentName,
                    attachmentBytes
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PatchMapping("/transactions/{transactionId}/category")
    public ResponseEntity<?> updateCategory(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID transactionId,
            @RequestBody CategoryUpdateRequest request
    ) {
        try {
            TransactionEntity updated = transactionService.updateTransactionCategory(
                    principal.userId(),
                    transactionId,
                    request.category(),
                    request.budgetBlock()
            );
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/transactions")
    public ResponseEntity<TransactionService.ExpenseHistorySummary> getExpenseHistory(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "merchant", required = false) String merchant
    ) {
        return ResponseEntity.ok(transactionService.getExpenseHistory(
                principal.userId(),
                month,
                category,
                merchant
        ));
    }

    public record CategoryUpdateRequest(String category, BudgetBlock budgetBlock) {
    }
}
