# End-to-End Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the demo into a complete, followable business workflow: container arrives at the dock, then it is sorted, shelved, lotted with photos, listed, and sold by Buy Now or by an offer. The buyer pays, then the warehouse picks, packs, invoices and dispatches. The order is then delivered, any claim is settled, and the order is closed. Each step is done by the right staff role, and a guided tour explains every screen.

**Architecture:** This extends the existing Spring Modulith backend:
- new Flyway migration V4
- new state columns and tables
- each tenant table copies the RLS loop in V3

The Next.js apps get new pages that follow the existing `Shell` and `ui.tsx` patterns. Payment and the GST portal are **mocked behind the same flow a real integration would use**: payment intent, then the gateway page, then a callback. E-invoice IRN and e-way bill numbers are generated locally. Swapping in Razorpay or a GSP later changes one class each.

**Tech Stack:** Spring Boot 4.1.1 / Java 17, JdbcClient, Flyway, PostgreSQL 16 RLS, Next.js 16 + Tailwind v4. One new npm dependency: `jsbarcode` (Code128 labels).

**Spec:** `docs/sku-inventory-model.md` §3.5 (inventory flow), `docs/prd.md` FR1–30, and this plan's "Workflow" section.

## Global Constraints

- Every new tenant table: `tenant_id uuid NOT NULL`, plus ENABLE + FORCE RLS with the `tenant_isolation` policy (copy the V3 DO-loop).
- Every state change is a guarded update (`... where id = ? and status = '<expected>'`). A count other than 1 means 409.
- Every carton movement writes an `inventory_ledger` row, and ledger drift must stay 0.
- Money is `numeric(14,2)`, shown as INR via `inr()`.
- Mocks carry `// shortcut: <limit>, <when to upgrade>` comments.
- Backend tests use Testcontainers. Run them with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Workflow (what the user will see, in order)

| # | Step | Who (role) | Screen | Status change |
|---|---|---|---|---|
| 1 | Create container with packing list (expected pieces, gross kg, landed cost) | OWNER | Seller › Containers | container `ARRIVED` |
| 2 | Dock check: record actual bales and gross kg. Variance vs packing list is shown | WAREHOUSE | Warehouse › Receiving | `ARRIVED → SORTING` |
| 3 | Sort into cartons: attributes, piece count, size tally, weight. **Print barcode label** | WAREHOUSE | Warehouse › Sorting station | carton `SORTED` (no bin yet) |
| 4 | Finish sorting: summary of pieces sorted vs expected | WAREHOUSE | Sorting station | `SORTING → SORTED` |
| 5 | Putaway: scan or type the carton LPN, then the bin code | WAREHOUSE | Warehouse › Putaway | carton `AVAILABLE`, `bin` set |
| 6 | Build lot from cartons, upload photos, write description | SALES | Seller › Lots | lot `DRAFT` |
| 7 | List: Buy Now price as % of MRP, optionally "accept offers" with a floor % | SALES | Lot detail | `DRAFT → LISTED` |
| 8 | Buyer registers (GSTIN, shop, city). Seller approves | BUYER, then SALES | Buyer › Register; Seller › Buyers | relationship `APPROVED` |
| 9a | Buyer: **Buy Now**. Lot held for 15 minutes; checkout shows GST breakup | BUYER | Lot page, then Checkout | lot `LISTED → RESERVED`; order `PAYMENT_PENDING` |
| 9b | Or buyer: **Make offer**. Seller accepts, counters or rejects. Buyer accepts the counter; checkout opens at the agreed price | BUYER, SALES | Lot page; Seller › Offers; Buyer › My offers | offer `OPEN → ACCEPTED`; lot `RESERVED` |
| 10 | Pay on mock gateway page (UPI / NetBanking). Success or failure is chosen by the user | BUYER | Pay page | order `PAID` (failure keeps `PAYMENT_PENDING`; the hold expiring releases the lot) |
| 11 | Pick: pick list shows cartons with bins; scan each LPN; a wrong carton is rejected | WAREHOUSE | Warehouse › Pick & pack | order `PAID → PICKING → PACKED` |
| 12 | Pack check: dispatch weight vs carton weights (±5%) | WAREHOUSE | Pick & pack | warning if outside tolerance |
| 13 | Dispatch: transporter, vehicle no. Generates **GST tax invoice** (HSN, CGST+SGST or IGST) + e-way bill no. | WAREHOUSE | Warehouse › Dispatch | `PACKED → DISPATCHED`, cartons `SOLD` |
| 14 | Buyer confirms delivery; can view and print the invoice | BUYER | Buyer › Orders | `DISPATCHED → DELIVERED` |
| 15 | Within 72 hours: raise claim (short pieces / wrong grade / damage, qty, note). Otherwise "No issues" | BUYER | Buyer › Order | claim `OPEN`, or order `CLOSED` |
| 16 | Seller resolves claim: credit amount or reject with reason | OWNER | Seller › Claims | claim `RESOLVED`, order `CLOSED` |
| 17 | Owner reports: recovery % per container (sales ÷ landed cost), stock aging, sales funnel, claims rate | OWNER | Seller › Dashboard / Reports | — |

