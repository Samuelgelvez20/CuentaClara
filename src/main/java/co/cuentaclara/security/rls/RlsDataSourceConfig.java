package co.cuentaclara.security.rls;

import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RlsDataSourceConfig implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && !(bean instanceof RlsAwareDataSource)) {
            return new RlsAwareDataSource(dataSource);
        }
        return bean;
    }

    /**
     * Ejecuta una acción con el contexto de un usuario específico (útil para workers asíncronos y pruebas).
     */
    public static <T> T runAsUser(UUID userId, Supplier<T> action) {
        UUID previous = TenantContext.getCurrentUserId();
        try {
            TenantContext.setCurrentUserId(userId);
            return action.get();
        } finally {
            if (previous != null) {
                TenantContext.setCurrentUserId(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    public static void runAsUser(UUID userId, Runnable action) {
        runAsUser(userId, () -> {
            action.run();
            return null;
        });
    }
}
