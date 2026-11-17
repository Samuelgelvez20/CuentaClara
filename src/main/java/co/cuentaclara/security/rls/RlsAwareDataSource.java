package co.cuentaclara.security.rls;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Envuelve el DataSource de la aplicación para configurar 'app.current_user_id'
 * al obtener una conexión y limpiarlo al cerrarla/devolverla al pool,
 * garantizando el cumplimiento estricto de Row Level Security (RNF-01).
 */
public class RlsAwareDataSource extends DelegatingDataSource {

    public RlsAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection rawConnection = obtainTargetDataSource().getConnection();
        return prepareAndWrapConnection(rawConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection rawConnection = obtainTargetDataSource().getConnection(username, password);
        return prepareAndWrapConnection(rawConnection);
    }

    private Connection prepareAndWrapConnection(Connection connection) throws SQLException {
        applyTenantSetting(connection, TenantContext.getCurrentUserId());
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && !connection.isClosed()) {
                        try {
                            applyTenantSetting(connection, null);
                        } catch (SQLException ignored) {
                            // Si la conexión ya falló o hizo rollback, se descarta silenciosamente
                        }
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getTargetException();
                    }
                }
        );
    }

    private void applyTenantSetting(Connection connection, UUID userId) throws SQLException {
        String value = userId != null ? userId.toString() : "";
        try (PreparedStatement stmt = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, false)")) {
            stmt.setString(1, value);
            stmt.execute();
        }
    }
}
