package co.cuentaclara.infrastructure.adapter.in.web;

import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.ChatAnswerResponse;
import co.cuentaclara.domain.model.ConfirmDocumentCommand;
import co.cuentaclara.domain.model.DocumentStatus;
import co.cuentaclara.domain.model.DocumentStatusView;
import co.cuentaclara.domain.model.ExpenseHistorySummary;
import co.cuentaclara.domain.model.ManualExpenseCommand;
import co.cuentaclara.domain.model.ObligationInput;
import co.cuentaclara.domain.model.PaymentFrequency;
import co.cuentaclara.domain.model.PeriodClosureReportView;
import co.cuentaclara.domain.model.QuestionnaireCommand;
import co.cuentaclara.domain.model.RecurringObligation;
import co.cuentaclara.domain.model.Transaction;
import co.cuentaclara.domain.port.in.AuthUseCase;
import co.cuentaclara.domain.port.in.ChatAssistantUseCase;
import co.cuentaclara.domain.port.in.DocumentIngestionUseCase;
import co.cuentaclara.domain.port.in.FinancialProfileUseCase;
import co.cuentaclara.domain.port.in.MonthlyReportUseCase;
import co.cuentaclara.domain.port.in.TransactionUseCase;
import co.cuentaclara.infrastructure.adapter.in.AuthCookieSupport;
import co.cuentaclara.infrastructure.security.jwt.AuthenticatedUserPrincipal;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebDashboardController {

    private static final String[] SPANISH_MONTH_NAMES = {
            "", "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    };

    private static final String[] SPANISH_DAY_NAMES = {
            "", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"
    };

    private static final List<String> STANDARD_CATEGORIES = List.of(
            "Alimentación",
            "Domicilios y Restaurantes",
            "Vivienda",
            "Deudas y Préstamos",
            "Servicios",
            "Transporte",
            "Salud",
            "Entretenimiento",
            "Educación",
            "Panadería y Antojos",
            "Otros"
    );

    private final AuthUseCase authUseCase;
    private final FinancialProfileUseCase profileUseCase;
    private final DocumentIngestionUseCase ingestionUseCase;
    private final TransactionUseCase transactionUseCase;
    private final ChatAssistantUseCase chatUseCase;
    private final MonthlyReportUseCase reportUseCase;

    public WebDashboardController(
            AuthUseCase authUseCase,
            FinancialProfileUseCase profileUseCase,
            DocumentIngestionUseCase ingestionUseCase,
            TransactionUseCase transactionUseCase,
            ChatAssistantUseCase chatUseCase,
            MonthlyReportUseCase reportUseCase
    ) {
        this.authUseCase = authUseCase;
        this.profileUseCase = profileUseCase;
        this.ingestionUseCase = ingestionUseCase;
        this.transactionUseCase = transactionUseCase;
        this.chatUseCase = chatUseCase;
        this.reportUseCase = reportUseCase;
    }

    @GetMapping("/dashboard")
    public String dashboard(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "monthNum", required = false) Integer monthNum,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "merchant", required = false) String merchant,
            Model model
    ) {
        String effectiveMonth = resolveEffectiveMonthParam(month, year, monthNum);
        populateCommonDashboardModel(principal, effectiveMonth, category, merchant, model);
        return "dashboard/index";
    }

    @GetMapping("/upload")
    public String uploadAndConfirmPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "trackingDoc", required = false) UUID trackingDocId,
            Model model
    ) {
        populateCommonDashboardModel(principal, month, null, null, model);
        @SuppressWarnings("unchecked")
        List<DocumentStatusView> allDocs =
                (List<DocumentStatusView>) model.getAttribute("documents");
        List<DocumentStatusView> activeDocs = allDocs == null ? List.of() : allDocs.stream()
                .filter(d -> d.document().getStatus() == DocumentStatus.PENDING
                        || d.document().getStatus() == DocumentStatus.PROCESSING)
                .toList();
        UUID effectiveTrackingId = trackingDocId;
        if (effectiveTrackingId == null && !activeDocs.isEmpty()) {
            effectiveTrackingId = activeDocs.get(0).document().getId();
        }
        model.addAttribute("trackingDocId", effectiveTrackingId);
        if (effectiveTrackingId != null) {
            UUID targetId = effectiveTrackingId;
            ingestionUseCase.getDocumentStatus(targetId)
                    .filter(d -> d.document().getUserId().equals(principal.userId()))
                    .ifPresent(d -> model.addAttribute("trackingDoc", d));
        }
        return "dashboard/upload";
    }

    @GetMapping("/assistant")
    public String assistantPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            Model model
    ) {
        populateCommonDashboardModel(principal, month, null, null, model);
        return "dashboard/assistant";
    }

    @GetMapping("/settings")
    public String settingsPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            Model model
    ) {
        populateCommonDashboardModel(principal, null, null, null, model);
        return "dashboard/settings";
    }

    @PostMapping("/settings")
    public String saveSettings(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(required = false) BigDecimal netIncome,
            @RequestParam(required = false) PaymentFrequency paymentFrequency,
            @RequestParam(required = false) String paymentDays,
            @RequestParam(required = false, defaultValue = "50") int needsPct,
            @RequestParam(required = false, defaultValue = "30") int wantsPct,
            @RequestParam(required = false, defaultValue = "20") int savingsPct,
            @RequestParam(required = false) String financialGoals,
            RedirectAttributes redirectAttributes
    ) {
        try {
            if (netIncome != null && netIncome.compareTo(BigDecimal.ZERO) > 0) {
                profileUseCase.saveOrUpdateQuestionnaire(
                        principal.userId(),
                        new QuestionnaireCommand(
                                netIncome,
                                paymentFrequency != null ? paymentFrequency : PaymentFrequency.MONTHLY,
                                paymentDays != null && !paymentDays.isBlank() ? paymentDays : "30",
                                needsPct, wantsPct, savingsPct,
                                financialGoals != null && !financialGoals.isBlank() ? financialGoals : "Ahorrar y organizar mis finanzas",
                                BigDecimal.ZERO,
                                null
                        )
                );
            }
            redirectAttributes.addFlashAttribute("successMessage",
                    "¡Tus ajustes y preferencias quedaron guardados!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/delete-account")
    public String deleteAccountFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "confirmationText", required = false) String confirmationText,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        String normalized = confirmationText != null ? confirmationText.trim() : "";
        if (!"ELIMINAR".equalsIgnoreCase(normalized)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Para eliminar tu cuenta escribe exactamente ELIMINAR en el diálogo de confirmación.");
            return "redirect:/settings";
        }
        authUseCase.deleteAccount(principal.userId());
        AuthCookieSupport.attachAuthCookie(response, "", 0);
        redirectAttributes.addFlashAttribute("successMessage",
                "Tu cuenta, tus movimientos, tus análisis y tus archivos temporales fueron eliminados de forma permanente.");
        return "redirect:/auth/login?deleted=true";
    }

    @PostMapping("/dashboard/obligations")
    public String addRecurringObligationFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam String name,
            @RequestParam BigDecimal estimatedAmount,
            @RequestParam(defaultValue = "5") Integer dueDay,
            @RequestParam(defaultValue = "Deudas y Préstamos") String category,
            @RequestParam(defaultValue = "NEEDS") BudgetBlock budgetBlock,
            @RequestParam(defaultValue = "/dashboard") String returnTo,
            RedirectAttributes redirectAttributes
    ) {
        try {
            profileUseCase.addRecurringObligation(
                    principal.userId(),
                    new ObligationInput(
                            name, category, budgetBlock, estimatedAmount, dueDay, true
                    )
            );
            redirectAttributes.addFlashAttribute("successMessage",
                    "Compromiso mensual '" + name + "' programado automáticamente para el día " + dueDay + " de cada mes.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + (returnTo.startsWith("/") ? returnTo : "/dashboard");
    }

    @PostMapping("/dashboard/obligations/{obligationId}/delete")
    public String deleteRecurringObligationFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID obligationId,
            @RequestParam(defaultValue = "/dashboard") String returnTo,
            RedirectAttributes redirectAttributes
    ) {
        profileUseCase.deleteRecurringObligation(principal.userId(), obligationId);
        redirectAttributes.addFlashAttribute("successMessage", "Pago fijo mensual eliminado del calendario.");
        return "redirect:" + (returnTo.startsWith("/") ? returnTo : "/dashboard");
    }

    @GetMapping("/history")
    public String historyPage(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(value = "month", required = false) String month,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "monthNum", required = false) Integer monthNum,
            @RequestParam(value = "startDate", required = false) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) LocalDate endDate,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "merchant", required = false) String merchant,
            Model model
    ) {
        boolean allHistoryRequested = "ALL".equalsIgnoreCase(month != null ? month.trim() : "")
                && startDate == null && endDate == null;
        String effectiveMonth = allHistoryRequested
                ? null
                : resolveEffectiveMonthParam(month, year, monthNum);
        if ((effectiveMonth == null || effectiveMonth.isBlank()) && startDate != null) {
            effectiveMonth = YearMonth.from(startDate).toString();
        }

        populateCommonDashboardModel(principal, effectiveMonth, category, merchant, model);

        LocalDate sDate = startDate;
        LocalDate eDate = endDate;
        if (sDate != null && eDate != null && sDate.isAfter(eDate)) {
            LocalDate tmp = sDate;
            sDate = eDate;
            eDate = tmp;
        }

        if (sDate != null || eDate != null) {
            ExpenseHistorySummary rangeHistory = transactionUseCase.getExpenseHistory(
                    principal.userId(), sDate, eDate, category, merchant
            );
            model.addAttribute("history", rangeHistory);
            model.addAttribute("startDate", sDate != null ? sDate.toString() : "");
            model.addAttribute("endDate", eDate != null ? eDate.toString() : "");
            model.addAttribute("isAllHistory", false);
            model.addAttribute("historyRangeLabel", formatDateRangeLabel(sDate, eDate));
        } else if (allHistoryRequested) {
            ExpenseHistorySummary allHistory = transactionUseCase.getExpenseHistory(
                    principal.userId(), "ALL", category, merchant
            );
            model.addAttribute("history", allHistory);
            model.addAttribute("startDate", "");
            model.addAttribute("endDate", "");
            model.addAttribute("isAllHistory", true);
            model.addAttribute("historyRangeLabel", "Todo el historial");
        }
        return "dashboard/history";
    }

    private String formatDateRangeLabel(LocalDate start, LocalDate end) {
        if (start != null && end != null) {
            if (start.equals(end)) {
                return formatShortDate(start);
            }
            if (start.getDayOfMonth() == 1 && end.equals(YearMonth.from(start).atEndOfMonth())
                    && YearMonth.from(start).equals(YearMonth.from(end))) {
                return formatMonthLabel(YearMonth.from(start));
            }
            return formatShortDate(start) + " – " + formatShortDate(end);
        }
        LocalDate single = start != null ? start : end;
        return single != null ? formatShortDate(single) : "";
    }

    private String formatShortDate(LocalDate d) {
        String monthShort = SPANISH_MONTH_NAMES[d.getMonthValue()];
        if (monthShort.length() > 3) {
            monthShort = monthShort.substring(0, 3);
        }
        return d.getDayOfMonth() + " " + monthShort + " " + d.getYear();
    }

    private String resolveEffectiveMonthParam(String month, Integer year, Integer monthNum) {
        if (year != null && monthNum != null && monthNum >= 1 && monthNum <= 12) {
            return YearMonth.of(year, monthNum).toString();
        }
        return month;
    }

    private void populateCommonDashboardModel(
            AuthenticatedUserPrincipal principal,
            String month,
            String category,
            String merchant,
            Model model
    ) {
        LocalDate today = LocalDate.now();
        YearMonth currentRealYm = YearMonth.from(today);
        YearMonth resolvedYm = parseYearMonthOrDefault(month, currentRealYm);
        String resolvedMonthStr = resolvedYm.toString();

        String todayDayName = SPANISH_DAY_NAMES[today.getDayOfWeek().getValue()];
        String todayFullLabel = todayDayName + " " + today.getDayOfMonth() + " de "
                + SPANISH_MONTH_NAMES[today.getMonthValue()] + " de " + today.getYear();

        model.addAttribute("user", principal);
        model.addAttribute("budgetBlocks", BudgetBlock.values());
        model.addAttribute("selectedMonth", resolvedMonthStr);
        model.addAttribute("startDate", "");
        model.addAttribute("endDate", "");
        model.addAttribute("isAllHistory", false);
        model.addAttribute("historyRangeLabel", formatMonthLabel(resolvedYm));
        model.addAttribute("selectedCategory", category != null ? category : "");
        model.addAttribute("selectedMerchant", merchant != null ? merchant : "");
        model.addAttribute("prevMonth", resolvedYm.minusMonths(1).toString());
        model.addAttribute("nextMonth", resolvedYm.plusMonths(1).toString());
        model.addAttribute("calendarMonthLabel", formatMonthLabel(resolvedYm));
        model.addAttribute("todayIso", today.toString());
        model.addAttribute("todayDayNumber", today.getDayOfMonth());
        model.addAttribute("todayDayOfWeek", today.getDayOfWeek().getValue());
        model.addAttribute("todayFullLabel", todayFullLabel);
        model.addAttribute("currentRealMonth", currentRealYm.toString());
        model.addAttribute("isCurrentRealMonth", resolvedYm.equals(currentRealYm));

        List<RecurringObligation> userObligations = new ArrayList<>();
        profileUseCase.getCurrentProfileAndBudget(principal.userId()).ifPresent(p -> {
            model.addAttribute("profile", p.profile());
            model.addAttribute("initialBudget", p.initialBudget());
            model.addAttribute("preliminaryRecommendations", p.preliminaryRecommendations());
            if (p.obligations() != null) {
                userObligations.addAll(p.obligations());
            }
        });
        if (userObligations.isEmpty()) {
            userObligations.addAll(profileUseCase.listObligations(principal.userId()));
        }
        model.addAttribute("obligations", userObligations);

        List<DocumentStatusView> allDocs = ingestionUseCase.listUserDocuments(principal.userId());
        List<DocumentStatusView> pendingConfirmations = allDocs.stream()
                .filter(d -> d.document().getStatus() == DocumentStatus.AWAITING_CONFIRMATION)
                .toList();
        List<DocumentStatusView> activeProcessingDocs = allDocs.stream()
                .filter(d -> d.document().getStatus() == DocumentStatus.PENDING
                        || d.document().getStatus() == DocumentStatus.PROCESSING)
                .toList();
        List<DocumentStatusView> failedDocuments = allDocs.stream()
                .filter(d -> d.document().getStatus() == DocumentStatus.ERROR)
                .limit(5)
                .toList();
        model.addAttribute("documents", allDocs);
        model.addAttribute("pendingConfirmations", pendingConfirmations);
        model.addAttribute("activeProcessingDocs", activeProcessingDocs);
        model.addAttribute("failedDocuments", failedDocuments);

        // Historial filtrado para la tabla de movimientos
        ExpenseHistorySummary history = transactionUseCase.getExpenseHistory(
                principal.userId(), resolvedMonthStr, category, merchant
        );
        model.addAttribute("history", history);

        // Historial completo del usuario para poblar desplegables óptimos (Meses, Categorías, Comercios)
        ExpenseHistorySummary allUserHistory = transactionUseCase.getExpenseHistory(
                principal.userId(), null, null, null
        );
        populateFilterDropdownOptions(allUserHistory.transactions(), resolvedYm, model);

        // Historial completo del mes seleccionado para construir el Calendario Interactivo día por día
        ExpenseHistorySummary monthFullHistory = transactionUseCase.getExpenseHistory(
                principal.userId(), resolvedMonthStr, null, null
        );
        List<CalendarDayCell> calendarCells = buildMonthlyCalendarCells(
                resolvedYm, monthFullHistory.transactions(), userObligations
        );
        model.addAttribute("calendarCells", calendarCells);
        model.addAttribute("reports", reportUseCase.listReports(principal.userId()));
    }

    private void populateFilterDropdownOptions(
            List<Transaction> allTransactions,
            YearMonth currentYm,
            Model model
    ) {
        int currentYear = currentYm.getYear();
        int currentMonthNum = currentYm.getMonthValue();

        Set<Integer> yearSet = new LinkedHashSet<>();
        int minYear = Math.min(1970, currentYear - 20);
        int maxYear = Math.max(2100, currentYear + 50);
        for (int y = minYear; y <= maxYear; y++) {
            yearSet.add(y);
        }
        yearSet.add(currentYear);
        for (Transaction tx : allTransactions) {
            if (tx.getTransactionDate() != null) {
                yearSet.add(tx.getTransactionDate().getYear());
            }
        }
        List<Integer> sortedYears = yearSet.stream().sorted().toList();

        Map<Integer, String> monthNumberNames = new LinkedHashMap<>();
        Map<String, String> monthOptions = new LinkedHashMap<>();
        for (int m = 1; m <= 12; m++) {
            monthNumberNames.put(m, SPANISH_MONTH_NAMES[m]);
            YearMonth ym = YearMonth.of(currentYear, m);
            monthOptions.put(ym.toString(), formatMonthLabel(ym));
        }

        Set<String> categoryOptions = new LinkedHashSet<>(STANDARD_CATEGORIES);
        Set<String> merchantOptions = new LinkedHashSet<>();
        for (Transaction tx : allTransactions) {
            if (tx.getCategory() != null && !tx.getCategory().isBlank()) {
                categoryOptions.add(tx.getCategory().trim());
            }
            if (tx.getMerchantName() != null && !tx.getMerchantName().isBlank()) {
                merchantOptions.add(tx.getMerchantName().trim());
            }
        }

        model.addAttribute("selectedYear", currentYear);
        model.addAttribute("selectedMonthNumber", currentMonthNum);
        model.addAttribute("availableYears", sortedYears);
        model.addAttribute("availableMonthNames", monthNumberNames);
        model.addAttribute("availableMonths", monthOptions);
        model.addAttribute("availableCategories", categoryOptions);
        model.addAttribute("availableMerchants", merchantOptions);
    }

    private List<CalendarDayCell> buildMonthlyCalendarCells(
            YearMonth ym,
            List<Transaction> monthTransactions,
            List<RecurringObligation> obligations
    ) {
        List<CalendarDayCell> cells = new ArrayList<>();
        // Lunes = 1 ... Domingo = 7 -> leading empty cells = dayOfWeek - 1
        int firstDayOfWeek = ym.atDay(1).getDayOfWeek().getValue();
        for (int i = 1; i < firstDayOfWeek; i++) {
            cells.add(CalendarDayCell.emptyCell());
        }

        LocalDate today = LocalDate.now();
        int daysInMonth = ym.lengthOfMonth();
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = ym.atDay(day);
            final int currentDay = day;
            boolean isToday = date.equals(today);
            String dayOfWeekName = SPANISH_DAY_NAMES[date.getDayOfWeek().getValue()];

            List<Transaction> dayTxs = monthTransactions.stream()
                    .filter(tx -> date.equals(tx.getTransactionDate()))
                    .toList();

            BigDecimal dayTotal = dayTxs.stream()
                    .map(Transaction::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<DayObligationBadge> dayObs = new ArrayList<>();
            for (RecurringObligation ob : obligations) {
                int effectiveDueDay = Math.min(ob.getDueDay(), daysInMonth);
                if (effectiveDueDay == currentDay) {
                    boolean paid = isObligationPaidInMonth(ob, monthTransactions);
                    dayObs.add(new DayObligationBadge(
                            ob.getId(),
                            ob.getName(),
                            ob.getCategory(),
                            ob.getBudgetBlock(),
                            ob.getEstimatedAmount(),
                            ob.getDueDay(),
                            paid
                    ));
                }
            }

            String fullDateLabel = (isToday ? "Hoy, " : "") + dayOfWeekName + " " + day + " de "
                    + SPANISH_MONTH_NAMES[ym.getMonthValue()] + " de " + ym.getYear();
            cells.add(new CalendarDayCell(
                    false,
                    isToday,
                    day,
                    dayOfWeekName,
                    date.toString(),
                    fullDateLabel,
                    dayTxs,
                    dayTotal,
                    dayObs
            ));
        }
        return cells;
    }

    private boolean isObligationPaidInMonth(RecurringObligation ob, List<Transaction> monthTransactions) {
        String obNameLower = ob.getName().toLowerCase(Locale.ROOT);
        String obCatLower = ob.getCategory().toLowerCase(Locale.ROOT);
        for (Transaction tx : monthTransactions) {
            String merchantLower = tx.getMerchantName() != null ? tx.getMerchantName().toLowerCase(Locale.ROOT) : "";
            String descLower = tx.getDescription() != null ? tx.getDescription().toLowerCase(Locale.ROOT) : "";
            String catLower = tx.getCategory() != null ? tx.getCategory().toLowerCase(Locale.ROOT) : "";
            if (merchantLower.contains(obNameLower) || obNameLower.contains(merchantLower)
                    || descLower.contains(obNameLower)
                    || (catLower.equals(obCatLower) && tx.getTotalAmount().compareTo(ob.getEstimatedAmount().multiply(new BigDecimal("0.5"))) >= 0)) {
                return true;
            }
        }
        return false;
    }

    private YearMonth parseYearMonthOrDefault(String month, YearMonth fallback) {
        if (month == null || month.isBlank()) {
            return fallback;
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String formatMonthLabel(YearMonth ym) {
        return SPANISH_MONTH_NAMES[ym.getMonthValue()] + " " + ym.getYear();
    }

    @PostMapping("/dashboard/upload")
    public String uploadInvoiceFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "statement", defaultValue = "false") boolean statement,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            DocumentStatusView statusView = ingestionUseCase.uploadAndProcessDocument(
                    principal.userId(),
                    file.getOriginalFilename(),
                    file.getBytes(),
                    statement,
                    async
            );
            DocumentStatus status = statusView.document().getStatus();
            if (status == DocumentStatus.PENDING || status == DocumentStatus.PROCESSING) {
                redirectAttributes.addFlashAttribute("infoMessage",
                        "Archivo recibido y en cola de extracción segura. Verás el resultado aquí en unos segundos.");
                return "redirect:/upload?trackingDoc=" + statusView.document().getId();
            }
            if (status == DocumentStatus.ERROR) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        statusView.document().getErrorMessage() != null
                                ? statusView.document().getErrorMessage()
                                : "No fue posible extraer los datos de este archivo.");
                return "redirect:/upload";
            }
            redirectAttributes.addFlashAttribute("successMessage",
                    "¡Tu recibo o factura fue leído! Revisa y confirma los datos antes de guardarlo.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/upload";
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
            transactionUseCase.confirmExtractedDocument(
                    principal.userId(),
                    documentId,
                    new ConfirmDocumentCommand(
                            merchantName, merchantNit, invoiceNumber, null,
                            transactionDate, null, null, totalAmount,
                            category, budgetBlock, null, null
                    )
            );
            String monthParam = transactionDate != null ? YearMonth.from(transactionDate).toString() : "2026-10";
            redirectAttributes.addFlashAttribute("successMessage",
                    "¡Gasto confirmado y agregado a tu calendario del mes!");
            return "redirect:/dashboard?month=" + monthParam;
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/upload";
        }
    }

    @PostMapping("/dashboard/documents/{documentId}/cancel")
    public String cancelDocumentFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "/upload") String returnTo,
            RedirectAttributes redirectAttributes
    ) {
        String safeReturn = (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//"))
                ? returnTo
                : "/upload";
        try {
            ingestionUseCase.cancelPendingDocument(principal.userId(), documentId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Comprobante descartado. No se guardó ningún gasto.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + safeReturn;
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
            @RequestParam(value = "returnMonth", required = false) String returnMonth,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        try {
            byte[] attBytes = attachment != null && !attachment.isEmpty() ? attachment.getBytes() : null;
            String attName = attachment != null ? attachment.getOriginalFilename() : null;
            LocalDate resolvedDate = transactionDate != null ? transactionDate : LocalDate.now();
            transactionUseCase.registerManualExpense(
                    principal.userId(),
                    new ManualExpenseCommand(
                            merchantName, resolvedDate, totalAmount, category, budgetBlock, description
                    ),
                    attName,
                    attBytes
            );
            String targetMonth = (returnMonth != null && !returnMonth.isBlank())
                    ? returnMonth
                    : YearMonth.from(resolvedDate).toString();
            redirectAttributes.addFlashAttribute("successMessage",
                    "¡Gasto de $" + String.format("%,.0f", totalAmount) + " en " + merchantName + " guardado en el día " + resolvedDate.getDayOfMonth() + "!");
            return "redirect:/dashboard?month=" + targetMonth + "#calendario-mes";
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/dashboard";
        }
    }

    @PostMapping("/dashboard/chat")
    public String askChatFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam String question,
            @RequestParam(value = "returnTo", defaultValue = "/assistant") String returnTo,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ChatAnswerResponse chatResponse =
                    chatUseCase.askQuestion(principal.userId(), question);
            redirectAttributes.addFlashAttribute("chatResponse", chatResponse);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + (returnTo.startsWith("/") ? returnTo : "/assistant");
    }

    @PostMapping("/dashboard/simulate-month")
    public String simulateFirstMonthFromWeb(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "2026-10") String month,
            RedirectAttributes redirectAttributes
    ) {
        try {
            PeriodClosureReportView closure =
                    reportUseCase.simulateFirstMonthAndGenerateReport(principal.userId(), month);
            redirectAttributes.addFlashAttribute("latestClosure", closure);
            redirectAttributes.addFlashAttribute("successMessage",
                    "¡Resumen del mes " + month + " listo con tus recomendaciones y calendario actualizado!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/dashboard?month=" + month;
    }

    public record DayObligationBadge(
            UUID id,
            String name,
            String category,
            BudgetBlock budgetBlock,
            BigDecimal estimatedAmount,
            int dueDay,
            boolean paidThisMonth
    ) {
    }

    public record CalendarDayCell(
            boolean empty,
            boolean today,
            int dayNumber,
            String dayOfWeekName,
            String isoDate,
            String fullDateLabel,
            List<Transaction> transactions,
            BigDecimal dayTotal,
            List<DayObligationBadge> obligations
    ) {
        public static CalendarDayCell emptyCell() {
            return new CalendarDayCell(true, false, 0, "", "", "", List.of(), BigDecimal.ZERO, List.of());
        }
    }
}
