package co.cuentaclara.domain.port.out;

import co.cuentaclara.domain.model.CleanupStats;
import java.util.UUID;

public interface TempFileStoragePort {

    String storeUserTempFile(UUID userId, String originalFilename, byte[] content);

    byte[] readTempFile(String storedPath);

    boolean exists(String storedPath);

    void deleteFileQuietly(String storedPath);

    void deleteUserTempDirectory(UUID userId);

    CleanupStats cleanupOrphanedTempFiles();
}
