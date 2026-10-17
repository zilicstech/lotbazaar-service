-- End-to-end workflow: dock check, putaway, photos, holds, payments, offers, pick/pack, invoices, claims.

-- Container lifecycle: ARRIVED (at dock) -> SORTING (dock check done) -> SORTED.
ALTER TABLE container DROP CONSTRAINT container_status_check;
UPDATE container SET status = 'SORTED' WHERE status = 'CLOSED';
ALTER TABLE container ADD CONSTRAINT container_status_check CHECK (status IN ('ARRIVED', 'SORTING', 'SORTED'));
ALTER TABLE container ALTER COLUMN status SET DEFAULT 'ARRIVED';
ALTER TABLE container
    ADD COLUMN expected_pieces int CHECK (expected_pieces > 0),
    ADD COLUMN expected_kg     numeric(10, 2) CHECK (expected_kg > 0),
    ADD COLUMN actual_bales    int,
    ADD COLUMN actual_kg       numeric(10, 2),
    ADD COLUMN checked_at      timestamptz,
    ADD COLUMN sorted_at       timestamptz;

-- Bins and putaway: a sorted carton becomes sellable (AVAILABLE) only once it sits in a bin.
CREATE TABLE bin_location (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    warehouse_id uuid NOT NULL REFERENCES warehouse(id),
    code         varchar(20) NOT NULL,
    UNIQUE (tenant_id, warehouse_id, code)
);
ALTER TABLE handling_unit ADD COLUMN bin_id uuid REFERENCES bin_location(id);
ALTER TABLE handling_unit DROP CONSTRAINT handling_unit_status_check;
ALTER TABLE handling_unit ADD CONSTRAINT handling_unit_status_check
    CHECK (status IN ('SORTED', 'AVAILABLE', 'RESERVED', 'SOLD', 'QUARANTINE'));
ALTER TABLE handling_unit ALTER COLUMN status SET DEFAULT 'SORTED';

-- GST: HSN per category, one flat rate per tenant (shortcut: per-HSN slabs need CA sign-off).
ALTER TABLE category ADD COLUMN hsn varchar(8);
ALTER TABLE tenant ADD COLUMN gst_rate numeric(4, 2) NOT NULL DEFAULT 5;

-- Lots: description, photos, offer settings, RESERVED while a buyer pays.
ALTER TABLE lot DROP CONSTRAINT lot_status_check;
ALTER TABLE lot ADD CONSTRAINT lot_status_check CHECK (status IN ('DRAFT', 'LISTED', 'RESERVED', 'SOLD'));
ALTER TABLE lot
    ADD COLUMN description     text,
    ADD COLUMN accept_offers   boolean NOT NULL DEFAULT false,
    ADD COLUMN offer_floor_pct numeric(5, 2);

CREATE TABLE lot_photo (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    lot_id       uuid NOT NULL REFERENCES lot(id),
    content_type varchar(30) NOT NULL,
    bytes        bytea NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX lot_photo_lot_idx ON lot_photo (lot_id);

-- Orders: full lifecycle. amount = taxable value; total = amount + GST.
ALTER TABLE sales_order DROP CONSTRAINT sales_order_status_check;
ALTER TABLE sales_order ADD CONSTRAINT sales_order_status_check CHECK (status IN
    ('PAYMENT_PENDING', 'PAID', 'PICKING', 'PACKED', 'DISPATCHED', 'DELIVERED', 'CLOSED', 'CANCELLED'));
ALTER TABLE sales_order DROP CONSTRAINT sales_order_lot_id_key;
-- A lot can have cancelled (expired) orders, but only one live order.
CREATE UNIQUE INDEX sales_order_live_lot ON sales_order (lot_id) WHERE status <> 'CANCELLED';
ALTER TABLE sales_order
    ADD COLUMN source          varchar(10) NOT NULL DEFAULT 'BUY_NOW' CHECK (source IN ('BUY_NOW', 'OFFER')),
    ADD COLUMN hold_expires_at timestamptz,
    ADD COLUMN gst_rate        numeric(4, 2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst            numeric(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst            numeric(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN igst            numeric(14, 2) NOT NULL DEFAULT 0,
    ADD COLUMN total           numeric(14, 2),
    ADD COLUMN paid_at         timestamptz,
    ADD COLUMN packed_at       timestamptz,
    ADD COLUMN dispatch_kg     numeric(10, 2),
    ADD COLUMN transporter     varchar(100),
    ADD COLUMN vehicle_no      varchar(20),
    ADD COLUMN delivered_at    timestamptz,
    ADD COLUMN closed_at       timestamptz;
UPDATE sales_order SET total = amount, paid_at = created_at WHERE total IS NULL;
ALTER TABLE sales_order ALTER COLUMN total SET NOT NULL;

CREATE TABLE payment (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    order_id    uuid NOT NULL REFERENCES sales_order(id),
    method      varchar(20) NOT NULL,
    status      varchar(10) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    gateway_ref varchar(40) NOT NULL,
    amount      numeric(14, 2) NOT NULL,
    at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_order_idx ON payment (order_id);

CREATE TABLE offer (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    lot_id         uuid NOT NULL REFERENCES lot(id),
    buyer_user_id  uuid NOT NULL REFERENCES app_user(id),
    amount         numeric(14, 2) NOT NULL CHECK (amount > 0),
    counter_amount numeric(14, 2),
    status         varchar(10) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'COUNTERED', 'ACCEPTED', 'REJECTED', 'EXPIRED')),
    note           varchar(500),
    order_id       uuid REFERENCES sales_order(id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
-- One live offer per buyer per lot.
CREATE UNIQUE INDEX offer_live_idx ON offer (lot_id, buyer_user_id) WHERE status IN ('OPEN', 'COUNTERED');

CREATE TABLE order_pick (
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    order_id  uuid NOT NULL REFERENCES sales_order(id),
    hu_id     uuid NOT NULL REFERENCES handling_unit(id),
    picked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, hu_id)
);

CREATE TABLE invoice_seq (
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    fy        varchar(7) NOT NULL,
    next_no   int NOT NULL,
    PRIMARY KEY (tenant_id, fy)
);

CREATE TABLE invoice (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant(id),
    order_id     uuid NOT NULL UNIQUE REFERENCES sales_order(id),
    invoice_no   varchar(30) NOT NULL,
    fy           varchar(7) NOT NULL,
    irn          varchar(64) NOT NULL,
    eway_bill_no varchar(12),
    issued_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, invoice_no)
);

CREATE TABLE claim (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    order_id        uuid NOT NULL REFERENCES sales_order(id),
    type            varchar(12) NOT NULL CHECK (type IN ('SHORT', 'WRONG_GRADE', 'DAMAGED', 'OTHER')),
    pieces          int NOT NULL DEFAULT 0 CHECK (pieces >= 0),
    note            varchar(1000) NOT NULL,
    status          varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'REJECTED')),
    credit_amount   numeric(14, 2),
    resolution_note varchar(1000),
    created_at      timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz
);
CREATE UNIQUE INDEX claim_one_per_order ON claim (order_id);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['bin_location', 'lot_photo', 'payment', 'offer', 'order_pick', 'invoice_seq',
                           'invoice', 'claim'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
        USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  END LOOP;
END $$;
