package co.cuentaclara.domain.port.in;

import co.cuentaclara.domain.model.CleanupStats;
import java.util.UUID;

public interface RagMaintenanceUseCase {

    int reindexChunksForCurrentEmbeddingModel(UUID userId);

    CleanupStats cleanupOrphanedTempFiles();
}
