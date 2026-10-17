# ADR-001: Modular monolith first

Status: Proposed
Date: 2026-10-15

## Context
Large client scale (20+ containers/month, thousands of buyers) but small team; auction is only hot path.

## Decision
Spring Boot + Spring Modulith, one deployable. Modules own their tables and talk via public APIs and domain events. Extract auction or inventory later if load demands.

## Consequences
Simple ops and transactions early. Discipline needed to keep module boundaries; Modulith tests enforce them.
