# LotBazaar: Epics and Stories (Phase 1a)

Each story names the FRs it covers (from `prd.md`).

## E0. Foundation

- **S0.1** Monorepo scaffold:
  - Next.js apps `buyer-web` and `seller-console`
  - Spring Boot Modulith backend
  - docker-compose with Postgres, Redis, RabbitMQ and Keycloak
  - CI that builds and tests everything

  *AC:* `docker compose up` gives a working stack. CI is green.
- **S0.2** Flyway baseline. Tenant-context filter sets `app.tenant_id` per transaction. App DB role without BYPASSRLS.

  *AC:* an isolation test proves tenant B cannot read tenant A rows, including via direct SQL as the app role.
- **S0.3** Outbox table and relay to RabbitMQ. Idempotent consumer base. DLQ.
- **S0.4** Observability: OpenTelemetry, structured logs, audit log table.

## E1. Tenancy and auth (FR1–3)

- **S1.1** Platform admin creates tenants, GSTINs and warehouses.
- **S1.2** Keycloak realm setup. Seller login with roles. JWT carries the tenant claim.
- **S1.3** User invites and role management.
- **S1.4** Buyer phone-OTP authenticator (MSG91).

## E2. Master data and SKU taxonomy (FR4–5)

- **S2.1** CRUD for brands, categories, grades, MRP and size bands, warehouses and bins.
- **S2.2** Sort-bucket SKU code generator, unique per tenant.

  *AC:* the same attributes always give the same code, and different attributes give a different code.

## E3. Intake and sorting (FR6–10)

- **S3.1** Container creation, packing list upload, landed cost.
- **S3.2** GRN with shortage recording.
- **S3.3** Sorting session PWA: scan LPN, choose bucket, enter size tally, weight, MRP tally, photo.
- **S3.4** Weight-variance QUARANTINE and recount.
- **S3.5** Putaway, transfer (in transit), cycle count.
- **S3.6** Offline queue: IndexedDB plus server dedup by client UUID.

## E4. Inventory ledger (FR11–13)

- **S4.1** Ledger and stock_balance projection written in the same transaction.

  *AC:* every HU state change writes a ledger row.
- **S4.2** `inventory.reserve()` / `release()` / `commit()`, with guarded updates.

  *AC:* 100 parallel reservations on 1 unit give exactly 1 success.
- **S4.3** Nightly reconciliation job and report.
- **S4.4** SPLIT, MERGE, REPACK and ADJUST operations.

## E5. Lot builder (FR14–16)

- **S5.1** Unique lot from selected HUs.
- **S5.2** Recipe plus kitting of N standard lot units.
- **S5.3** Manifest derivation and immutable snapshot.
- **S5.4** Media upload to S3 with signed, watermarked URLs.

## E6. Buyer onboarding and storefront (FR17–19, FR24)

- **S6.1** KYC flow with GSTIN API verification.
- **S6.2** Tenant approval queue. Buyer tiers.
- **S6.3** Tenant storefront by subdomain, gated catalog, filters, lot detail with manifest.
- **S6.4** Brand visibility rules.

## E7. Buy Now and orders (FR20–21, FR26)

- **S7.1** Listing management (one active listing per lot).
- **S7.2** Cart hold with TTL and an expiry job.
- **S7.3** Order creation, allocation, pick/pack/dispatch screens.

## E8. Payments and wallet (FR25)

- **S8.1** Gateway integration (Razorpay or Cashfree): checkout plus webhook signature verification.
- **S8.2** Wallet ledger: top-up, EMD lock and release, forfeit.
- **S8.3** Late-payment auto-refund path.

## E9. GST invoicing and dispatch (FR27)

- **S9.1** Gapless invoice counter per GSTIN per financial year.
- **S9.2** Tax calculation by place of supply.
- **S9.3** GSP integration: IRN and e-way bill.

## E10. Offers (FR22)

- **S10.1** Offer, counter, accept or reject, with a payment window.

## E11. Auctions (FR23)

- **S11.1** Auction setup, EMD gate.
- **S11.2** Bid placement with per-auction lock, proxy bidding, soft close and tie rule.
- **S11.3** Live updates through the STOMP relay.
- **S11.4** Close job: winner, payment window, default cascade, unsold relist.

## E12. Claims and notifications (FR28–29)

- **S12.1** Claim submission with video, review, credit note and refund.
- **S12.2** RTO inbound.
- **S12.3** WhatsApp and SMS notifications, saved-search alerts.

## E13. Reports (FR30)

- **S13.1** Stock ageing, sell-through, recovery %, GMV, claim-rate dashboards (read replica).

## Sprint plan (2-week sprints, team per business plan)

| Sprint | Epics |
|---|---|
| 1 | E0, E1 (S1.1–1.3) |
| 2 | E2, E4 (S4.1–4.2) |
| 3 | E3 (S3.1–3.4) |
| 4 | E3 (S3.5–3.6), E4 (S4.3–4.4), E5 (S5.1) |
| 5 | E5, E6 (S6.1–6.2), S1.4 |
| 6 | E6 (S6.3–6.4), E7 |
| 7 | E8, E9 (S9.1–9.2) |
| 8 | E9 (S9.3), E10, E12 (S12.3) |
| 9 | E11 |
| 10 | E12 (S12.1–12.2), E13 |
| 11 | Hardening: k6 load test, `/security-review`, e2e |
| 12 | Pilot at 1 warehouse |
