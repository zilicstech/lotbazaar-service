# Plan: B2B Branded Stock-Lot Liquidation Platform (working name "LotBazaar")

## Context

A large liquidator (the anchor client) buys whole containers of dead stock from brands such as H&M and Zara: apparel and footwear. They resell it to retailers at wholesale discounts, in **bundles (lots) of 100–1,000+ mixed pieces**, not single items. Today this is mostly offline, through phone, WhatsApp and warehouse visits.

We are building:
1. **A full inventory and warehouse system** for the client, covering many warehouses.
2. **A B2B selling platform** where verified retailers buy lots. India comes first (Phase 1); global buyers follow (Phase 2).
3. **A multi-tenant SaaS**, so other liquidators can be onboarded later.

The core unsolved problem is **what the SKU is** when the sellable unit is a mixed bundle. The client also asked whether bidding is the right sales model.

Inputs locked with the user:
- One full plan in a single document.
- Client is large: 20+ containers a month, 10+ warehouses, thousands of buyers.
- Client accepts light sorting at intake.
- Stack: **Next.js frontend, Java backend, PostgreSQL**.

Skills applied inline:
- idea-to-business: the stage structure.
- market-sizing, competitor-analysis and user-personas: Stage 1.
- lean-canvas, monetization and pricing: Stage 2.
- PRD, pre-mortem and prioritize-features: Stage 3.
- bmad-architecture, database-design, api-design, cto-advisor and ciso-advisor: Stage 4.
- gtm-strategy, ideal-customer-profile and growth-loops: Stage 5.

---

## 1. Validation and market (Stage 1)

**Market evidence** (Apparel Resources industry survey):
- India's off-price apparel market is about **$3B+**.
- Unsold retail inventory is about **$4B**. It reaches surplus firms at a 70–80% markdown, giving a stock-lot market of about **$1.2B**.
- About 3% of all apparel ends up with surplus firms.

**Market sizing:**
- **TAM:** India apparel and footwear stock-lot market, about $3B a year.
- **SAM:** branded retail surplus (international and premium brands) sold B2B in lots, roughly $0.8–1.2B.
- **SOM:**
  - Years 1–2: the anchor client's GMV. Assume ₹150–400 Cr a year at 20+ containers a month. **Validate this with the client's books.**
  - Year 3+: add 3–5 tenants and grow toward 1–2% of SAM.

**Competitors:**

| Player | Model | Gap we exploit |
|---|---|---|
| Offline mandis and traders (Gandhi Nagar Delhi, Ulhasnagar, Tiruppur, Surat), WhatsApp brokers | Phone, visits, cash | **This is the real incumbent.** Weaknesses: no manifests, no trust, buyers must travel |
| Excess2Sell, Bulqbox, e-Age World (India) | Marketplace of surplus lots from many sellers | Generic and mixed-category. Weak on grade and piece guarantees. Not a WMS for the seller |
| B-Stock (US/global) | Auctions, retailer-branded storefronts | Shows that auctions work for **truckload lots with sophisticated buyers**, not small retailers |
| Udaan, generic B2B | Fixed-price wholesale | No liquidation workflow, no lot or grade model |

**Positioning:** *"Verified branded stock lots, with an honest manifest, delivered to your shop."* We win on trust: photo or video per carton, a grade guarantee, and a piece-count tolerance backed by a claims SLA. Price alone is not our edge.

**Buyer personas:**
1. **Tier-2/3 multi-brand store owner.** Buys 1–5 lots a month. Price-sensitive and low on trust. Mobile and WhatsApp first. Wants "x% of MRP" pricing and specific size curves.
2. **Off-price or discount chain buyer.** Buys 20–100 lots a month. Wants consistency, credit terms and GST invoices.
3. **Online reseller** (Meesho, Instagram or Flipkart sellers). Wants smaller lots and photos.
4. **Exporter or international wholesaler** (Phase 2). Buys by container. Price discovery matters to them.

**Seller-side personas:** client owner/finance, warehouse supervisor, sorter, lot/catalog manager, and sales key-account manager (KAM).

