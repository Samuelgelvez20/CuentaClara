-- =================================================================================
-- V1__init_schema_and_rls.sql
-- Esquema completo de CuentaClara con Row Level Security (RNF-01, RF-01..RF-17)
-- =================================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Asegurar que el rol de aplicación sin privilegios de propietario exista (RNF-01)
DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'cuentaclara_app') THEN
        CREATE ROLE cuentaclara_app WITH LOGIN PASSWORD 'app_secret_dev' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO cuentaclara_app;

-- =================================================================================
-- 2. TABLA DE USUARIOS (RF-01, RF-14, RNF-02, RNF-04)
-- =================================================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    ai_consent_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ai_consent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =================================================================================
-- 3. DOMINIO DE PERFIL FINANCIERO Y PLANIFICACIÓN (RF-11, RF-15, RF-16, RF-17)
-- =================================================================================
CREATE TABLE financial_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    net_income NUMERIC(15, 2) NOT NULL CHECK (net_income >= 0),
    payment_frequency VARCHAR(20) NOT NULL CHECK (payment_frequency IN ('MONTHLY', 'BIWEEKLY', 'WEEKLY', 'VARIABLE')),
    payment_days VARCHAR(100) NOT NULL DEFAULT '30',
    needs_pct INTEGER NOT NULL DEFAULT 50 CHECK (needs_pct >= 0 AND needs_pct <= 100),
    wants_pct INTEGER NOT NULL DEFAULT 30 CHECK (wants_pct >= 0 AND wants_pct <= 100),
    savings_pct INTEGER NOT NULL DEFAULT 20 CHECK (savings_pct >= 0 AND savings_pct <= 100),
    financial_goals TEXT,
    total_debts NUMERIC(15, 2) NOT NULL DEFAULT 0 CHECK (total_debts >= 0),
    questionnaire_completed BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_budget_pct_sum CHECK (needs_pct + wants_pct + savings_pct = 100)
);

CREATE TABLE recurring_obligations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    category VARCHAR(60) NOT NULL,
    budget_block VARCHAR(20) NOT NULL DEFAULT 'NEEDS' CHECK (budget_block IN ('NEEDS', 'WANTS', 'SAVINGS')),
    estimated_amount NUMERIC(15, 2) NOT NULL CHECK (estimated_amount >= 0),
    due_day INTEGER NOT NULL CHECK (due_day >= 1 AND due_day <= 31),
    is_essential BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE incomes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_name VARCHAR(150) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL CHECK (amount > 0),
    received_date DATE NOT NULL,
    period_type VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    is_simulated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =================================================================================
-- 4. DOMINIO DOCUMENTAL Y VECTORIAL (RF-02..RF-08, RNF-03, RNF-05, RNF-08)
-- =================================================================================
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    source_type VARCHAR(30) NOT NULL CHECK (source_type IN ('XML_UBL', 'PDF_DIGITAL', 'PDF_SCANNED', 'IMAGE', 'STATEMENT_PDF')),
    file_size_bytes BIGINT NOT NULL CHECK (file_size_bytes > 0 AND file_size_bytes <= 10485760),
    sha256_hash VARCHAR(64) NOT NULL,
    cufe VARCHAR(150),
    invoice_number VARCHAR(100),
    issuer_nit VARCHAR(50),
    issuer_name VARCHAR(200),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'AWAITING_CONFIRMATION', 'INDEXED', 'ERROR')),
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0 AND retry_count <= 3),
    temp_file_path VARCHAR(500),
    temp_file_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    error_message TEXT,
    page_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_documents_user_hash UNIQUE (user_id, sha256_hash)
);

CREATE UNIQUE INDEX uq_documents_user_cufe ON documents (user_id, cufe) WHERE cufe IS NOT NULL;
CREATE UNIQUE INDEX uq_documents_user_inv_nit ON documents (user_id, issuer_nit, invoice_number)
    WHERE issuer_nit IS NOT NULL AND invoice_number IS NOT NULL;

CREATE TABLE document_extractions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID NOT NULL UNIQUE REFERENCES documents(id) ON DELETE CASCADE,
    merchant_name VARCHAR(200),
    merchant_nit VARCHAR(50),
    invoice_number VARCHAR(100),
    cufe VARCHAR(150),
    issue_date DATE,
    subtotal_amount NUMERIC(15, 2),
    tax_amount NUMERIC(15, 2),
    discount_amount NUMERIC(15, 2) DEFAULT 0,
    total_amount NUMERIC(15, 2),
    suggested_category VARCHAR(60),
    suggested_budget_block VARCHAR(20) DEFAULT 'NEEDS',
    items_json TEXT,
    raw_extracted_text TEXT,
    extraction_method VARCHAR(40) NOT NULL,
    confirmed_by_user BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE document_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID REFERENCES documents(id) ON DELETE CASCADE,
    transaction_id UUID,
    chunk_type VARCHAR(30) NOT NULL CHECK (chunk_type IN ('INVOICE_SUMMARY', 'ITEM_GROUP', 'MANUAL_EXPENSE', 'STATEMENT_WINDOW')),
    chunk_order INTEGER NOT NULL DEFAULT 0,
    page_number INTEGER,
    source_reference VARCHAR(255) NOT NULL,
    content_text TEXT NOT NULL,
    content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('spanish', coalesce(content_text, ''))) STORED,
    embedding vector(1536),
    embedding_model VARCHAR(80) NOT NULL DEFAULT 'text-embedding-3-small',
    embedding_version VARCHAR(30) NOT NULL DEFAULT 'v1',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_chunks_user ON document_chunks (user_id);
