package co.cuentaclara.infrastructure.security.rls;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

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