**Riskiest assumptions, ranked. Test these before heavy build:**
1. **Brand contracts allow online listing with brand names.** Many dead-stock deals forbid public brand display, require de-labelling, or restrict channel or geography.
   - *Test:* read the client's purchase contracts in week 1.
   - This drives the design toward a **closed, KYC-gated catalog**.
2. **Retailers will buy lots unseen, from manifest, photos and video.**
   - *Test:* a concierge pilot. Sell 30 lots over WhatsApp using generated manifests and track disputes.
3. **Light sorting is fast enough at 20+ containers a month.**
   - *Test:* time-study one container intake.
4. **Auctions get enough bidders.** Each auction needs 3 or more bidders to beat fixed price.
   - *Test:* run 10 pilot auctions.

**Discovery tasks (weeks 1–4):**
- 10–15 retailer interviews.
- Shadow one container from receipt to dispatch.
- Contract review.
- Export the client's last 12 months of sales: lot sizes, price as a % of MRP, days to sell.

---

## 2. Sales model: is bidding a good idea?

**Recommendation: not bidding alone. Use a hybrid, with fixed price as the default and auctions as a targeted tool.**

Why pure bidding is wrong for this buyer base:
- Indian small retailers want **price certainty**. Auctions slow cash: the auction window plus the payment window plus a fallback to the second bidder.
- Auctions need **liquidity**. With fewer than 3 active bidders per lot, prices fall below what fixed price would get.
- **Repeatable standard lots** (same spec, many units) do not need price discovery.
- Auctions invite **collusion and shill risk** among a small, connected trader community.

**Model mix:**

| Mode | Use for | Mechanics |
|---|---|---|
| **Buy Now (fixed price)**, default | Standard sorted lots, repeat buyers | Price is a % of total MRP. Volume tiers (1, 5 and 10+ lots). First paid wins, with a 15-minute cart reservation |
| **Make an Offer / RFQ** | Large buyers, multi-lot or whole-bucket deals | Buyer offers a % of MRP. Seller accepts, counters or rejects. KAM-assisted |
| **Timed auction** | Unique, premium or hard-to-price lots; whole containers; Phase 2 exporters | Reserve price. Proxy bidding. **Soft close** (2-minute extension). Earnest-money deposit (EMD) gate |
| **Markdown ladder** (automatic declining price) | Aged stock | The % of MRP drops by a set step every N days until sold or floored. Gives price discovery without auction overhead |
| **Pre-booking** | Containers still in transit | Sell on the packing-list manifest before arrival, with a deposit. Improves the container-to-cash cycle |

The data will set the mix: a/b the auction share by lot type and measure recovery as a % of MRP.

---

## 3. The SKU problem: four-level inventory model

The key insight is to **separate what is tracked (stock) from what is sold (lot)**. Pieces are never scanned individually. Light sorting creates homogeneous buckets.

```
Container / Inbound Shipment   (purchase unit; carries cost)
   └── Sort Bucket  = the INVENTORY SKU (homogeneous attribute set)
         └── Handling Unit (HU) = physical carton/bale with an LPN barcode, N pieces of ONE bucket
               └── (pieces: counted, not serialised)

Lot  = the SELLABLE SKU = a set of HUs (unique lot) OR a recipe filled from HUs (standard lot)
```

### 3.1 Sort Bucket (inventory SKU)

The SKU code is generated from attributes:
`{Brand|MULTI}-{Gender}-{Category}-{SubCat}-{Grade}-{MRPBand}-{SizeBand}`

Example: `ZARA-W-TOP-TSHIRT-A-1500_2499-SML`

| Attribute | Values (master data, per tenant) |
|---|---|
| Brand | H&M, Zara and others, or MULTI for mixed premium |
| Gender | M, W, K (kids), U (unisex) |
| Category and sub-category | Tops/T-shirt, Bottoms/Jeans, Footwear/Sneaker… |
| **Grade** | A = new with tags; B = new without tags or minor defect; C = defect or damaged; F = footwear pair-mismatch |
| **MRP band** | <500, 500–999, 1000–1499, 1500–2499, 2500+ (for INR; per currency in Phase 2) |
| Size band | S-M-L, L-XL-XXL, Kids 2–8, Shoes UK6–9… |
| Season (optional) | SS24, AW24 |

