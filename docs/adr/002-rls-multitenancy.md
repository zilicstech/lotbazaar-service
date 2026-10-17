# ADR-002: Shared schema multi-tenancy with PostgreSQL RLS

Status: Proposed
Date: 2026-10-15

## Context
Multiple liquidators must be isolated; competing tenants must never see each other's buyers or prices.

## Decision
tenant_id on every tenant table, RLS policies on current_setting('app.tenant_id') set per transaction, plus app-level filters. Buyer identity is global; buyer_tenant_relationship holds per-tenant data. Tenant-to-datasource router allows dedicated DB for enterprise tenants later.

## Consequences
Cheap onboarding, defence in depth. Needs isolation tests in CI and care with connection pooling and migrations.
