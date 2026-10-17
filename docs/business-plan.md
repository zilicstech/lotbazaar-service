# LotBazaar: Business Plan

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

