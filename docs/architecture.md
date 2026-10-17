# LotBazaar: Architecture

## 6. Technical architecture (Stage 4)

### 6.1 Style

A **modular monolith first** (Spring Modulith), split later along proven seams.

At this scale a monolith is not the bottleneck: thousands of buyers and about 25k cartons a month. Auction real-time traffic is the only hot path, and it is isolated as a module that can be extracted later.

### 6.2 Stack

| Layer | Choice |
|---|---|
| Frontend | **Next.js 15 (App Router) + TypeScript**, Tailwind + shadcn/ui. Two apps in a Turborepo: `buyer-web` (SSR for SEO-gated catalog, PWA) and `seller-console` (+ warehouse PWA routes, barcode via ZXing / camera, or keyboard-wedge scanners) |
| Backend | **Java 21 + Spring Boot 3.x**, Spring Modulith, Spring Data JPA + jOOQ for reporting queries, Flyway migrations, MapStruct |
| DB | **PostgreSQL 16**, with RLS for tenancy, partitioning for the ledger and bid tables, and `pg_trgm` / full-text search for catalog search (OpenSearch later if needed) |
| Cache / real-time | **Redis**: auction state, rate limits, session cache. **WebSocket (STOMP) or SSE** for live bids and stock updates |
| Async | **Transactional outbox** table plus a poller that publishes to **RabbitMQ** in v1 (or Kafka when splitting services). Spring Batch for imports |
| Auth | **Keycloak**: OIDC, organisations, MFA for seller admins, OTP login for buyers. Next.js uses NextAuth/Auth.js against Keycloak |
| Files | S3-compatible object storage (AWS S3), CDN, image resizing; video in short clips |
| Integrations | Razorpay or Cashfree (payments, virtual accounts, payouts), GSP for e-invoice and e-way bill, GSTIN verification API, WhatsApp BSP (Gupshup or Meta Cloud API), MSG91 SMS, Shiprocket Cargo / Delhivery |
| Infra | AWS Mumbai (ap-south-1) for data residency. Containers on ECS Fargate or EKS. RDS PostgreSQL Multi-AZ, ElastiCache Redis, Terraform, GitHub Actions |
| Observability | OpenTelemetry, Grafana/Prometheus or Datadog, Sentry for frontends, structured audit log |

### 6.3 Backend modules (bounded contexts)

`tenancy-identity` · `master-data` · `procurement` (containers, landed cost) · `inventory` (buckets, HUs, locations, ledger, reservations) · `warehouse-ops` (sort, putaway, pick, pack, count) · `lots` (recipes, unique lots, manifests) · `commerce` (listings, pricing rules, cart, offers) · `auction` · `orders` · `payments-wallet` · `fulfilment` (dispatch, shipping, claims) · `billing-compliance` (GST invoice, e-way bill, TCS/TDS) · `buyers` (KYC, tiers, credit) · `notifications` · `reporting` · `platform-admin` (plans, SaaS billing).

Rules:
- Modules talk only through public APIs and domain events. Modulith verifies this in tests.
- Each module owns its tables.

### 6.4 Multi-tenancy design

- **Shared DB with a shared schema and `tenant_id` on every tenant table.** **PostgreSQL Row-Level Security** policies are keyed on `current_setting('app.tenant_id')`. The value is set per transaction by a Spring `TransactionSynchronization` or Hibernate `StatementInspector`.
  - This is defence in depth: app filters plus RLS.
  - It allows a dedicated DB per tenant later (enterprise tier) through a tenant→datasource router.
- **Tenant** = the seller company (liquidator).
- **Buyer identity** is platform-global: one login, one KYC. **`buyer_tenant_relationship`** holds per-tenant data: approval status, tier, credit limit, blocks.
  - Buyers can buy from many tenants.
  - **Tenants never see each other's buyers or prices.** In Phase 1 each tenant's catalog is a private storefront (subdomain or custom domain). A shared marketplace is opt-in in Phase 2.
- Tenant config covers: grades, MRP bands, sales modes enabled, tolerance %, claim window, brand visibility rules, invoice series, GSTINs (per warehouse state).