Sorters assign these attributes at a sorting table. Grade and MRP band are read from the tag. **Sorters do not record piece-level data.**

### 3.2 Handling Unit (HU / carton)

- Each carton gets an **LPN barcode** at sorting and holds one bucket only.
- Recorded per carton:
  - piece count (counted at sorting, or packed to a standard count of 50 or 100)
  - **weight**, for cheap re-verification: average grams per piece per category, with a variance flag at more than 5%
  - total MRP, if the tag MRP is summed by a scanner reading MRP tags, or by MRP band × count as an estimate
  - photo
- HU is the unit of **location, reservation and picking**. This makes inventory tractable: about 1,000 cartons per container, not 50,000 pieces.

### 3.3 Lot (sellable SKU)

There are two lot types.

1. **Standard lot (recipe-based catalog SKU).**
   - A spec such as: "Zara Women Tops Grade A, 200 pcs, MRP 1500–2499, size curve S20/M40/L30/XL10, ±3%."
   - Listed once, with **available quantity = how many lots can be built from matching buckets**.
   - The actual HUs are allocated at pick time.
   - This is the scalable model: it behaves like a normal SKU with stock.
2. **Unique lot (specific HUs).**
   - The lot manager picks specific cartons. The lot is one-off, often mixed brand or category.
   - Used for auctions and whole-container sales.
   - Its HUs are reserved at listing.

**Lot manifest** (derived automatically, never typed):
- piece count
- brand mix %, category mix %, size curve
- total MRP and average MRP
- grade
- photos and video
- weight

Price shows as **a % of total MRP and an absolute ₹ amount**, the language buyers already use.

### 3.4 Accuracy and dispute control

- **Piece-count tolerance** (e.g. ±2%) is written into the terms.
- Claims are allowed within 72 hours of delivery, with an unboxing video required.
- Weight is checked at sort and at dispatch, giving two checkpoints.
- Cycle counts run per HU, with a randomized audit of 1 in 50 cartons.
- Dispute rate per sorter and per bucket feeds a quality dashboard.

### 3.5 Inventory flow

```
PO/Container (cost, packing list) → Inbound ASN → GRN at dock (container-level count/weight)
 → Sorting session (create HUs, assign bucket, count, weigh, label LPN) → Putaway (bin)
 → Lot build (recipe or unique) → Listing → Reserve (cart / auction / offer)
 → Order paid → Allocate HUs → Pick (scan LPN) → Pack/Verify weight → Dispatch (invoice + e-way bill)
 → Delivered → Claim window → Closed
Transfers between warehouses = HU moves with in-transit state.
```

**Costing:**
- Container landed cost (purchase + freight + duty + handling) is allocated to buckets by MRP share.
- This gives margin per lot. The client cares most about **recovery % per container**.

---

## 4. Business model (Stage 2)

**Lean canvas (abridged):**
- **Problem:**
  - opaque, offline lot selling
  - slow container-to-cash cycle
  - no inventory visibility across warehouses
  - buyer trust deficit
- **Solution:** sort-bucket WMS, verified lot manifests, a hybrid sales engine, and integrated payments, GST and logistics.
- **Unfair advantage:** the anchor client's supply, giving branded inventory from day 1. Without supply, a liquidation marketplace dies.
- **Key metrics:** see the North Star below.

**Revenue streams**, as tech provider and later marketplace operator:
1. **SaaS per tenant:**
   - setup and onboarding fee
   - monthly subscription by warehouses and users, e.g. ₹1–3L a month for a large tenant
2. **Transaction fee:** 1–3% of GMV on platform sales. For the anchor client, negotiate a lower take rate in exchange for equity or a revenue share.
3. **Value-added services, later:**
   - logistics margin through aggregator freight
   - buyer financing (BNPL partner commission)
   - inspection or "verified grade" certification
   - promoted listings

**Anchor-client deal: decide this before building.** Options:
- (a) Fixed-fee custom build plus a SaaS licence, with the IP retained by us. **This is the recommendation.**
- (b) Revenue share.

