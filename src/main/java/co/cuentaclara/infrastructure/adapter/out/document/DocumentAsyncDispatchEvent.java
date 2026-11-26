package co.cuentaclara.infrastructure.adapter.out.document;

import java.util.UUID;

public record DocumentAsyncDispatchEvent(UUID userId, UUID documentId) {
}
