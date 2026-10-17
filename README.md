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
| Tests | JUnit 5, Testcontainers (real PostgreSQL), MockMvc |

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
docker-compose.yml                 PostgreSQL, Redis, RabbitMQ, Keycloak for local development
```

## Run locally

You need Docker (Colima works), Java 17+ and nothing else; the Maven wrapper is included.

```bash
# 1. Infrastructure. PostgreSQL listens on 5433 so it does not clash with a local install on 5432.
docker compose up -d postgres redis rabbitmq

# 2. API on http://localhost:8080. The "demo" profile seeds two tenants, users, stock, lots and orders.
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

The demo profile seeds only into an empty database. To start over:

```bash
docker exec <postgres-container> psql -U lotbazaar -d postgres \
  -c "DROP DATABASE lotbazaar" -c "CREATE DATABASE lotbazaar OWNER lotbazaar"
```

### Configuration

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/lotbazaar` | Used by the app and by Flyway |
| `DB_USER` / `DB_PASSWORD` | `lotbazaar` / `lotbazaar` | Must own the schema (Flyway runs DDL) |
| `JWT_SECRET` | dev-only value | **Set a long random value in every non-local environment** |
| `REDIS_HOST` / `RABBIT_HOST` | `localhost` | |

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
./mvnw test        # needs Docker for Testcontainers
```

On Colima, first run `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

The suite (25 tests) covers the inbound flow, sales and offers, fulfilment and invoicing, claims, role access, tenant isolation, dashboard figures and concurrent Buy Now.

## Known shortcuts before a pilot

Each is marked `shortcut:` in the code.

- The app issues its own JWTs instead of Keycloak.
- Payment is a mock gateway (planned: Razorpay or Cashfree with signed webhooks).
- IRN and e-way bill numbers are generated locally (planned: GSP sandbox). One flat GST rate per tenant.
- Lot photos are stored in PostgreSQL behind an unguessable URL (planned: S3 with signed CDN URLs).
- Not built yet: auctions, warehouse-to-warehouse transfers, bin-to-bin moves, cycle counts.

## Documentation

`docs/` holds the business plan, PRD, product scope, SKU and inventory model, architecture, ADRs 001 to 004, epics, an architecture review and the end-to-end implementation plan.
