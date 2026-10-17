# Architecture Review (adversarial + edge-case), 2026-10-15

Scope: `architecture.md`, `sku-inventory-model.md`. Lenses: adversarial, edge-case-hunter. Editorial lenses skipped (design critique requested).

## Adversarial

| # | Location | Problem | Fix | Consequence if shipped |
|---|---|---|---|---|
| A1 | 6.4 Multi-tenancy | RLS bypassed by table owner or superuser role; session-level `SET` leaks across pooled connections | App connects as non-owner role without BYPASSRLS; `ALTER TABLE ... FORCE ROW LEVEL SECURITY`; use `set_config('app.tenant_id', ?, true)` (transaction-local) only | Cross-tenant data leak between competing liquidators |
| A2 | 6.4 | Platform-scope tables (buyer, buyer_kyc) have no defined RLS stance | Classify every table as tenant-scoped, platform-scoped, or buyer-scoped; buyer-scoped policies keyed on `app.buyer_id` | Tenants read other tenants' buyer lists |
| A3 | 3.3 Standard lot | "Available = lots buildable from buckets" double-counts when recipes share buckets, and is combinatorial with size curves | Standard lots are **pre-kitted**: lot build assigns HUs to N lot units; listing qty = kitted units | Oversell of standard lots; allocation fails at pick |
| A4 | 3.1/3.3 | Lot promises a size curve but cartons only record a size band | Record per-size piece counts per carton at sort (tally, not scan), or sell "size band mix" without a curve guarantee | Size-curve disputes on every lot |
| A5 | 3.2 | Total MRP estimated as band × count; price is % of MRP | Grade A requires actual MRP tally per carton; estimated MRP flagged on manifest and priced on piece count instead | Mis-stated MRP basis; buyer disputes and legal exposure |
| A6 | 3.5 Costing | Landed cost split by MRP share ignores grade | Allocate by MRP × grade factor (e.g. A 1.0, B 0.6, C 0.3), tenant-configurable | Wrong margin per lot; bad pricing decisions |
| A7 | 6.6 #2 | Spring simple STOMP broker is in-memory per instance; multi-instance fan-out breaks | Use RabbitMQ STOMP broker relay (already in stack) | Bidders on other nodes miss live bids |
| A8 | 6.6 #1 | Lot could be listed in two modes at once (cart + offer + auction) | One active listing per lot; listing activation reserves HUs; offer acceptance goes through the same reserve call | Same lot sold twice |
| A9 | 6.6 #1 | Payment webhook can arrive after the 15-minute hold expires and stock is resold | Extend hold on payment initiation; late success with no stock triggers auto-refund and alert | Paid orders with no stock |
| A10 | Payments | NEFT/RTGS takes hours; 15-minute hold incompatible with large tickets | Pre-funded wallet for large buyers, or "awaiting transfer" hold (configurable, e.g. 24 h) backed by EMD | Large buyers cannot complete purchases |
| A11 | 6.6 #5 GST | Multi-state warehouses: place of supply, IGST vs CGST+SGST, and unregistered buyers not handled | Invoice from dispatching warehouse GSTIN; tax type by place of supply; decide policy for buyers without GSTIN | Wrong tax invoices; compliance penalties |
| A12 | 6.6 #5 | Invoice numbers must be gapless per GSTIN per FY; Postgres sequences leave gaps | Counter table row-locked inside the invoice transaction | Non-compliant invoice series |
| A13 | 6.5 Ledger | `qty_delta` unit ambiguous; no HU split/merge/repack events | Ledger carries both `hu_delta` and `piece_delta`; add SPLIT, MERGE, REPACK, ADJUST event types | Ledger cannot reconcile after any repack |
| A14 | 6.5 Lot manifest | Manifest is live derived data; changes after sale | Immutable manifest snapshot (versioned) at listing and at order | No evidence for claims |
| A15 | 6.2 Auth | Keycloak has no native phone/WhatsApp OTP | Custom Keycloak authenticator via MSG91/WhatsApp, or token exchange from own OTP service | Buyer login blocked |
| A16 | 6.6 #4 Offline | Offline-created HUs need IDs before sync | Pre-printed LPN label rolls with tenant prefix and check digit; server validates uniqueness on sync | Duplicate or unlabeled cartons |
| A17 | General | No flow for claims money (credit note, partial refund) or RTO / rejected deliveries | Add claim settlement (GST credit note, refund or wallet credit) and RTO inbound flow restoring HUs | Inventory and finance drift after disputes |
| A18 | 6.3 Async | Outbox consumers: no idempotency or DLQ defined | Consumer dedup table keyed on event id; DLQ per queue with alerting | Duplicate notifications or lost events |
| A19 | 6.3 Reporting | Reports on primary DB compete with auction writes | Read replica for reporting module | Auction latency spikes during report runs |

## Edge cases (unhandled paths)

| # | Location | Trigger | Guard |
|---|---|---|---|
| E1 | Auction | Zero bids or reserve not met | Close as UNSOLD; auto-relist to Buy Now or ladder per tenant rule |
| E2 | Auction | Equal bid amounts | Earlier server timestamp wins; proxy max ties resolved the same way |
| E3 | Auction | Second bidder declines or also defaults | Cascade max N bidders, then UNSOLD |
| E4 | Auction / EMD | Losing bidders' EMD; one EMD reused across concurrent auctions | Lock EMD per auction; release on loss automatically |
| E5 | Ladder | Floor price reached | Stop decrement; alert lot manager |
| E6 | Inventory | HU damaged or missing after lot reserved | Re-manifest and buyer consent, or cancel with refund |
| E7 | Intake | Container count differs from packing list | Supplier shortage record against container; cost re-allocation |
| E8 | Sorting | Weight variance > 5% | HU goes to QUARANTINE status; recount required before AVAILABLE |
| E9 | Buyer | GSTIN later cancelled or suspended | Periodic re-verification; block checkout |
| E10 | Tenant | Tenant offboarding | Data export and retention/deletion policy |
