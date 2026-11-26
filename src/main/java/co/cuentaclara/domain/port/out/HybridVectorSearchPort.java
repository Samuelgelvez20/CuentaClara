package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.HybridChunkEvidence;
import co.cuentaclara.domain.model.SqlTransactionsToolResult;
import co.cuentaclara.domain.model.TransactionQueryFilters;
import java.util.List;
import java.util.UUID;

public interface HybridVectorSearchPort {

    SqlTransactionsToolResult consultarTransacciones(UUID userId, TransactionQueryFilters filters);

    List<HybridChunkEvidence> buscarDocumentos(UUID userId, String queryText, int topK);
}