### 6.5 Core data model (key tables)

```
tenant, warehouse, bin_location, brand, category, grade, mrp_band, size_band
container(id, tenant_id, supplier, landed_cost, status, packing_list_file)
sort_bucket(id, tenant_id, sku_code UNIQUE(tenant_id,sku_code), brand_id, gender, category_id, grade_id, mrp_band_id, size_band_id, season)
handling_unit(id, tenant_id, lpn UNIQUE, bucket_id, container_id, warehouse_id, bin_id,
              piece_count, weight_g, total_mrp, unit_cost_alloc, status[AVAILABLE|RESERVED|PICKED|SHIPPED|DAMAGED], version)
inventory_ledger(id, tenant_id, hu_id, bucket_id, warehouse_id, qty_delta, event_type, ref_type, ref_id, at)  -- append-only, partitioned by month
stock_balance(tenant_id, bucket_id, warehouse_id, on_hand, reserved, available)  -- projection, updated in same txn
lot_recipe(id, tenant_id, bucket_criteria jsonb, pieces, size_curve jsonb, tolerance_pct)
lot(id, tenant_id, type[STANDARD|UNIQUE], recipe_id, manifest jsonb, total_mrp, status)
lot_hu(lot_id, hu_id)            -- unique lots at listing, standard lots at allocation
listing(id, tenant_id, lot_id|recipe_id, mode[FIXED|OFFER|AUCTION|LADDER], price_pct_mrp, price_abs, qty_available, visibility)
auction(id, listing_id, start_at, end_at, reserve, min_increment, emd_amount, soft_close_sec, status, version)
bid(id, auction_id, buyer_id, amount, max_proxy, at)  -- partitioned
offer(id, listing_id, buyer_id, amount, status, counter_of)
order, order_line, payment, wallet_txn, invoice, eway_bill, shipment, claim
buyer, buyer_kyc, buyer_tenant_relationship
outbox_event, audit_log
```

### 6.6 Hard technical problems and how we solve them

1. **No overselling across channels.**
   - Every sale reserves stock through `inventory.reserve()` inside one transaction.
   - Unique lots: `SELECT … FOR UPDATE` on the lot row, plus a status transition.
   - Standard lots: decrement `stock_balance.available` with a guarded `UPDATE … WHERE available >= n`.
   - Cart reservations carry a TTL. A scheduled job releases expired holds.
2. **Auction correctness.**
   - All bid placement is serialised per auction through `SELECT … FOR UPDATE` on the auction row. Proxy-bid resolution runs server-side.
   - Soft close extends `end_at`.
   - The server clock is authoritative. Bids are idempotent through a client bid key.
   - Redis pub/sub pushes the top bid to WebSocket subscribers.
   - The closing job is idempotent. Result: winner with a payment-due timer, then EMD forfeit, then an offer to the next bidder.
3. **Inventory audit trail.**
   - The ledger is append-only. Balances are a projection updated in the same transaction.
   - A nightly reconciliation job compares sum(ledger) with the balances and with HU counts.
4. **Offline warehouse ops.**
   - The PWA queues scans in IndexedDB.
   - Each scan carries a client UUID, so the server dedups them.
   - Conflicts are surfaced, never silently merged.
5. **GST compliance.**
   - Invoice series run per GSTIN per financial year.
   - e-invoice IRN is generated before dispatch, plus an e-way bill when value is over ₹50k.
   - As a marketplace operator in multi-tenant mode, the platform must handle **GST TCS (sec. 52)** and **income-tax 194-O TDS**. **Confirm current rates and the operator-vs-SaaS structure with a CA.** If we are pure SaaS and the tenant sells directly, the obligations differ.
6. **Security** (ciso lens):
   - Seller roles: owner, finance, warehouse, sales, viewer.
   - Buyer KYC documents are encrypted (KMS); PII access is logged.
   - Rate limits on bidding and OTP.
   - Signed, watermarked image URLs.
   - Payment webhooks are verified by signature.
   - OWASP ASVS L2 target. Run a `/security-review` before launch.