The contract must state that **the platform IP and the multi-tenant rights are ours**. It must also commit that the client's buyer data is never exposed to other tenants.

**North Star Metric:** *weekly paid GMV from verified buyers.*

| Input metric | Target / note |
|---|---|
| Active verified buyers | Count |
| Lot conversion rate (views to paid) | Track by sales mode |
| **Container-to-cash days** | Target under 45 |
| Recovery % of MRP | Track per container and bucket |
| Repeat-buyer rate | Track monthly |
| Claim/dispute rate | Guardrail, under 3% |

**Unit economics to model with the CFO lens:**
- **Cost:** cost per sorted piece, covering labour minutes × wage.
- **Margin:** platform cost per order and gross margin per lot.
- **Cash:** working-capital days.

---

## 5. Product scope (Stage 3)

**Surfaces:**
1. **Seller console (web)**, per tenant:
   - masters, containers, sorting, inventory, lot builder, listings, pricing rules, auctions, offers, orders, dispatch, buyers, credit, reports
2. **Warehouse app (PWA)**, for handhelds or phone camera:
   - GRN, sort session, LPN print, putaway, pick, pack, cycle count
   - offline-tolerant
3. **Buyer portal (web + PWA)**:
   - KYC onboarding (GSTIN + PAN + shop photo)
   - browse lots, Buy Now, offers, auctions, wallet/EMD, orders, claims, invoices
   - WhatsApp alerts
4. **Platform admin**: tenant onboarding, plans, billing, compliance, support.

**MVP (Phase 1a, anchor client, India). Must-haves:**
- Tenant, users and RBAC. Masters (brands, categories, grades, MRP and size bands, warehouses and bins).
- Container receipt, sorting sessions, HUs with LPN labels, putaway, transfers, cycle count.
- **Inventory ledger** with real-time stock by bucket, HU and warehouse.
- Lot builder (standard + unique) with an auto-manifest, photos and video.
- Buyer KYC, with GSTIN verified via a GST API and manual approval. Buyer tiers per tenant.
- **Buy Now + Make an Offer + timed auction** (with EMD).
- Payments:
  - **Razorpay or Cashfree:** UPI, netbanking, virtual accounts / NEFT-RTGS for large tickets
  - a wallet for EMD
- Order, allocation, pick/pack, dispatch.
- **GST e-invoice (IRN) + e-way bill** via a GSP API.
- Claims within 72 hours.
- WhatsApp Business API notifications: new lots matching saved filters, outbid, payment due.
- Reports: stock aging, sell-through, recovery %, GMV, buyer activity.

**Phase 1b:**
- markdown ladder
- pre-booking for in-transit containers
- buyer credit limits and BNPL partner
- logistics aggregator integration (Shiprocket Cargo / Delhivery B2B freight quotes at checkout)
- saved searches and recommendations
- second tenant onboarding

**Phase 2 (global):**
- multi-currency pricing and FX
- international buyer KYC
- Incoterms (EXW/FOB/CIF)
- export documentation: commercial invoice, packing list, HS codes, IEC; certificate of origin via a CHA partner
- LC or wire payments; Stripe or Payoneer for card or international payments
- multi-language
- an opt-in shared cross-tenant marketplace

**Out of scope for v1:** a native mobile app (the PWA covers it), piece-level serialisation, B2C sales, own logistics fleet, ML pricing.

**Pre-mortem: top failure modes and mitigations:**

| Failure | Mitigation |
|---|---|
| Brand contract violated by public listing; client loses supply | Closed catalog visible only after KYC. Per-brand visibility rules (hide brand name, show "Premium EU high-street"). Watermarked images. Legal review |
| Manifest inaccuracy leads to disputes and lost trust | Weight checks, tolerance terms, unboxing-video claims, sorter QA metrics |
| Buyers move offline after the first deal | Value they cannot get offline: credit, freight, guaranteed manifests, invoices, priority access to new containers |
| Auctions are thin and prices low | Auctions only for chosen lots. Reserve prices. Markdown ladder as the default for aged stock |
| Payment default after winning an auction | EMD forfeiture, offer to the second bidder, buyer reliability score |
| Warehouse staff don't adopt the app | Scan-first PWA, Hindi/regional language UI, minimal typing, label printers at sort tables |
| Multi-tenant data leak between competing liquidators | PostgreSQL RLS, tenant-scoped tests, separate buyer relationship per tenant |

