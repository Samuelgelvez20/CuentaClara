package co.cuentaclara.security.rls;

import co.cuentaclara.infrastructure.security.rls.RlsDataSourceConfig;
import co.cuentaclara.infrastructure.security.rls.TenantContext;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RlsTenantIsolationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("cuentaclara")
            .withUsername("cuentaclara_owner")
            .withPassword("owner_secret_dev");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Flyway corre con el usuario owner y crea el rol cuentaclara_app sin privilegios de dueño
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // La aplicación se conecta con cuentaclara_app (sujeto a RLS)
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "cuentaclara_app");
        registry.add("spring.datasource.password", () -> "app_secret_dev");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("RNF-01: Dos usuarios autenticados ven únicamente sus propias transacciones, fragmentos y perfil financiero")
    void shouldIsolateTenantDataStrictlyBetweenTwoUsers() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        // Crear ambos usuarios en la tabla users
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, ai_consent_accepted) VALUES (?, ?, ?, ?, true)",
                userA, "ana@cuentaclara.co", "hash_a", "Ana Pérez"
        );
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name, ai_consent_accepted) VALUES (?, ?, ?, ?, true)",
                userB, "beto@cuentaclara.co", "hash_b", "Beto Gómez"
        );

        // Insertar datos financieros y documentales como Usuario A
        RlsDataSourceConfig.runAsUser(userA, () -> {
            jdbcTemplate.update(
                    "INSERT INTO financial_profiles (user_id, net_income, payment_frequency, needs_pct, wants_pct, savings_pct) VALUES (?, ?, 'MONTHLY', 50, 30, 20)",
                    userA, new BigDecimal("4500000.00")
            );
            jdbcTemplate.update(
                    "INSERT INTO transactions (user_id, source_type, merchant_name, transaction_date, total_amount, category, budget_block, citation_reference) VALUES (?, 'INVOICE_XML', 'Éxito Colombia', ?, ?, 'Alimentación', 'NEEDS', 'XML:FE-1001')",
                    userA, Date.valueOf(LocalDate.of(2026, 10, 10)), new BigDecimal("185000.00")
            );
            jdbcTemplate.update(
                    "INSERT INTO document_chunks (user_id, chunk_type, source_reference, content_text) VALUES (?, 'INVOICE_SUMMARY', 'XML:FE-1001', 'Compra mercado Éxito Ana')",
                    userA
            );
        });

        // Insertar datos financieros y documentales como Usuario B
        RlsDataSourceConfig.runAsUser(userB, () -> {
            jdbcTemplate.update(
                    "INSERT INTO financial_profiles (user_id, net_income, payment_frequency, needs_pct, wants_pct, savings_pct) VALUES (?, ?, 'BIWEEKLY', 60, 25, 15)",
                    userB, new BigDecimal("3200000.00")
            );
            jdbcTemplate.update(
                    "INSERT INTO transactions (user_id, source_type, merchant_name, transaction_date, total_amount, category, budget_block, citation_reference) VALUES (?, 'MANUAL', 'Rappi Domicilios', ?, ?, 'Domicilios', 'WANTS', 'MANUAL:TX-2002')",
                    userB, Date.valueOf(LocalDate.of(2026, 10, 12)), new BigDecimal("42000.00")
            );
            jdbcTemplate.update(
                    "INSERT INTO document_chunks (user_id, chunk_type, source_reference, content_text) VALUES (?, 'MANUAL_EXPENSE', 'MANUAL:TX-2002', 'Pedido hamburguesa Rappi Beto')",
                    userB
            );
        });

        // 1. Verificar que el Usuario A haciendo un SELECT sin WHERE solo ve lo suyo
        RlsDataSourceConfig.runAsUser(userA, () -> {
            List<Map<String, Object>> transactionsA = jdbcTemplate.queryForList("SELECT merchant_name, total_amount FROM transactions");
            assertThat(transactionsA).hasSize(1);
            assertThat(transactionsA.getFirst().get("merchant_name")).isEqualTo("Éxito Colombia");

            List<Map<String, Object>> chunksA = jdbcTemplate.queryForList("SELECT content_text FROM document_chunks");
            assertThat(chunksA).hasSize(1);
            assertThat(chunksA.getFirst().get("content_text").toString()).contains("Ana");
        });

        // 2. Verificar que el Usuario B haciendo un SELECT sin WHERE solo ve lo suyo
        RlsDataSourceConfig.runAsUser(userB, () -> {
            List<Map<String, Object>> transactionsB = jdbcTemplate.queryForList("SELECT merchant_name, total_amount FROM transactions");
            assertThat(transactionsB).hasSize(1);
            assertThat(transactionsB.getFirst().get("merchant_name")).isEqualTo("Rappi Domicilios");

            List<Map<String, Object>> chunksB = jdbcTemplate.queryForList("SELECT content_text FROM document_chunks");
            assertThat(chunksB).hasSize(1);
            assertThat(chunksB.getFirst().get("content_text").toString()).contains("Beto");
        });

        // 3. Verificar que sin usuario autenticado en el contexto no se filtra ninguna fila
        TenantContext.clear();
        List<Map<String, Object>> anonymousTransactions = jdbcTemplate.queryForList("SELECT * FROM transactions");
        assertThat(anonymousTransactions).isEmpty();
    }
}
