# LotBazaar: SKU and Inventory Model

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

## Amendments after architecture review (2026-10-15)

- **Standard lots are pre-kitted.** At lot build, specific HUs are assigned to N lot units. The listing quantity is the count of kitted units, which removes double counting when two recipes share a bucket.
- **Per-size tally per carton** is recorded at sorting (a count per size, no scanning). Lot size curves come from these tallies.
- **Grade A cartons need an actual MRP tally.** Any MRP derived from band × count is marked "estimated" on the manifest.
- **Landed cost allocation** = MRP × grade factor (default A 1.0, B 0.6, C 0.3; tenant-configurable).
- **New HU statuses:** QUARANTINE (weight variance over 5%, recount required) and RTO.
- **New HU events:** SPLIT, MERGE, REPACK and ADJUST.
- **Manifest snapshots are immutable** and versioned at listing and at order.
