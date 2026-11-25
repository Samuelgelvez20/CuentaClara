-- =================================================================================
-- V2__budget_plans_and_categories.sql
-- Modelo de datos para el Plan de Gastos del Mes Siguiente, Categorías y RLS
-- =================================================================================

-- 1. Añadir needs_cut_percent a financial_profiles (0-30, por defecto 10)
ALTER TABLE financial_profiles
    ADD COLUMN IF NOT EXISTS needs_cut_percent INTEGER NOT NULL DEFAULT 10
        CHECK (needs_cut_percent >= 0 AND needs_cut_percent <= 30);

-- 2. Añadir embedding de descripción corta en transactions para agrupación de gastos hormiga con <=>
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS description_embedding vector(1536);

-- 3. Tabla categories (con block NEEDS/WANTS/SAVINGS e is_fixed configurable por usuario)
CREATE TABLE IF NOT EXISTS categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    block VARCHAR(20) NOT NULL CHECK (block IN ('NEEDS', 'WANTS', 'SAVINGS')),
    is_fixed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_categories_user_name UNIQUE (user_id, name)
);

CREATE INDEX IF NOT EXISTS idx_categories_user ON categories (user_id);

-- 4. Tabla budget_plans (Único por user_id, target_month)
CREATE TABLE IF NOT EXISTS budget_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_month DATE NOT NULL,
    income NUMERIC(15, 0) NOT NULL CHECK (income > 0),
    pct_needs INTEGER NOT NULL CHECK (pct_needs >= 0 AND pct_needs <= 100),
    pct_wants INTEGER NOT NULL CHECK (pct_wants >= 0 AND pct_wants <= 100),
    pct_savings INTEGER NOT NULL CHECK (pct_savings >= 0 AND pct_savings <= 100),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACCEPTED')),
    warnings TEXT NOT NULL DEFAULT '',
    structural_gap NUMERIC(15, 0) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    accepted_at TIMESTAMPTZ,
    CONSTRAINT uq_budget_plans_user_target_month UNIQUE (user_id, target_month),
    CONSTRAINT chk_budget_plans_pct_sum CHECK (pct_needs + pct_wants + pct_savings = 100)
);

CREATE INDEX IF NOT EXISTS idx_budget_plans_user_month ON budget_plans (user_id, target_month);

-- 5. Tabla budget_lines
CREATE TABLE IF NOT EXISTS budget_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES budget_plans(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    category_name VARCHAR(80) NOT NULL,
    block VARCHAR(20) NOT NULL CHECK (block IN ('NEEDS', 'WANTS', 'SAVINGS')),
    last_month_amount NUMERIC(15, 0) NOT NULL DEFAULT 0,
    limit_amount NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (limit_amount >= 0),
    is_fixed BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(30) NOT NULL CHECK (reason IN ('FIXED_KEPT', 'VARIABLE_CUT', 'PROPORTIONAL', 'EQUAL_SPLIT', 'SAVINGS_GOAL'))
);

CREATE INDEX IF NOT EXISTS idx_budget_lines_plan ON budget_lines (plan_id);
CREATE INDEX IF NOT EXISTS idx_budget_lines_user ON budget_lines (user_id);

-- 6. Tabla budget_plan_weeks
CREATE TABLE IF NOT EXISTS budget_plan_weeks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES budget_plans(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    week_index INTEGER NOT NULL CHECK (week_index >= 1),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    wants_limit NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (wants_limit >= 0),
    variable_needs_limit NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (variable_needs_limit >= 0)
);

CREATE INDEX IF NOT EXISTS idx_budget_plan_weeks_plan ON budget_plan_weeks (plan_id);
CREATE INDEX IF NOT EXISTS idx_budget_plan_weeks_user ON budget_plan_weeks (user_id);

-- 7. Tabla budget_plan_actions
CREATE TABLE IF NOT EXISTS budget_plan_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES budget_plans(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 1 AND position <= 3),
    title VARCHAR(60) NOT NULL,
    explanation VARCHAR(280) NOT NULL,
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    category_name VARCHAR(80),
    evidence_transaction_ids UUID[] NOT NULL DEFAULT '{}',
    source VARCHAR(20) NOT NULL CHECK (source IN ('AI', 'FALLBACK'))
);

CREATE INDEX IF NOT EXISTS idx_budget_plan_actions_plan ON budget_plan_actions (plan_id);
CREATE INDEX IF NOT EXISTS idx_budget_plan_actions_user ON budget_plan_actions (user_id);

-- 8. Permisos y Row Level Security (RLS) para todas las tablas nuevas
GRANT SELECT, INSERT, UPDATE, DELETE ON categories, budget_plans, budget_lines, budget_plan_weeks, budget_plan_actions TO cuentaclara_app;

DO $$
DECLARE
    t TEXT;
    new_tenant_tables TEXT[] := ARRAY[
        'categories',
        'budget_plans',
        'budget_lines',
        'budget_plan_weeks',
        'budget_plan_actions'
    ];
BEGIN
    FOREACH t IN ARRAY new_tenant_tables LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY;', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY;', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation_policy ON %I FOR ALL TO cuentaclara_app USING (user_id = current_app_user_id()) WITH CHECK (user_id = current_app_user_id());',
            t
        );
    END LOOP;
END $$;