CREATE INDEX idx_document_chunks_tsv ON document_chunks USING GIN (content_tsv);
CREATE INDEX idx_document_chunks_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops);

-- =================================================================================
-- 5. TRANSACCIONES, REGLAS DE CATEGORIZACIÓN Y PRESUPUESTOS (RF-09, RF-10, RF-13)
-- =================================================================================
CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    source_type VARCHAR(30) NOT NULL CHECK (source_type IN ('INVOICE_XML', 'INVOICE_PDF', 'INVOICE_IMAGE', 'MANUAL')),
    merchant_name VARCHAR(200) NOT NULL,
    merchant_nit VARCHAR(50),
    invoice_number VARCHAR(100),
    cufe VARCHAR(150),
    transaction_date DATE NOT NULL,
    subtotal_amount NUMERIC(15, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(15, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(15, 2) NOT NULL CHECK (total_amount >= 0),
    category VARCHAR(60) NOT NULL,
    budget_block VARCHAR(20) NOT NULL CHECK (budget_block IN ('NEEDS', 'WANTS', 'SAVINGS')),
    description TEXT,
    items_summary TEXT,
    citation_reference VARCHAR(255) NOT NULL,
    confirmed BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_user_date ON transactions (user_id, transaction_date DESC);
CREATE INDEX idx_transactions_user_category ON transactions (user_id, category);

CREATE TABLE user_category_corrections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    merchant_pattern VARCHAR(200) NOT NULL,
    context_text TEXT NOT NULL,
    confirmed_category VARCHAR(60) NOT NULL,
    confirmed_budget_block VARCHAR(20) NOT NULL CHECK (confirmed_budget_block IN ('NEEDS', 'WANTS', 'SAVINGS')),
    embedding vector(1536),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_cat_corr_user ON user_category_corrections (user_id);

CREATE TABLE budgets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_label VARCHAR(40) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    budget_stage VARCHAR(25) NOT NULL CHECK (budget_stage IN ('INITIAL_ESTIMATE', 'OBSERVED_PLAN')),
    status VARCHAR(25) NOT NULL DEFAULT 'SUGGESTED' CHECK (status IN ('SUGGESTED', 'ACCEPTED', 'EDITED', 'IGNORED')),
    reference_income NUMERIC(15, 2) NOT NULL CHECK (reference_income >= 0),
    needs_pct INTEGER NOT NULL,
    wants_pct INTEGER NOT NULL,
    savings_pct INTEGER NOT NULL,
    needs_limit NUMERIC(15, 2) NOT NULL,
    wants_limit NUMERIC(15, 2) NOT NULL,
    savings_target NUMERIC(15, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_budgets_pct_sum CHECK (needs_pct + wants_pct + savings_pct = 100)
);

CREATE TABLE budget_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    budget_id UUID NOT NULL REFERENCES budgets(id) ON DELETE CASCADE,
    category VARCHAR(60) NOT NULL,
    budget_block VARCHAR(20) NOT NULL CHECK (budget_block IN ('NEEDS', 'WANTS', 'SAVINGS')),
    allocated_amount NUMERIC(15, 2) NOT NULL CHECK (allocated_amount >= 0),
    rationale TEXT
);

CREATE TABLE monthly_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_label VARCHAR(40) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    declared_income NUMERIC(15, 2) NOT NULL,
    actual_income NUMERIC(15, 2) NOT NULL,
    needs_pct INTEGER NOT NULL,
    wants_pct INTEGER NOT NULL,
    savings_pct INTEGER NOT NULL,
    needs_spent NUMERIC(15, 2) NOT NULL,
    wants_spent NUMERIC(15, 2) NOT NULL,
    savings_achieved NUMERIC(15, 2) NOT NULL,
    essential_exceeded BOOLEAN NOT NULL DEFAULT FALSE,
    verdict_summary TEXT NOT NULL,
    recommendations_json TEXT NOT NULL,
    suggested_budget_id UUID REFERENCES budgets(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =================================================================================
-- 6. PERMISOS AL ROL DE APLICACIÓN Y ROW LEVEL SECURITY (RNF-01)
-- =================================================================================
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cuentaclara_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO cuentaclara_app;

-- Función auxiliar que obtiene el UUID del usuario actual en la sesión PostgreSQL
CREATE OR REPLACE FUNCTION current_app_user_id() RETURNS UUID AS $$
DECLARE
    val TEXT;
BEGIN
    val := current_setting('app.current_user_id', true);
    IF val IS NULL OR val = '' THEN
        RETURN NULL;
    END IF;
    RETURN val::UUID;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- Activar y forzar Row Level Security en todas las tablas con datos por usuario
DO $$
DECLARE
    t TEXT;
    tenant_tables TEXT[] := ARRAY[
        'financial_profiles',
        'recurring_obligations',
        'incomes',
        'documents',
        'document_extractions',
        'document_chunks',
        'transactions',
        'user_category_corrections',
        'budgets',
        'budget_allocations',
        'monthly_reports'
    ];
BEGIN
    FOREACH t IN ARRAY tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY;', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY;', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation_policy ON %I FOR ALL TO cuentaclara_app USING (user_id = current_app_user_id()) WITH CHECK (user_id = current_app_user_id());',
            t
        );
    END LOOP;
END $$;