---

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

---

## 7. Go-to-market and growth (Stage 5)

**ICP:**
- **Beachhead:** off-price and multi-brand retailers in Tier-2/3 North and West India who already buy stock lots offline, starting with the client's existing buyer list.
- **Phase 2:** Middle East and Africa importers, who already buy Indian stock lots.

**Launch:**
- Onboard the client's existing buyers first, using a KAM-assisted WhatsApp invite with a KYC link.
- Hold a "new container drop" each week as a recurring event that drives return visits.

**Growth loops:**
1. **Supply loop:** more containers → more drops → more buyer visits → faster sell-through → client buys more containers.
2. **Trust loop:** honest manifests → low claims → buyer reviews and reliability → more unseen purchases.
3. **Referral:** retailer-refers-retailer credit on the first order.
4. **Tenant loop (B2B SaaS):** the anchor client's success becomes a case study, aimed at other liquidators.

**Channels:**
- WhatsApp broadcasts with saved-search alerts
- KAM outreach
- trade presence in wholesale markets
- YouTube/Instagram unboxing of lots as social proof
- the client's dealer network

---

## 8. Roadmap and team

| Phase | Duration | Output |
|---|---|---|
| 0. Discovery and contracts | 4 weeks | Contract review, 15 interviews, container time-study, data export, SKU taxonomy workshop with the client, **concierge pilot** (30 lots on WhatsApp with generated manifests) |
| 1a. MVP | ~5–6 months | Section 5 MVP, launched with the anchor client in 1–2 warehouses, then rolled to all |
| 1b. Scale India | +3–4 months | Credit, logistics, markdown ladder, pre-booking, 2nd tenant |
| 2. Global | +4–6 months | Export, FX, international KYC, opt-in marketplace |

**Team for 1a:**
- 1 product manager/BA
- 1 UX designer
- 3 Java backend engineers
- 2 Next.js engineers
- 1 QA/automation engineer
- 0.5 DevOps engineer
- a part-time CA/legal advisor

**Suggested first epics:**
1. Tenancy & auth
2. Master data & SKU taxonomy
3. Container intake & sorting (PWA)
4. Inventory ledger & HU
5. Lot builder & manifest
6. Buyer KYC & storefront
7. Buy Now & orders
8. Payments & wallet
9. GST invoicing & dispatch
10. Offers
11. Auctions
12. Notifications
13. Reports

---

## 9. What happens after plan approval

1. Create the project folder `~/Projects/lotbazaar/docs/` and split this plan into:
   - `business-plan.md`
   - `prd.md` (via the BMAD PRD workflow)
   - `architecture.md` (via BMAD architecture)
   - `adr/` (ADR-001 modular monolith, ADR-002 RLS multi-tenancy, ADR-003 four-level SKU model, ADR-004 hybrid sales modes)
2. Run `bmad-review` (adversarial principal-engineer critique) on the architecture doc, plus `pm-execution:strategy-red-team` on the business plan. Fold in the fixes.
3. `bmad-create-epics-and-stories`, then `bmad-sprint-planning`.
4. Only then scaffold the repos:
   - Next.js Turborepo
   - Spring Boot Modulith
   - docker-compose with Postgres, Redis, RabbitMQ and Keycloak

   No code is written before step 3 is approved.

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

### Sources
- [Apparel Resources: surpassing surplus business (market sizes)](https://apparelresources.com/business-news/retail/surpassing-surplus-business/)
- [Excess2Sell vs competitors (CB Insights)](https://www.cbinsights.com/compare/excess2sell-vs-surpluss)
- [B-Stock auction vs fixed-price liquidation analysis](https://closo.co/blogs/inventory-liquidation/bstock-wholesale)
- [Legit pallet liquidation sites 2026 comparison](https://closo.co/blogs/inventory-liquidation/legit-pallet-liquidation-sites)