**Staff roles** (seller users): `OWNER` (everything), `WAREHOUSE` (Receiving, Sorting, Putaway, Pick & pack, Dispatch; cannot see prices or buyers), `SALES` (Lots, Offers, Buyers, Orders read-only). The menu and the API both enforce them. Demo users: `owner@rk.demo`, `warehouse@rk.demo`, `sales@rk.demo` (same demo password, in README).

**Guided tour:** an in-app "How it works" page showing the 17 steps above, each linking to its screen and the role to log in as. Every page gets a one-line "What is this screen?" banner.

**Out of this round:** auctions, inter-warehouse transfers, real Razorpay/GSP calls, WhatsApp, Hindi UI, offline PWA. These are listed in README as next.

## Review Focus

1. A Buy Now hold expires while the buyer is on the pay page. Expect payment to be refused with "hold expired", and the lot to be back on sale. *(Test in Task 5.)*
2. Two buyers make offers and the seller accepts both. Expect only the first acceptance to succeed; the second gets "lot no longer available". *(Test in Task 6.)*
3. A carton is scanned at pick that belongs to a different lot or order. Expect it to be rejected; the pick count is unchanged. *(Test in Task 7.)*
4. The WAREHOUSE user calls `/api/seller/lots` or `/buyers`. Expect 403, and prices hidden in the warehouse UI. *(Test in Task 1.)*
5. A cross-state buyer (seller in Delhi, buyer in Maharashtra) gets IGST, while a same-state buyer gets CGST+SGST. *(Test in Task 8.)*

---

### Task 1: Staff roles (OWNER / WAREHOUSE / SALES)

**Files:**
- Modify: `backend/src/main/java/com/lotbazaar/SecurityConfig.java`: path-level role rules
- Modify: `backend/src/main/java/com/lotbazaar/demo/DemoDataSeeder.java`: add warehouse and sales users
- Modify: `apps/seller-console/components/Shell.tsx`: filter menu by `session.user.role`
- Test: `backend/src/test/java/com/lotbazaar/identity/RoleAccessTests.java`

**Interfaces:** Produces the roles `OWNER`, `WAREHOUSE`, `SALES` in the JWT `roles` claim (already contains `u.role()`). API prefixes:
- `/api/seller/wh/**`: WAREHOUSE, OWNER
- `/api/seller/sales/**`: SALES, OWNER
- `/api/seller/owner/**`: OWNER
- existing `/api/seller/**`: any SELLER

- [ ] Write a failing test. The WAREHOUSE token gets 403 on `GET /api/seller/sales/offers`, OWNER gets 200, and SALES gets 403 on `POST /api/seller/wh/putaway`.
- [ ] Add matchers before the generic `/api/seller/**` rule: `.requestMatchers("/api/seller/wh/**").hasAnyRole("WAREHOUSE","OWNER")`, and the same pattern for sales and owner.
- [ ] Move the existing price- and buyer-bearing endpoints (lots, buyers, orders money view, dashboard) under `/sales` or `/owner`, and update the frontend `api()` paths.
- [ ] Shell menu: each item gets a `roles: string[]`, and the menu renders only the items that include the session role.
- [ ] Run the tests (green). Commit.

### Task 2: Container dock check + sorting lifecycle

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__end_to_end.sql` (this task adds the container part; later tasks append to the same file before first run)
- Modify: `InventoryService.java`, `InventoryController.java`
- Modify: `apps/seller-console/app/containers/page.tsx`, `containers/[id]/page.tsx`
- Test: `backend/src/test/java/com/lotbazaar/inventory/ContainerLifecycleTests.java`

Schema:
```sql
ALTER TABLE container DROP CONSTRAINT container_status_check;
ALTER TABLE container ADD CONSTRAINT container_status_check CHECK (status IN ('ARRIVED','SORTING','SORTED'));
UPDATE container SET status = 'SORTED' WHERE status = 'CLOSED';
ALTER TABLE container ALTER COLUMN status SET DEFAULT 'ARRIVED';
ALTER TABLE container ADD COLUMN expected_pieces int, ADD COLUMN expected_kg numeric(10,2),
  ADD COLUMN actual_bales int, ADD COLUMN actual_kg numeric(10,2), ADD COLUMN checked_at timestamptz,
  ADD COLUMN sorted_at timestamptz;