### 6.7 API design (outline)

- REST/JSON at `/api/v1/...`, OpenAPI 3 generated by springdoc. A typed client is generated for Next.js (openapi-typescript).
- Tenant resolution:
  - buyer storefront: from the host or subdomain
  - seller APIs: from a JWT claim
- Cursor pagination. `Idempotency-Key` on payments, bids and orders. RFC 7807 problem errors.
- Real-time: `/ws`, with topics `auction.{id}` and `listing.{id}.stock`.

### 6.8 Non-functional targets

| Area | Target |
|---|---|
| Catalog latency | p95 under 300 ms |
| Bid placement | p95 under 150 ms server-side |
| Concurrency | 5k concurrent buyers during a live auction |
| Availability | 99.9% |
| Recovery | RPO 5 min, RTO 1 h |
| Data residency | India |
| Load test | k6 on auction close plus a flash Buy Now on a hot standard lot |

## Verification

**Business:**
- The Phase 0 concierge pilot hits its gates:
  - at least 70% of lots sold within 14 days
  - claim rate under 3%
  - at least 40% repeat buyers
- Contracts confirm that online listing is allowed, or we choose the brand-masking path.
- The sorting time-study shows the cost per piece is within budget.

**Technical (once built):**
- Testcontainers integration tests:
  - RLS isolation: a tenant A token can never read tenant B rows. Include a direct-SQL test with the RLS policy.
  - Concurrency: 100 parallel buyers on 1 unique lot gives exactly 1 order. The standard lot `available` never goes negative.
  - Auction: proxy bidding, soft close and the close/forfeit/second-bidder path.
  - Ledger reconciliation equals the balances.
- k6 load test on auction close and a hot lot. Playwright e2e for the full path:
  container → sort → lot → buy → pay → dispatch → invoice.
- GSP sandbox: IRN and e-way bill are generated for a test order.


---

## Review decisions (2026-10-15)

Adopted from `reviews/architecture-review-2026-10-15.md`:

1. **RLS hardening:** app uses a non-owner DB role, `FORCE ROW LEVEL SECURITY` on tenant tables, and transaction-local `set_config('app.tenant_id', ?, true)`. Every table is classified as tenant-, platform- or buyer-scoped.
2. **Standard lots are pre-kitted.** Lot build assigns specific HUs to N lot units, and listing quantity = kitted units. This replaces "buildable from buckets".
3. **Per-size tally per carton at sort.** Lot size curves are derived from the tallies, never promised from size bands alone.
4. **MRP basis:** grade A requires an actual MRP tally. Estimated MRP is flagged on the manifest.
5. **Landed cost allocation** = MRP × grade factor, configurable per tenant.
6. **WebSocket fan-out** goes through the RabbitMQ STOMP broker relay.
7. **One active listing per lot.** All sales modes go through `inventory.reserve()`.
8. **Payment holds:** a hold extends on payment initiation, and a late payment with no stock auto-refunds. Large tickets use a pre-funded wallet or an "awaiting transfer" hold.
9. **GST:** invoices come from the dispatching warehouse's GSTIN and use place-of-supply tax logic. Invoice numbers are gapless, from a row-locked counter table.
10. **Ledger:** carries `hu_delta` and `piece_delta`, with SPLIT, MERGE, REPACK, ADJUST, QUARANTINE and RTO events.
11. **Immutable versioned manifest snapshots,** taken at listing and at order.
12. **Buyer OTP:** a custom Keycloak authenticator (MSG91/WhatsApp).
13. **Pre-printed LPN label rolls** for offline sorting.
14. **Claim settlement** issues a GST credit note plus a refund or wallet credit. There is an RTO inbound flow.
15. **Outbox consumers** are idempotent, with a DLQ per queue. The reporting module reads from a replica.
16. **Auction edge rules:**
    - unsold lots auto-relist
    - ties go to the earliest bid
    - payment defaults cascade to at most 3 bidders
    - EMD is locked per auction
17. **Open decision:** whether unregistered buyers (no GSTIN) may buy. The default is no in Phase 1a.
