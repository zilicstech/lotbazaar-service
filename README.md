# LotBazaar Service

Backend API for **LotBazaar**, a multi-tenant B2B platform where liquidators sell branded apparel and footwear dead stock to verified retailers in lots of 100 to 1,000+ mixed pieces.

It covers the full Phase 1 workflow: container arrival, dock check, sorting into barcoded cartons, putaway into bins, lot building, Buy Now and offers, GST checkout, mock payment, pick by scan, pack with a weight check, dispatch with a GST invoice and e-way bill, delivery, claims and owner reporting.

The web apps (seller console and buyer storefront) live in [lotbazaar-app](https://github.com/zilicstech/lotbazaar-app).

## Tech stack

| Layer | Choice |
|---|---|
| Language and framework | Java 17, Spring Boot 4.1, Spring Modulith (modular monolith) |
| Data access | `JdbcClient` with plain SQL, Flyway migrations |
| Database | PostgreSQL 16 with row-level security (RLS) for tenant isolation |
| Auth | HS256 JWTs issued by the app (Keycloak planned) |
| Tests | JUnit 5 and MockMvc against a real local PostgreSQL test database |

## Project layout

```
src/main/java/com/lotbazaar/
  tenancy/      Tenant context, RLS-aware transaction manager, tenant filter
  identity/     Login, buyer registration, JWT, seller roles (OWNER / WAREHOUSE / SALES)
  masterdata/   Warehouses, brands, categories (HSN), grades, MRP and size bands
  inventory/    Containers, dock check, sorting into cartons, bins, putaway, stock ledger
  lots/         Lot builder, manifest, photos, listing (Buy Now price, offer floor)
  commerce/     Checkout and GST, payment, offers, pick/pack/dispatch, invoices, claims, scheduled jobs
  reporting/    Owner dashboard: recovery % per container, stock aging, order funnel, ledger drift
  demo/         Seeds demo data at every workflow stage (profile "demo" only)
src/main/resources/db/migration/   Flyway migrations V1 to V4
src/test/java/com/lotbazaar/       Workflow, concurrency and access tests
docs/                              Business plan, PRD, architecture, ADRs, epics, plans
Dockerfile                         Production image (see Deployment)
```

## Run locally

You need **PostgreSQL 16** installed on the machine (Postgres.app or `brew install postgresql@16` on Mac, the postgresql.org installer on Windows, `apt install postgresql-16` on Linux) and **Java 17+**. The Maven wrapper is included. No Docker is needed.

**1. Create the user and databases once** (in `psql` or DBeaver, as a superuser such as `postgres`):

```sql
CREATE USER lotbazaar WITH PASSWORD 'lotbazaar' CREATEROLE;  -- CREATEROLE: the first migration creates the RLS role lotbazaar_app
CREATE DATABASE lotbazaar OWNER lotbazaar;                   -- app and demo data
CREATE DATABASE lotbazaar_test OWNER lotbazaar;              -- automated tests
```

**2. Start the API** on http://localhost:8080. Flyway creates the tables; the `demo` profile seeds two tenants, users, stock, lots and orders.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

The demo profile seeds only into an empty database. To start over:

```bash
psql -d postgres -c "DROP DATABASE lotbazaar" -c "CREATE DATABASE lotbazaar OWNER lotbazaar"
```

**Viewing data in DBeaver or psql.** Every tenant table uses `FORCE ROW LEVEL SECURITY`, so even the `lotbazaar` owner sees **no rows** unless a tenant is set. Either connect as a superuser (sees everything), or set the tenant first in the same session:

```sql
SELECT set_config('app.tenant_id', (SELECT id::text FROM tenant WHERE code = 'rk-liquidators'), false);
```

### Configuration

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/lotbazaar` | Used by the app and by Flyway |
| `DB_USER` / `DB_PASSWORD` | `lotbazaar` / `lotbazaar` | Must own the schema (Flyway runs DDL) |
| `JWT_SECRET` | dev-only value | **Set a long random value in every non-local environment** |

### Demo accounts

Demo users are created by `src/main/java/com/lotbazaar/demo/DemoDataSeeder.java`, which also holds the shared demo password.

| Account | Role |
|---|---|
| `owner@rk.demo` | Seller owner: everything, including costs, dashboard and claims |
| `warehouse@rk.demo` | Warehouse: dock check, sorting, putaway, pick, pack, dispatch (never sees costs) |
| `sales@rk.demo` | Sales: lots, photos, listing, buyers, offers, orders |
| `owner@acme.demo` | Owner of a second tenant, to show isolation |
| `buyer@shop.demo` | Approved buyer, Jaipur (inter-state: IGST) |
| `delhi@shop.demo` | Approved buyer, Delhi (same state: CGST + SGST) |
| `newbuyer@shop.demo` | Buyer pending approval |

## API overview

All endpoints are under `/api`. Errors use RFC 7807 problem details.

| Prefix | Who | Main endpoints |
|---|---|---|
| `/api/auth` | Anyone | `POST /login`, `POST /register-buyer`, `GET /me` |
| `/api/public` | Anyone | Storefront list, lot photos |
| `/api/seller` | Seller staff (per-role) | Masters, containers, dock check, cartons, finish sorting, bins, putaway, stock, dashboard |
| `/api/seller/lots` | Owner, Sales | Build lot, photos, list for sale |
| `/api/seller` (sales) | Owner, Sales | Buyers (approve / block), offers (accept / counter / reject), orders, invoices |
| `/api/seller/claims` | Owner | Resolve with credit, or reject |
| `/api/seller/wh/orders` | Owner, Warehouse | Fulfilment queue, pick list, scan, pack, dispatch |
| `/api/store/{tenantCode}` | Buyers | Lots, checkout, offers, pay, orders, delivery, claims, invoices |

Role rules are in `SecurityConfig.java`.

## How it works

**Inventory model.** Container, then sort bucket (the inventory SKU: brand, gender, category, grade, MRP band, size band), then carton (a barcoded handling unit in one bin), then lot (the sellable unit, a set of cartons). Pieces are counted, never scanned one by one.

**Order lifecycle.** `PAYMENT_PENDING` (Buy Now holds 15 minutes, accepted offers 24 hours) → `PAID` → `PICKING` → `PACKED` → `DISPATCHED` → `DELIVERED` → `CLOSED`, or `CANCELLED` when a hold expires. A scheduled job releases expired holds and auto-closes delivered orders after the 72-hour claim window.

**No overselling.** A guarded `LISTED → RESERVED` update means exactly one buyer wins a lot; a partial unique index allows one live order per lot. Tested with 100 concurrent buyers.

**GST.** Seller state comes from the warehouse GSTIN, buyer state from the buyer GSTIN: same state gives CGST + SGST, otherwise IGST. Invoice numbers are gapless per financial year and at most 16 characters (for example `RK/2627/000005`). An e-way bill is generated above ₹50,000.

**Stock ledger.** Every carton movement (receive, putaway, reserve, sell) appends a row to `inventory_ledger`. The dashboard and the tests check that the ledger total equals the pieces on hand.

**Multi-tenancy.** Every tenant table has `tenant_id` with `ENABLE` and `FORCE ROW LEVEL SECURITY`. `TenantAwareTransactionManager` switches each transaction to the `lotbazaar_app` role and sets `app.tenant_id`; a request without a tenant sees no rows. Run raw SQL inside a transaction, otherwise RLS silently matches nothing.

## Tests

```bash
./mvnw test        # uses the local lotbazaar_test database
```

Set `TEST_DB_URL` to use a different test database. Each test creates its own random tenants, so the database can be reused between runs. CI runs the same suite against a PostgreSQL service on GitHub Actions.

The suite (25 tests) covers the inbound flow, sales and offers, fulfilment and invoicing, claims, role access, tenant isolation, dashboard figures and concurrent Buy Now.

## Known shortcuts before a pilot

Each is marked `shortcut:` in the code.

- The app issues its own JWTs instead of Keycloak.
- Payment is a mock gateway (planned: Razorpay or Cashfree with signed webhooks).
- IRN and e-way bill numbers are generated locally (planned: GSP sandbox). One flat GST rate per tenant.
- Lot photos are stored in PostgreSQL behind an unguessable URL (planned: S3 with signed CDN URLs).
- Not built yet: auctions, warehouse-to-warehouse transfers, bin-to-bin moves, cycle counts.

## Deployment

The `Dockerfile` builds a production image: the jar is compiled on JDK 17 and runs on a slim Java 17 runtime as a non-root user, on port 8080.

```bash
docker build -t lotbazaar-service .
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://<db-host>:5432/lotbazaar -e DB_USER=lotbazaar -e DB_PASSWORD=<secret> \
  -e JWT_SECRET=<long-random-secret> lotbazaar-service
```

Do not enable the `demo` profile in production. The database user needs `CREATEROLE` for the first migration, and it should not be a superuser (row-level security must apply).

## Documentation

`docs/` holds the business plan, PRD, product scope, SKU and inventory model, architecture, ADRs 001 to 004, epics, an architecture review and the end-to-end implementation plan.
