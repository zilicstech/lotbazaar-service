-- Platform-scoped: users of every kind. Seller users carry tenant_id; buyers are global (ADR-002).
CREATE TABLE app_user (
    id            uuid PRIMARY KEY,
    email         varchar(200) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    name          varchar(200) NOT NULL,
    kind          varchar(10)  NOT NULL CHECK (kind IN ('SELLER', 'BUYER', 'ADMIN')),
    tenant_id     uuid REFERENCES tenant(id),
    role          varchar(20)  NOT NULL,
    business_name varchar(200),
    gstin         varchar(15),
    city          varchar(100),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CHECK ((kind = 'SELLER') = (tenant_id IS NOT NULL))
);

CREATE TABLE buyer_tenant_relationship (
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    buyer_user_id  uuid NOT NULL REFERENCES app_user(id),
    status         varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'BLOCKED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, buyer_user_id)
);

CREATE TABLE brand (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    code      varchar(10)  NOT NULL,
    name      varchar(100) NOT NULL,
    UNIQUE (tenant_id, code)
);

CREATE TABLE category (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    code      varchar(20)  NOT NULL,
    name      varchar(100) NOT NULL,
    UNIQUE (tenant_id, code)
);

CREATE TABLE container (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    ref          varchar(40)  NOT NULL,
    supplier     varchar(200) NOT NULL,
    warehouse_id uuid NOT NULL REFERENCES warehouse(id),
    landed_cost  numeric(14, 2) NOT NULL CHECK (landed_cost >= 0),
    status       varchar(20) NOT NULL DEFAULT 'SORTING' CHECK (status IN ('SORTING', 'CLOSED')),
    received_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, ref)
);

-- Inventory SKU (ADR-003): one row per distinct attribute set.
CREATE TABLE sort_bucket (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    sku_code    varchar(80) NOT NULL,
    brand_id    uuid NOT NULL REFERENCES brand(id),
    category_id uuid NOT NULL REFERENCES category(id),
    gender      char(1) NOT NULL CHECK (gender IN ('M', 'W', 'K', 'U')),
    grade       char(1) NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    mrp_band    varchar(20) NOT NULL,
    UNIQUE (tenant_id, sku_code)
);

CREATE TABLE lot (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    code         varchar(30)  NOT NULL,
    title        varchar(200) NOT NULL,
    status       varchar(20)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'LISTED', 'SOLD')),
    manifest     jsonb NOT NULL DEFAULT '{}',
    piece_count  int NOT NULL DEFAULT 0,
    total_mrp    numeric(14, 2) NOT NULL DEFAULT 0,
    price_pct    numeric(5, 2),
    price_amount numeric(14, 2),
    created_at   timestamptz NOT NULL DEFAULT now(),
    listed_at    timestamptz,
    UNIQUE (tenant_id, code)
);

-- Handling unit = physical carton with LPN, one bucket per carton.
CREATE TABLE handling_unit (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    lpn          varchar(30) NOT NULL,
    container_id uuid NOT NULL REFERENCES container(id),
    bucket_id    uuid NOT NULL REFERENCES sort_bucket(id),
    warehouse_id uuid NOT NULL REFERENCES warehouse(id),
    piece_count  int NOT NULL CHECK (piece_count > 0),
    weight_g     int NOT NULL CHECK (weight_g > 0),
    total_mrp    numeric(14, 2) NOT NULL CHECK (total_mrp > 0),
    size_tally   jsonb NOT NULL DEFAULT '{}',
    status       varchar(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'RESERVED', 'SOLD', 'QUARANTINE')),
    lot_id       uuid REFERENCES lot(id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, lpn)
);
CREATE INDEX handling_unit_status_idx ON handling_unit (tenant_id, status);
CREATE INDEX handling_unit_lot_idx ON handling_unit (lot_id);

CREATE TABLE inventory_ledger (
    id          bigserial PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    hu_id       uuid NOT NULL REFERENCES handling_unit(id),
    event_type  varchar(20) NOT NULL,
    hu_delta    int NOT NULL,
    piece_delta int NOT NULL,
    ref_type    varchar(20),
    ref_id      uuid,
    at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sales_order (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant(id),
    order_no      varchar(30) NOT NULL,
    buyer_user_id uuid NOT NULL REFERENCES app_user(id),
    lot_id        uuid NOT NULL UNIQUE REFERENCES lot(id),
    amount        numeric(14, 2) NOT NULL,
    status        varchar(20) NOT NULL CHECK (status IN ('PAID', 'DISPATCHED')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    dispatched_at timestamptz,
    UNIQUE (tenant_id, order_no)
);

-- Same fail-closed policy as warehouse on every tenant-scoped table.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['buyer_tenant_relationship', 'brand', 'category', 'container', 'sort_bucket',
                           'lot', 'handling_unit', 'inventory_ledger', 'sales_order'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
        USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  END LOOP;
END $$;
