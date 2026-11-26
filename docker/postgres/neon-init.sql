-- =================================================================================
-- Inicialización de Neon.tech (PostgreSQL + pgvector + Row Level Security)
-- Ejecutar UNA SOLA VEZ desde el SQL Editor de Neon usando tu rol propietario (neondb_owner).
-- IMPORTANTE: Cambia 'CAMBIA_ESTA_CLAVE_SEGURA' antes de ejecutar y usa esa misma clave en DB_APP_PASSWORD.
-- =================================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'cuentaclara_app') THEN
        CREATE ROLE cuentaclara_app WITH LOGIN PASSWORD 'CAMBIA_ESTA_CLAVE_SEGURA'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    ELSE
        ALTER ROLE cuentaclara_app WITH PASSWORD 'CAMBIA_ESTA_CLAVE_SEGURA'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

-- Permite que tu usuario dueño de Neon (neondb_owner) otorgue permisos a cuentaclara_app en Flyway
GRANT cuentaclara_app TO CURRENT_USER;

GRANT CONNECT ON DATABASE neondb TO cuentaclara_app;
GRANT USAGE, CREATE ON SCHEMA public TO cuentaclara_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cuentaclara_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO cuentaclara_app;