```
API:
- `POST /api/seller/owner/containers` gets body `{ref, supplier, warehouseId, landedCost, expectedPieces, expectedKg}`.
- `POST /api/seller/wh/containers/{id}/dock-check` gets body `{actualBales, actualKg}`. Guarded `ARRIVED→SORTING`.
- `POST /api/seller/wh/containers/{id}/finish` is guarded `SORTING→SORTED`.
- `addCarton` requires `SORTING`.

The UI shows variance: `actualKg − expectedKg` and % in red when over 3%. After finishing, it shows pieces sorted vs expected.

- [ ] Write a failing test: adding a carton to an `ARRIVED` container gives 409; after the dock-check it works; finishing then blocks further cartons.
- [ ] Implement the migration and service methods. Run (green). Commit.

### Task 3: Barcode labels + putaway to bins

**Files:**
- V4: `bin_location(id, tenant_id, warehouse_id, code, UNIQUE(tenant_id, warehouse_id, code))` + RLS. Cartons: `ALTER TABLE handling_unit ADD COLUMN bin_id uuid REFERENCES bin_location(id)`. The status check gains `'SORTED'`, and new cartons default to `SORTED`.
- Seed: bins `A-01-01 … C-04-03` per warehouse.
- Create: `apps/seller-console/app/wh/putaway/page.tsx` and `apps/seller-console/app/labels/[containerId]/page.tsx` (print view, `jsbarcode`, 100×50 mm labels: LPN barcode, SKU code, pcs, grade)
- Modify: `InventoryService.putaway(String lpn, String binCode)`. Guarded `SORTED→AVAILABLE`. Writes a ledger row `PUTAWAY` with `hu_delta 0, piece_delta 0`.
- Lot builder lists only `AVAILABLE` cartons, so unshelved cartons can't be sold.
- Test: `PutawayTests`. An unknown bin gives 404, and putaway twice gives 409. A carton must be shelved before it can go into a lot; otherwise 409.

- [ ] Write the failing tests. Implement. Putaway page: two inputs (LPN autofocus, then bin) work with a keyboard-wedge scanner (Enter submits). It shows the last 10 putaways. Run (green). Commit.

### Task 4: Lot photos, description, offer settings

**Files:**
- V4: `lot_photo(id, tenant_id, lot_id, content_type, bytes bytea, created_at)` + RLS.
  - `ALTER TABLE lot ADD COLUMN description text, ADD COLUMN accept_offers boolean NOT NULL DEFAULT false, ADD COLUMN offer_floor_pct numeric(5,2)`.
  - The lot status check becomes `('DRAFT','LISTED','RESERVED','SOLD')`.
- `LotController`:
  - `POST /api/seller/sales/lots/{id}/photos` (multipart, max 5 MB, image/jpeg|png|webp, max 8 per lot)
  - `GET /api/public/lot-photos/{photoId}`, served with tenant check through the store path; buyer pages use `/api/store/{code}/photos/{id}`
- Shortcut: photos stored in Postgres bytea. Move to S3 before pilot.
- UI: photo grid on seller lot detail plus a buyer gallery.
- Test: `LotPhotoTests`. Rejected cases: wrong type, oversize, a 9th photo, and another tenant's photo id (404).

- [ ] Tests, implementation, commit.

### Task 5: Holds, checkout with GST, mock payment

**Files:**
- V4:
  - `sales_order` status check becomes `('PAYMENT_PENDING','PAID','PICKING','PACKED','DISPATCHED','DELIVERED','CLOSED','CANCELLED')`.
  - New columns: `hold_expires_at timestamptz, taxable numeric(14,2), gst_rate numeric(4,2), cgst, sgst, igst numeric(14,2), total numeric(14,2), source varchar(10) CHECK (source IN ('BUY_NOW','OFFER'))`.
  - Drop `UNIQUE(lot_id)`; replace it with a partial unique index `WHERE status <> 'CANCELLED'`.
  - `payment(id, tenant_id, order_id, method, status CHECK IN ('SUCCESS','FAILED'), gateway_ref, amount, at)` + RLS.
  - `tenant` gets `state_code char(2), gstin varchar(15)`. The buyer state comes from GSTIN digits 1–2.
  - Tenant config gets `gst_rate numeric(4,2) DEFAULT 5`. Shortcut: one flat rate; per-HSN rates need a CA.
- `OrderService`:
  - `checkout(buyerId, lotId)`: guarded `LISTED→RESERVED`, then insert `PAYMENT_PENDING` with `hold_expires_at = now()+15 min`, then compute GST. Price is tax-inclusive or exclusive? **Exclusive:** buyer pays price + GST.
  - `pay(buyerId, orderId, method, success)`: if expired, 410 "hold expired". On success, `PAYMENT_PENDING→PAID` and a payment row. On failure, a payment row FAILED and the order stays pending.
  - `@Scheduled(fixedDelay=60s) releaseExpiredHolds()`: per tenant via `TenantDirectory.as`, cancels expired pending orders and sets lot `RESERVED→LISTED`.
- Buyer UI:
  - `store/[code]/checkout/[orderId]` shows the GST breakup and a countdown.
  - `pay/[orderId]` is the mock gateway page with UPI or NetBanking, and buttons "Pay successfully" / "Simulate failure".
- Update `BuyNowConcurrencyTests` to use `checkout`; still exactly 1 winner.
- Test: `CheckoutTests`.
  - Expired hold gives 410 and the lot is LISTED again after the release job.
  - Failure, then a retry with success, works.
  - Same-state buyer gets CGST = SGST = 2.5%; other-state buyer gets IGST 5%.

- [ ] Tests, implementation, commit.

### Task 6: Make an offer / counter / accept

**Files:**
- V4: `offer(id, tenant_id, lot_id, buyer_user_id, amount numeric(14,2), counter_amount numeric(14,2), status CHECK IN ('OPEN','COUNTERED','ACCEPTED','REJECTED','EXPIRED'), note, created_at, updated_at)` + RLS.
- Create: `backend/src/main/java/com/lotbazaar/commerce/OfferService.java`.
  - `make(buyerId, lotId, amount)`: lot must be LISTED with `accept_offers`. Amount ≥ floor, otherwise 400 with the floor in the message. One OPEN offer per buyer per lot.
  - `accept(offerId)`: guarded lot `LISTED→RESERVED`, creates a `PAYMENT_PENDING` order with source OFFER, a 24-hour hold, and the offer amount. Other open offers on the lot become `EXPIRED`.
  - `counter(offerId, amount)` and `reject(offerId)`.
  - `acceptCounter(buyerId, offerId)` behaves like `accept` at the counter amount.
- Seller UI: `app/offers/page.tsx` lists offers with Accept / Counter / Reject.
- Buyer UI: a "Make offer" form on the lot page and `store/[code]/offers` (My offers).
- Test: `OfferTests`.
  - Below floor gives 400.
  - Accepting two offers on one lot: the second gets 409.
  - Counter → buyer accepts → order created at the counter amount.

- [ ] Tests, implementation, commit.

### Task 7: Pick & pack with scan verification

**Files:**
- V4: `order_pick(order_id, hu_id, picked_at, PRIMARY KEY(order_id, hu_id), tenant_id)` + RLS. `sales_order` gets `dispatch_kg numeric(10,2)`.
- `FulfilmentService` (new, in `commerce`):
  - `pickList(orderId)`: the lot's cartons with bin code and picked flag.
  - `startPicking(orderId)`: `PAID→PICKING`.
  - `scan(orderId, lpn)`: the carton must belong to the order's lot, otherwise 409 "carton LPN-x is not part of this order". Inserts into `order_pick` (idempotent on repeat).
  - `pack(orderId, kg)`: all cartons must be picked, otherwise 409 listing the missing ones. Compares kg against the sum of `weight_g`; outside ±5% returns `warning` but allows it with `confirm=true`. Then `PICKING→PACKED`.
- UI: `app/wh/fulfil/page.tsx` (queue of PAID/PICKING/PACKED orders) and `app/wh/fulfil/[orderId]/page.tsx` (scan box, checklist turning green, weight input).
- Test: `FulfilmentTests`. A wrong LPN is rejected; packing with a missing carton gives 409; a weight warning appears at 10% off.

- [ ] Tests, implementation, commit.

### Task 8: Dispatch, GST tax invoice, e-way bill

**Files:**
- V4: `invoice(id, tenant_id, order_id UNIQUE, invoice_no, fy varchar(7), irn varchar(64), eway_bill_no varchar(12), transporter, vehicle_no, issued_at)` + RLS, and `invoice_seq(tenant_id, fy, next int)` for gapless numbering. Number format `RK/2026-27/000001`.
- `FulfilmentService.dispatch(orderId, transporter, vehicleNo)`:
  - `PACKED→DISPATCHED`
  - allocates the invoice number with `update invoice_seq ... returning`
  - IRN = sha256(gstin+no+fy) (shortcut: mock IRN; call GSP sandbox before pilot)
  - e-way bill no. (12 digits) when total > 50,000
  - `inventory.sellLot` moves to here: cartons become SOLD and the ledger records SALE
- Invoice view: `GET /api/seller/wh/orders/{id}/invoice` and `/api/store/{code}/orders/{id}/invoice`. Printable page `invoice/[orderId]` in both apps showing:
  - seller and buyer GSTIN
  - HSN (6109 knitted tops, 6203/6204 woven, 6403/6404 footwear; mapped on `category` via V4 `ALTER TABLE category ADD COLUMN hsn varchar(8)`)
  - taxable value, CGST/SGST or IGST, total in words
- Test: `DispatchTests`. Invoice numbers are sequential with no gaps across 3 orders; IGST vs CGST is correct; the ledger is SOLD and drift is 0.

- [ ] Tests, implementation, commit.

### Task 9: Delivery, claims, close

**Files:**
- V4:
  - `claim(id, tenant_id, order_id, type CHECK IN ('SHORT','WRONG_GRADE','DAMAGED','OTHER'), pieces int, note, status CHECK IN ('OPEN','RESOLVED','REJECTED'), credit_amount numeric(14,2), resolution_note, created_at, resolved_at)` + RLS.
  - `sales_order` gets `delivered_at`.
- `ClaimService`:
  - `confirmDelivery(buyerId, orderId)`: `DISPATCHED→DELIVERED`.
  - `noIssues(buyerId, orderId)`: `DELIVERED→CLOSED`.
  - `raise(buyerId, orderId, ...)`: only DELIVERED and within 72 hours of `delivered_at`, otherwise 409 "claim window closed".
  - `resolve(claimId, credit, note)`: credit ≤ order total. Claim RESOLVED, order CLOSED.
  - `reject(claimId, note)`: claim REJECTED, order CLOSED.
  - Scheduled: orders delivered more than 72 hours ago with no claim auto-CLOSE.
- UI:
  - buyer order detail with a timeline (Paid → Packed → Dispatched → Delivered → Closed), plus buttons for delivery, no issues and raise claim
  - seller `app/claims/page.tsx`
- Test: `ClaimTests`. A claim after 72 hours gives 409 (set `delivered_at` back in the test); credit > total gives 400.

- [ ] Tests, implementation, commit.

### Task 10: Owner reports

**Files:**
- Modify: `reporting/DashboardController.java`. Move to `/api/seller/owner/dashboard` and add:
  - **Recovery per container:** sum of paid order totals for lots containing that container's cartons (pro-rated by the cartons' MRP share) ÷ landed cost.
  - **Stock aging buckets:** 0–30, 31–60, 61–90 and 90+ days, by `handling_unit.created_at` for AVAILABLE.
  - **Funnel:** listed → reserved → paid → dispatched → closed counts.
  - **Claims rate:** claims ÷ delivered orders.
- UI: dashboard sections with simple bar charts (CSS widths, no chart library).
- Test: `DashboardTests`. Recovery % for the seeded container matches a hand-computed value.

- [ ] Tests, implementation, commit.

### Task 11: Guided tour, demo seed covering every stage, README

**Files:**
- Rewrite `DemoDataSeeder` so that RK has data at **every** stage:
  - 1 container ARRIVED (ready for dock check)
  - 1 SORTING with some cartons not yet shelved
  - 1 SORTED
  - lots in DRAFT / LISTED (one accepting offers) / RESERVED
  - orders in PAID, PACKED, DISPATCHED, DELIVERED, and CLOSED with a resolved claim
  - 1 open offer
  - 1 pending buyer

  The seeder runs only when the DB is empty. The README says to reset with `docker rm -f lb-postgres` and re-run.
- Create:
  - `apps/seller-console/app/guide/page.tsx`: the 17 steps from the Workflow table, each with role, link and a one-line "why"
  - `components/PageHelp.tsx`: a banner on every page with a one-line explanation and its step number
- README: the workflow table and a demo script.

- [ ] Build both apps, run lint, run all backend tests. Commit.

### Task 12: Full Chrome walkthrough (acceptance)

- [ ] Restart the servers on a fresh DB. In Chrome, run steps 1–17 end to end on a **new** container, using the correct role login for each step. Record a GIF (`lotbazaar_end_to_end.gif`).
- [ ] Confirm the dashboard shows recovery % for the new container and ledger drift 0.
- [ ] Check a negative path on screen: hold expiry, a wrong-carton scan, a late claim.
- [ ] Report to the user which steps passed, with screenshots or the GIF.
