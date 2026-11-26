package co.cuentaclara.infrastructure.adapter.out.security;

import co.cuentaclara.domain.port.out.TenantExecutionPort;
import co.cuentaclara.infrastructure.security.rls.RlsDataSourceConfig;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class RlsTenantExecutionAdapter implements TenantExecutionPort {

    @Override
    public <T> T runAsUser(UUID userId, Supplier<T> action) {
        return RlsDataSourceConfig.runAsUser(userId, action);
    }
}
