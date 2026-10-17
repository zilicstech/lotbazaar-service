package com.lotbazaar.commerce;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** After delivery: buyer confirms, raises a claim within 72 hours or closes the order; seller settles claims. */
@Service
@Transactional
public class ClaimService {

	public record NewClaim(String type, int pieces, String note) {
	}

	public record Resolution(BigDecimal creditAmount, String note) {
	}

	public record ClaimView(UUID id, UUID orderId, String orderNo, String lotCode, String lotTitle, BigDecimal orderTotal,
			String type, int pieces, String note, String status, BigDecimal creditAmount, String resolutionNote,
			String buyerBusiness, String buyerCity, Instant createdAt, Instant resolvedAt) {
	}

	static final Duration CLAIM_WINDOW = Duration.ofHours(72);

	private static final String CLAIM_SELECT = """
			select c.id, c.order_id, o.order_no, l.code as lot_code, l.title as lot_title, o.total as order_total,
			       c.type, c.pieces, c.note, c.status, c.credit_amount, c.resolution_note,
			       u.business_name as buyer_business, u.city as buyer_city, c.created_at, c.resolved_at
			from claim c join sales_order o on o.id = c.order_id join lot l on l.id = o.lot_id
			join app_user u on u.id = o.buyer_user_id""";

	private final JdbcClient jdbc;

	private final OrderService orders;

	ClaimService(JdbcClient jdbc, OrderService orders) {
		this.jdbc = jdbc;
		this.orders = orders;
	}

	public OrderService.OrderView confirmDelivery(UUID buyerId, UUID orderId) {
		orders.orderOfBuyer(buyerId, orderId);
		transition(orderId, "DISPATCHED", "status = 'DELIVERED', delivered_at = now()",
				"Only dispatched orders can be marked delivered");
		return orders.order(orderId);
	}

	/** Buyer is happy with the lot: closes the order before the claim window ends. */
	public OrderService.OrderView noIssues(UUID buyerId, UUID orderId) {
		orders.orderOfBuyer(buyerId, orderId);
		if (jdbc.sql("select count(*) from claim where order_id = ?").param(orderId).query(Integer.class).single() > 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has a claim; the seller will close it");
		}
		transition(orderId, "DELIVERED", "status = 'CLOSED', closed_at = now()", "Only delivered orders can be closed");
		return orders.order(orderId);
	}

	public ClaimView raise(UUID tenantId, UUID buyerId, UUID orderId, NewClaim n) {
		var order = orders.orderOfBuyer(buyerId, orderId);
		if (!"DELIVERED".equals(order.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Claims can be raised only on delivered orders");
		}
		if (order.deliveredAt().plus(CLAIM_WINDOW).isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "The 72-hour claim window for this order has closed");
		}
		if (n.type() == null || !List.of("SHORT", "WRONG_GRADE", "DAMAGED", "OTHER").contains(n.type())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose what is wrong with the lot");
		}
		if (n.note() == null || n.note().isBlank() || n.pieces() < 0 || n.pieces() > order.pieceCount()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Describe the problem and give the affected pieces (0 to " + order.pieceCount() + ")");
		}
		var id = UUID.randomUUID();
		try {
			jdbc.sql("insert into claim(id, tenant_id, order_id, type, pieces, note) values (?,?,?,?,?,?)")
				.params(id, tenantId, orderId, n.type(), n.pieces(), n.note().trim())
				.update();
		}
		catch (DuplicateKeyException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A claim already exists for this order");
		}
		return claim(id);
	}

	/** Seller settles a claim with a credit note amount (0 allowed) and closes the order. */
	public ClaimView resolve(UUID claimId, Resolution r) {
		var c = claim(claimId);
		if (r.creditAmount() == null || r.creditAmount().signum() < 0 || r.creditAmount().compareTo(c.orderTotal()) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Credit must be between ₹0 and the order total ₹" + c.orderTotal().toPlainString());
		}
		return settle(c, "RESOLVED", r.creditAmount(), r.note());
	}

	public ClaimView reject(UUID claimId, Resolution r) {
		if (r.note() == null || r.note().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the buyer a reason for rejecting the claim");
		}
		return settle(claim(claimId), "REJECTED", null, r.note());
	}

	/** Orders delivered more than 72 hours ago with no claim close themselves. */
	public int autoClose() {
		return jdbc.sql("""
				update sales_order o set status = 'CLOSED', closed_at = now()
				where o.status = 'DELIVERED' and o.delivered_at < now() - interval '72 hours'
				  and not exists (select 1 from claim c where c.order_id = o.id)""").update();
	}

	@Transactional(readOnly = true)
	public List<ClaimView> claims() {
		return jdbc.sql(CLAIM_SELECT + " order by c.status = 'OPEN' desc, c.created_at desc").query(ClaimView.class).list();
	}

	@Transactional(readOnly = true)
	public ClaimView claim(UUID id) {
		return jdbc.sql(CLAIM_SELECT + " where c.id = ?")
			.param(id)
			.query(ClaimView.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found"));
	}

	private ClaimView settle(ClaimView c, String status, BigDecimal credit, String note) {
		if (jdbc.sql("""
				update claim set status = ?, credit_amount = ?, resolution_note = ?, resolved_at = now()
				where id = ? and status = 'OPEN'""").params(status, credit, note == null ? null : note.trim(), c.id())
			.update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This claim is already settled");
		}
		transition(c.orderId(), "DELIVERED", "status = 'CLOSED', closed_at = now()", "Order is not awaiting a claim");
		return claim(c.id());
	}

	// set clause is always a constant from this class, never user input.
	private void transition(UUID orderId, String from, String set, String error) {
		if (jdbc.sql("update sales_order set " + set + " where id = ? and status = ?").params(orderId, from).update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, error);
		}
	}

}
