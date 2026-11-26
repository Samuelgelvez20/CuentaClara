package co.cuentaclara.domain.port.in;

import co.cuentaclara.domain.model.BudgetBlock;
import co.cuentaclara.domain.model.ConfirmDocumentCommand;
import co.cuentaclara.domain.model.ExpenseHistorySummary;
import co.cuentaclara.domain.model.ManualExpenseCommand;
import co.cuentaclara.domain.model.Transaction;
import java.time.LocalDate;
import java.util.UUID;

public interface TransactionUseCase {

    Transaction confirmExtractedDocument(UUID userId, UUID documentId, ConfirmDocumentCommand request);

    Transaction registerManualExpense(
            UUID userId,
            ManualExpenseCommand request,
            String attachmentFilename,
            byte[] attachmentBytes
    );

    Transaction updateTransactionCategory(
            UUID userId,
            UUID transactionId,
            String category,
            BudgetBlock budgetBlock
    );

    ExpenseHistorySummary getExpenseHistory(
            UUID userId,
            String monthFilter,
            String categoryFilter,
            String merchantFilter
    );

    ExpenseHistorySummary getExpenseHistory(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate,
            String categoryFilter,
            String merchantFilter
    );
}
