package co.cuentaclara.domain.port.in;

import co.cuentaclara.domain.model.ChatAnswerResponse;
import co.cuentaclara.domain.model.HybridChunkEvidence;
import co.cuentaclara.domain.model.SqlTransactionsToolResult;
import co.cuentaclara.domain.model.TransactionQueryFilters;
import java.util.List;
import java.util.UUID;

public interface ChatAssistantUseCase {

    ChatAnswerResponse askQuestion(UUID userId, String userQuestion);

    SqlTransactionsToolResult consultarTransacciones(UUID userId, TransactionQueryFilters filters);

    List<HybridChunkEvidence> buscarDocumentos(UUID userId, String queryText, int topK);
}
