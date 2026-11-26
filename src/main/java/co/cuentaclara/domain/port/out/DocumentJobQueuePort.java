package co.cuentaclara.domain.port.out;

import java.util.UUID;

public interface DocumentJobQueuePort {

    boolean enqueueJob(UUID userId, UUID documentId);

    boolean isInlineAsyncFallbackEnabled();

    void dispatchAsyncProcessing(UUID userId, UUID documentId);
}
