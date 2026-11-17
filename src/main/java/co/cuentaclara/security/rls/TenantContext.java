package co.cuentaclara.security.rls;

import java.util.UUID;

/**
 * Almacena en el hilo actual el UUID del usuario autenticado para que el DataSource
 * fije la variable de sesión PostgreSQL 'app.current_user_id' (RNF-01).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_USER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setCurrentUserId(UUID userId) {
        CURRENT_USER.set(userId);
    }

    public static UUID getCurrentUserId() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
