# LotBazaar: Product Scope (PRD input)

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

