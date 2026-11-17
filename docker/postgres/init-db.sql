-- Inicialización del entorno PostgreSQL para CuentaClara (RNF-01)
-- 1. Extensiones requeridas para vectores (pgvector) y UUIDs
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 2. Crear el rol de aplicación sin privilegios de propietario ni BYPASSRLS
DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'cuentaclara_app') THEN
        CREATE ROLE cuentaclara_app WITH LOGIN PASSWORD 'app_secret_dev' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

-- 3. Permisos sobre el esquema public para que cuentaclara_app opere bajo RLS
GRANT CONNECT ON DATABASE cuentaclara TO cuentaclara_app;
GRANT USAGE ON SCHEMA public TO cuentaclara_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cuentaclara_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO cuentaclara_app;
