package co.cuentaclara.domain.port.out;

import java.util.UUID;
import java.util.function.Supplier;

public interface TenantExecutionPort {

    <T> T runAsUser(UUID userId, Supplier<T> action);

    default void runAsUser(UUID userId, Runnable action) {
        runAsUser(userId, () -> {
            action.run();
            return null;
        });
    }
}
