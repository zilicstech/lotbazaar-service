# ADR-004: Hybrid sales modes, fixed price by default

Status: Proposed
Date: 2026-10-15

## Context
Small Indian retailers value price certainty; auctions need 3+ bidders and risk thin liquidity and collusion.

## Decision
Buy Now is default. Offers/RFQ for large buyers. Timed auctions (reserve, proxy bid, soft close, EMD) only for unique or premium lots. Markdown ladder for aged stock. Pre-booking for in-transit containers.

## Consequences
More modes to build; sequence them: Buy Now and offers first, auctions after, ladder in 1b. Tune mix from recovery % data.
