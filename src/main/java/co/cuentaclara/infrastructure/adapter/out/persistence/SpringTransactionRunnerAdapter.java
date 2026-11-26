package co.cuentaclara.infrastructure.adapter.out.persistence;

import co.cuentaclara.domain.port.out.TransactionRunnerPort;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringTransactionRunnerAdapter implements TransactionRunnerPort {

    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate readOnlyTemplate;

    public SpringTransactionRunnerAdapter(PlatformTransactionManager transactionManager) {
        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
    }

    @Override
    public <T> T runInTransaction(Supplier<T> action) {
        return writeTemplate.execute(status -> action.get());
    }

    @Override
    public <T> T runInReadOnlyTransaction(Supplier<T> action) {
        return readOnlyTemplate.execute(status -> action.get());
    }
}
