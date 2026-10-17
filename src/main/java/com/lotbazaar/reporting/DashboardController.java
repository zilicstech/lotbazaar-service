package com.lotbazaar.reporting;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Owner dashboard: headline numbers, recovery per container, stock aging, order funnel, claims. */
@RestController
class DashboardController {

	record Headline(int containers, int availableCartons, int availablePieces, BigDecimal availableMrp,
			int unshelvedCartons, int draftLots, int listedLots, int reservedLots, int soldLots, BigDecimal gmv,
			BigDecimal soldMrp, int pendingBuyers, int openOffers, int ordersToFulfil, int openClaims,
			BigDecimal claimCredits, int deliveredOrders, int claims, long ledgerDrift) {
	}

	/** Sales are allocated to a container by its cartons' share of each sold lot's MRP. */
	record Recovery(String ref, String supplier, String status, BigDecimal landedCost, BigDecimal containerMrp,
			BigDecimal soldMrp, BigDecimal revenue, BigDecimal recoveryPct, BigDecimal sellThroughPct) {
	}

	record Aging(String bucket, int cartons, int pieces, BigDecimal mrp) {
	}

	record Stage(String status, int orders, BigDecimal value) {
	}

	record Dashboard(Headline headline, List<Recovery> recovery, List<Aging> aging, List<Stage> funnel) {
	}

	private static final String PAID_STATES = "('PAID','PICKING','PACKED','DISPATCHED','DELIVERED','CLOSED')";

	private final JdbcClient jdbc;

	DashboardController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/api/seller/dashboard")
	@Transactional(readOnly = true)
	public Dashboard dashboard() {
		var headline = jdbc.sql("""
				select (select count(*) from container)::int as containers,
				       (select count(*) from handling_unit where status = 'AVAILABLE')::int as available_cartons,
				       (select coalesce(sum(piece_count), 0) from handling_unit where status = 'AVAILABLE')::int as available_pieces,
				       (select coalesce(sum(total_mrp), 0) from handling_unit where status = 'AVAILABLE') as available_mrp,
				       (select count(*) from handling_unit where status = 'SORTED')::int as unshelved_cartons,
				       (select count(*) from lot where status = 'DRAFT')::int as draft_lots,
				       (select count(*) from lot where status = 'LISTED')::int as listed_lots,
				       (select count(*) from lot where status = 'RESERVED')::int as reserved_lots,
				       (select count(*) from lot where status = 'SOLD')::int as sold_lots,
				       (select coalesce(sum(amount), 0) from sales_order where status in %1$s) as gmv,
				       (select coalesce(sum(l.total_mrp), 0) from sales_order o join lot l on l.id = o.lot_id
				        where o.status in %1$s) as sold_mrp,
				       (select count(*) from buyer_tenant_relationship where status = 'PENDING')::int as pending_buyers,
				       (select count(*) from offer where status = 'OPEN')::int as open_offers,
				       (select count(*) from sales_order where status in ('PAID', 'PICKING', 'PACKED'))::int as orders_to_fulfil,
				       (select count(*) from claim where status = 'OPEN')::int as open_claims,
				       (select coalesce(sum(credit_amount), 0) from claim) as claim_credits,
				       (select count(*) from sales_order where delivered_at is not null)::int as delivered_orders,
				       (select count(*) from claim)::int as claims,
				       (select coalesce(sum(piece_delta), 0) from inventory_ledger)
				         - (select coalesce(sum(piece_count), 0) from handling_unit where status <> 'SOLD') as ledger_drift
				""".formatted(PAID_STATES)).query(Headline.class).single();
		var recovery = jdbc.sql("""
				select c.ref, c.supplier, c.status, c.landed_cost,
				       coalesce(sum(h.total_mrp), 0) as container_mrp,
				       coalesce(sum(h.total_mrp) filter (where o.id is not null), 0) as sold_mrp,
				       round(coalesce(sum(o.amount * h.total_mrp / nullif(l.total_mrp, 0)), 0), 2) as revenue,
				       round(100 * coalesce(sum(o.amount * h.total_mrp / nullif(l.total_mrp, 0)), 0)
				             / nullif(c.landed_cost, 0), 1) as recovery_pct,
				       round(100 * coalesce(sum(h.total_mrp) filter (where o.id is not null), 0)
				             / nullif(sum(h.total_mrp), 0), 1) as sell_through_pct
				from container c
				left join handling_unit h on h.container_id = c.id
				left join lot l on l.id = h.lot_id
				left join sales_order o on o.lot_id = l.id and o.status in %s
				group by c.id order by c.received_at desc""".formatted(PAID_STATES)).query(Recovery.class).list();
		var aging = jdbc.sql("""
				select bucket, count(*)::int as cartons, sum(piece_count)::int as pieces, sum(total_mrp) as mrp
				from (select piece_count, total_mrp,
				             case when now() - created_at <= interval '30 days' then '0-30 days'
				                  when now() - created_at <= interval '60 days' then '31-60 days'
				                  when now() - created_at <= interval '90 days' then '61-90 days'
				                  else '90+ days' end as bucket
				      from handling_unit where status in ('SORTED', 'AVAILABLE')) s
				group by bucket order by bucket""").query(Aging.class).list();
		var funnel = jdbc.sql("""
				select status, count(*)::int as orders, coalesce(sum(total), 0) as value
				from sales_order where status <> 'CANCELLED' group by status""").query(Stage.class).list();
		return new Dashboard(headline, recovery, aging, funnel);
	}

}
