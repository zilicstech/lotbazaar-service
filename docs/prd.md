# LotBazaar: Product Requirements (Phase 1a MVP)

Status: Draft v1, 2026-10-15. Sources: `business-plan.md`, `product-scope.md`, `sku-inventory-model.md`, `architecture.md` (including review decisions).

## 1. Goal

Let the anchor liquidator sell branded apparel and footwear stock lots to verified Indian retailers online. The platform needs:
- full multi-warehouse inventory control
- a data model that can onboard more liquidators (tenants) later

**North Star:** weekly paid GMV from verified buyers.

**Guardrails:**
- claim rate under 3%
- container-to-cash under 45 days

## 2. Users

**Seller side (tenant):**
- Owner
- Finance
- Warehouse supervisor
- Sorter
- Lot manager
- Sales key-account manager (KAM)

**Buyer side:**
- Retailer (owner or buyer staff)

**Platform:**
- Super admin

## 3. Functional requirements

### Tenancy and access
- **FR1** Platform admin creates a tenant with legal entity, GSTINs per state, warehouses and plan.
- **FR2** The tenant owner invites users and assigns roles: OWNER, FINANCE, WAREHOUSE, SORTER, LOT_MANAGER, SALES, VIEWER.
- **FR3** Every tenant record is isolated by PostgreSQL RLS. No API returns another tenant's data.

### Master data
- **FR4** The tenant maintains brands, categories and sub-categories, grades, MRP bands, size bands, warehouses and bins.
- **FR5** The sort-bucket SKU code is generated from attributes and is unique per tenant.

### Intake and sorting
- **FR6** Create a container with supplier, packing list upload and landed cost components.
- **FR7** Receive a container at a warehouse (GRN), recording container-level count or weight and any shortage against the packing list.
- **FR8** Run a sorting session on the warehouse PWA. Per carton, record:
  - the scanned pre-printed LPN
  - the bucket attributes
  - a per-size piece tally
  - weight
  - an MRP tally (required for grade A) or estimated MRP
  - a photo
- **FR9** Weight variance over the tolerance puts the carton into QUARANTINE until it is recounted.
- **FR10** Put away cartons to bins, transfer them between warehouses (with an in-transit state), and run cycle counts.

### Inventory
- **FR11** An append-only ledger records every HU and piece movement, with event type and reference.
- **FR12** Real-time stock by bucket × warehouse: on hand, reserved, available.
- **FR13** A nightly reconciliation compares the ledger, the balances and the HU counts, and reports mismatches.

### Lots
- **FR14** **Unique lot:** the lot manager selects specific cartons.
- **FR15** **Standard lot:** the lot manager defines a recipe and kits N units. Each unit is a fixed set of cartons.
- **FR16** The manifest is derived automatically: pieces, brand, category and size mix, total MRP, grade, weight and media. It is snapshotted immutably at listing and at order.

### Buyers
- **FR17** Buyer sign-up by phone OTP. KYC needs GSTIN (verified by API), PAN and a shop photo. The tenant approves or rejects the buyer.
- **FR18** Per-tenant buyer relationship: status, tier, blocked flag.
- **FR19** Phase 1a sells only to buyers with a GSTIN.

### Selling
- **FR20** **Listing:** one active listing per lot or kitted lot set. Modes: FIXED, OFFER, AUCTION. Price is entered as a % of MRP and shown as both % and ₹.
- **FR21** **Buy Now:** a cart reservation of 15 minutes. The hold extends when payment starts. First paid wins.
- **FR22** **Make an Offer:**
  - the buyer offers an amount
  - the seller accepts, counters or rejects
  - an accepted offer reserves stock and opens a payment window
- **FR23** **Auction:**
  - reserve price, minimum increment, start and end times
  - soft close of 120 seconds
  - proxy bidding
  - an EMD locked from the buyer's wallet
  - live updates
  - ties go to the earliest bid
  - unsold lots auto-relist per the tenant rule
  - the winner pays within a window; on default the EMD is forfeited and the lot cascades to the next bidder, at most 3 times
- **FR24** The visibility rule per brand can hide brand names and watermark images. The catalog is visible only to approved buyers.

### Orders, payments and compliance
- **FR25** Payment through the gateway: UPI, netbanking, or bank transfer to a virtual account. Wallet top-up for EMD and pre-funding.
- **FR26** The order is allocated to the reserved cartons. Pick by LPN scan, pack with a weight check, and dispatch.
- **FR27** The GST e-invoice (IRN) is issued from the dispatching warehouse's GSTIN, with place-of-supply tax and a gapless invoice series per GSTIN per financial year. An e-way bill is issued when the value is over ₹50,000.
- **FR28** **Claims:**
  - within 72 hours of delivery, with an unboxing video
  - the outcome is a GST credit note plus a refund or wallet credit
  - an RTO restores cartons to inventory

### Notifications and reports
- **FR29** WhatsApp and SMS alerts: KYC status, new lots matching saved filters, outbid, payment due, dispatch.
- **FR30** Reports: stock ageing, sell-through, recovery % per container, GMV, buyer activity, claim rate per sorter or bucket.

## 4. Non-functional requirements

| ID | Area | Requirement |
|---|---|---|
| NFR1 | Latency | Catalog p95 under 300 ms. Bid placement p95 under 150 ms server-side |
| NFR2 | Scale | 5,000 concurrent buyers during a live auction |
| NFR3 | Availability and recovery | 99.9% availability. RPO 5 min, RTO 1 h |
| NFR4 | Data residency | India (AWS ap-south-1). KYC documents encrypted with KMS |
| NFR5 | Correctness | No oversell under concurrency. Ledger reconciles to zero drift |
| NFR6 | Usability | Warehouse PWA works offline and supports Hindi |

## 5. Out of scope (Phase 1a)

- markdown ladder, pre-booking, credit or BNPL
- logistics aggregator integration
- export and multi-currency
- shared cross-tenant marketplace
- native apps
- piece-level serialisation

## 6. Acceptance for MVP launch

The end-to-end path works for real data at 1 warehouse:

container → sort → lot → buy (all 3 modes) → pay → dispatch → IRN → claim

Launch also needs:
- RLS isolation and concurrency test suites green
- k6 load test meets NFR1 and NFR2
- `/security-review` passed

## 7. Open questions

1. Do the brand contracts permit online listing with brand names?
2. Is the platform the e-commerce operator, or pure SaaS? This decides GST TCS and 194-O TDS duties. **Needs a CA.**
3. How long is the payment window for auction winners? The proposal is 48 hours.
