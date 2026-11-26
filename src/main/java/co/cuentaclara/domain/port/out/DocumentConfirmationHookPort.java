package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.CategorySuggestion;
import co.cuentaclara.domain.model.Document;
import co.cuentaclara.domain.model.DocumentExtraction;
import co.cuentaclara.domain.model.Transaction;
import java.util.UUID;

public interface DocumentConfirmationHookPort {

    void onDocumentConfirmed(
            UUID userId,
            Document document,
            DocumentExtraction extraction,
            Transaction transaction,
            boolean saveUserCategoryPreference
    );

    void onManualExpenseRegistered(
            UUID userId,
            Transaction transaction,
            Document optionalAttachment,
            boolean saveUserCategoryPreference
    );

    void onManualCategoryCorrection(UUID userId, Transaction transaction);

    CategorySuggestion suggestCategoryForUser(UUID userId, String merchantName, String contextText);
}
