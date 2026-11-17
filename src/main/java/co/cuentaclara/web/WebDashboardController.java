package co.cuentaclara.web;

import co.cuentaclara.chat.NaturalLanguageChatService;
import co.cuentaclara.document.DocumentIngestionService;
import co.cuentaclara.document.DocumentStatus;
import co.cuentaclara.profile.BudgetBlock;
import co.cuentaclara.profile.FinancialProfileService;
import co.cuentaclara.report.MonthlyAnalysisAndReportService;
import co.cuentaclara.security.jwt.AuthenticatedUserPrincipal;
import co.cuentaclara.transaction.TransactionService;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controlador web Thymeleaf + HTMX en español que unifica:
 * - Carga y confirmación de facturas en 3 pasos o menos (RNF-12, RF-03, RF-05)
 * - Registro rápido de gastos manuales con adjunto opcional (RF-02)
 * - Historial filtrable por mes, categoría y comercio con totales SQL (RF-10)
 * - Chat conversacional con citas verificables (RF-12)
 * - Informe mensual/quincenal y plan sugerido editable (RF-13, RF-16)
 */
@Controller
public class WebDashboardController {

    private final FinancialProfileService profileService;
    private final DocumentIngestionService ingestionService;
    private final TransactionService transactionService;
    private final NaturalLanguageChatService chatService;
    private final MonthlyAnalysisAndReportService reportService;

    public WebDashboardController(
            FinancialProfileService profileService,
            DocumentIngestionService ingestionService,
            TransactionService transactionService,
            NaturalLanguageChatService chatService,
            MonthlyAnalysisAndReportService reportService
    ) {
        this.profileService = profileService;
        this.ingestionService = ingestionService;
        this.transactionService = transactionService;
        this.chatService = chatService;
        this.reportService = reportService;
    }

    @GetMapping("/dashboard")
    public String dashboard(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "merchant", required = false) String merchant,
            Model model
    ) {
        model.addAttribute("user", principal);
        model.addAttribute("budgetBlocks", BudgetBlock.values());
        model.addAttribute("selectedMonth", month != null ? month : "2026-10");
        model.addAttribute("selectedCategory", category != null ? category : "");
        model.addAttribute("selectedMerchant", merchant != null ? merchant : "");

        profileService.getCurrentProfileAndBudget(principal.userId()).ifPresent(p -> {
            model.addAttribute("profile", p.profile());
            model.addAttribute("initialBudget", p.initialBudget());
            model.addAttribute("preliminaryRecommendations", p.preliminaryRecommendations());
        });

        List<DocumentIngestionService.DocumentStatusView> allDocs = ingestionService.listUserDocuments(principal.userId());
        List<DocumentIngestionService.DocumentStatusView> pendingConfirmations = allDocs.stream()
                .filter(d -> d.document().getStatus() == DocumentStatus.AWAITING_CONFIRMATION)
                .toList();
        model.addAttribute("documents", allDocs);
        model.addAttribute("pendingConfirmations", pendingConfirmations);

        TransactionService.ExpenseHistorySummary history = transactionService.getExpenseHistory(
                principal.userId(), month, category, merchant
        );
        model.addAttribute("history", history);
        model.addAttribute("reports", reportService.listReports(principal.userId()));

        return "dashboard/index";
    }

    @PostMapping("/dashboard/upload")
    public String uploadInvoiceFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "statement", defaultValue = "false") boolean statement,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            ingestionService.uploadAndProcessDocument(
                    principal.userId(),
                    file.getOriginalFilename(),
                    file.getBytes(),
                    statement,
                    false
            );
            redirectAttributes.addFlashAttribute("successMessage",
                    "Documento procesado correctamente. Revisa y confirma los datos extraídos antes de guardar (RF-05).");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/dashboard/documents/{documentId}/confirm")
    public String confirmDocumentFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID documentId,
            @RequestParam String merchantName,
            @RequestParam(required = false) String merchantNit,
            @RequestParam(required = false) String invoiceNumber,
            @RequestParam(required = false) LocalDate transactionDate,
            @RequestParam BigDecimal totalAmount,
            @RequestParam String category,
            @RequestParam BudgetBlock budgetBlock,
            RedirectAttributes redirectAttributes
    ) {
        try {
            transactionService.confirmExtractedDocument(
                    principal.userId(),
                    documentId,
                    new TransactionService.ConfirmDocumentRequest(
                            merchantName, merchantNit, invoiceNumber, null,
                            transactionDate, null, null, totalAmount,
                            category, budgetBlock, null, null
                    )
            );
            redirectAttributes.addFlashAttribute("successMessage",
                    "Factura confirmada, indexada en pgvector y copia temporal eliminada de forma segura (RF-05, RF-08).");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/dashboard/manual-expense")
    public String registerManualExpenseFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam String merchantName,
            @RequestParam BigDecimal totalAmount,
            @RequestParam(required = false) LocalDate transactionDate,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BudgetBlock budgetBlock,
            @RequestParam(required = false) String description,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            byte[] attBytes = attachment != null && !attachment.isEmpty() ? attachment.getBytes() : null;
            String attName = attachment != null ? attachment.getOriginalFilename() : null;
            transactionService.registerManualExpense(
                    principal.userId(),
                    new TransactionService.ManualExpenseRequest(
                            merchantName, transactionDate, totalAmount, category, budgetBlock, description
                    ),
                    attName,
                    attBytes
            );
            redirectAttributes.addFlashAttribute("successMessage", "Gasto manual registrado e indexado con éxito.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/dashboard/chat")
    public String askChatFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam String question,
            RedirectAttributes redirectAttributes
    ) {
        try {
            NaturalLanguageChatService.ChatAnswerResponse chatResponse =
                    chatService.askQuestion(principal.userId(), question);
            redirectAttributes.addFlashAttribute("chatResponse", chatResponse);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/dashboard/simulate-month")
    public String simulateFirstMonthFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "2026-10") String month,
            RedirectAttributes redirectAttributes
    ) {
        try {
            MonthlyAnalysisAndReportService.PeriodClosureReportView closure =
                    reportService.simulateFirstMonthAndGenerateReport(principal.userId(), month);
            redirectAttributes.addFlashAttribute("latestClosure", closure);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Cierre del periodo " + month + " calculado en SQL con plan sugerido para el siguiente mes.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/dashboard";
    }
}
