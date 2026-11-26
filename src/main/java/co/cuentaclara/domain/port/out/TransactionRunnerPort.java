package co.cuentaclara.domain.port.out;

import java.util.function.Supplier;

public interface TransactionRunnerPort {

    <T> T runInTransaction(Supplier<T> action);

    <T> T runInReadOnlyTransaction(Supplier<T> action);

    default void runInTransactionVoid(Runnable action) {
        runInTransaction(() -> {
            action.run();
            return null;
        });
    }
}
