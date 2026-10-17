-- Runtime role: never owns tables and has no BYPASSRLS, so RLS always applies (ADR-002).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'lotbazaar_app') THEN
    CREATE ROLE lotbazaar_app NOLOGIN NOBYPASSRLS;
  END IF;
END $$;
GRANT lotbazaar_app TO CURRENT_USER;
GRANT USAGE ON SCHEMA public TO lotbazaar_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lotbazaar_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO lotbazaar_app;

-- Platform-scoped: no RLS.
CREATE TABLE tenant (
    id          uuid PRIMARY KEY,
    code        varchar(40)  NOT NULL UNIQUE,
    legal_name  varchar(200) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz  NOT NULL DEFAULT now()
);

-- Tenant-scoped template: tenant_id + ENABLE/FORCE RLS + tenant_isolation policy.
CREATE TABLE warehouse (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant(id),
    code        varchar(40)  NOT NULL,
    name        varchar(200) NOT NULL,
    state_code  char(2)      NOT NULL,
    gstin       varchar(15),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);
ALTER TABLE warehouse ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouse FORCE ROW LEVEL SECURITY;
-- Missing or empty app.tenant_id resolves to NULL and matches nothing (fail closed).
CREATE POLICY tenant_isolation ON warehouse
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
