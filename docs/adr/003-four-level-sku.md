# ADR-003: Four-level SKU model (container, sort bucket, handling unit, lot)

Status: Proposed
Date: 2026-10-15

## Context
Sellable unit is a mixed bundle of 100-1000+ pieces; piece-level scanning is too costly for dead stock.

## Decision
Inventory SKU is the sort bucket (brand, gender, category, grade, MRP band, size band). Tracking unit is the carton (LPN). Sellable SKU is the lot, standard (recipe) or unique (specific cartons). Pieces are counted and weighed, never serialised.

## Consequences
About 1,000 cartons per container instead of 50,000 pieces. Manifest accuracy relies on sorting discipline, weight checks and a claims process.
