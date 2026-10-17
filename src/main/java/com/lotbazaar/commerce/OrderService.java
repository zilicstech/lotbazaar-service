package com.lotbazaar.commerce;

import com.lotbazaar.lots.LotService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Checkout (lot hold + GST), mock payment, order views and buyer access per tenant storefront. */
@Service
@Transactional
public class OrderService {

	public record OrderView(UUID id, String orderNo, UUID lotId, String lotCode, String lotTitle, int pieceCount,
			BigDecimal amount, String status, Instant createdAt, Instant dispatchedAt, String buyerName,
			String buyerBusiness, String buyerCity, String buyerGstin, String source, Instant holdExpiresAt,
			BigDecimal gstRate, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total, Instant paidAt,
			Instant packedAt, BigDecimal dispatchKg, String transporter, String vehicleNo, Instant deliveredAt,
			Instant closedAt, String invoiceNo, String claimStatus) {
	}

	public record PaymentResult(boolean success, String message, OrderView order) {
	}

	public record BuyerView(UUID id, String name, String email, String businessName, String gstin, String city,
			String status, Instant createdAt) {
	}

	static final Duration BUY_NOW_HOLD = Duration.ofMinutes(15);

	static final Duration OFFER_HOLD = Duration.ofHours(24);

	static final String ORDER_SELECT = """
			select o.id, o.order_no, o.lot_id, l.code as lot_code, l.title as lot_title, l.piece_count, o.amount,
			       o.status, o.created_at, o.dispatched_at, u.name as buyer_name, u.business_name as buyer_business,
			       u.city as buyer_city, u.gstin as buyer_gstin, o.source, o.hold_expires_at, o.gst_rate, o.cgst,
			       o.sgst, o.igst, o.total, o.paid_at, o.packed_at, o.dispatch_kg, o.transporter, o.vehicle_no,
			       o.delivered_at, o.closed_at, i.invoice_no, c.status as claim_status
			from sales_order o join lot l on l.id = o.lot_id join app_user u on u.id = o.buyer_user_id
			left join invoice i on i.order_id = o.id left join claim c on c.order_id = o.id""";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final LotService lots;

	OrderService(JdbcClient jdbc, LotService lots) {
		this.jdbc = jdbc;
		this.lots = lots;
	}

	/** Buyer status with the current tenant; null when the buyer never registered with it. */
	@Transactional(readOnly = true)
	public String buyerStatus(UUID buyerId) {
		return jdbc.sql("select status from buyer_tenant_relationship where buyer_user_id = ?")
			.param(buyerId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	/**
	 * Buy Now: holds the lot for 15 minutes and opens a payment-pending order. The guarded LISTED-to-RESERVED update
	 * makes exactly one concurrent buyer win; the rest get 409.
	 */
	public OrderView checkout(UUID tenantId, UUID buyerId, UUID lotId) {
		requireApproved(buyerId);
		var lot = lots.lot(lotId);
		if (!lots.reserve(lotId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Sorry, this lot is no longer available");
		}
		return order(createPending(tenantId, buyerId, lotId, lot.priceAmount(), "BUY_NOW", BUY_NOW_HOLD));
	}

	/** Opens a payment-pending order on a lot the caller has already reserved. Computes GST by place of supply. */
	UUID createPending(UUID tenantId, UUID buyerId, UUID lotId, BigDecimal taxable, String source, Duration hold) {
		var rate = jdbc.sql("select gst_rate from tenant where id = ?").param(tenantId).query(BigDecimal.class).single();
		var sellerState = jdbc.sql("""
				select w.state_code from handling_unit h join warehouse w on w.id = h.warehouse_id
				where h.lot_id = ? limit 1""").param(lotId).query(String.class).single();
		var buyerState = jdbc.sql("select substr(gstin, 1, 2) from app_user where id = ?")
			.param(buyerId)
			.query(String.class)
			.single();
		var tax = Gst.of(taxable, rate, sellerState.equals(buyerState));
		var id = UUID.randomUUID();
		var orderNo = "SO-" + Long.toString(Math.abs(RANDOM.nextLong()) % 2_176_782_336L, 36).toUpperCase();
		jdbc.sql("""
				insert into sales_order(id, tenant_id, order_no, buyer_user_id, lot_id, amount, status, source,
				                        hold_expires_at, gst_rate, cgst, sgst, igst, total)
				values (?,?,?,?,?,?, 'PAYMENT_PENDING', ?, ?,?,?,?,?,?)""")
			.params(id, tenantId, orderNo, buyerId, lotId, taxable, source,
					java.sql.Timestamp.from(Instant.now().plus(hold)), rate, tax.cgst(), tax.sgst(), tax.igst(),
					tax.total())
			.update();
		return id;
	}

	/**
	 * Mock payment gateway callback. Success captures the order and sells the lot; failure is recorded and the buyer
	 * may retry until the hold expires.
	 */
	public PaymentResult pay(UUID buyerId, UUID orderId, String method, boolean success) {
		var order = orderOfBuyer(buyerId, orderId);
		var expired = order.holdExpiresAt() != null && order.holdExpiresAt().isBefore(Instant.now());
		if (expired && List.of("PAYMENT_PENDING", "CANCELLED").contains(order.status())) {
			throw new ResponseStatusException(HttpStatus.GONE,
					"Your hold expired and the lot went back on sale. Please check out again.");
		}
		if (!"PAYMENT_PENDING".equals(order.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is " + order.status().toLowerCase());
		}
		var ref = "pay_mock_" + Long.toString(Math.abs(RANDOM.nextLong()), 36);
		// shortcut: mock gateway; replace with Razorpay/Cashfree order + signed webhook (story S8.1) before pilot.
		jdbc.sql("insert into payment(id, tenant_id, order_id, method, status, gateway_ref, amount) "
				+ "select ?, tenant_id, id, ?, ?, ?, total from sales_order where id = ?")
			.params(UUID.randomUUID(), method == null ? "UPI" : method, success ? "SUCCESS" : "FAILED", ref, orderId)
			.update();
		if (!success) {
			return new PaymentResult(false, "Payment failed at the bank. You can try again before the hold expires.",
					order);
		}
		if (jdbc.sql("""
				update sales_order set status = 'PAID', paid_at = now()
				where id = ? and status = 'PAYMENT_PENDING' and hold_expires_at > now()""").param(orderId).update() != 1
				|| !lots.markSold(order.lotId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Order could not be paid; refresh and try again");
		}
		// The lot is sold: offers still waiting on it can never be accepted.
		jdbc.sql("""
				update offer set status = 'EXPIRED', updated_at = now()
				where lot_id = ? and status in ('OPEN', 'COUNTERED')""").param(order.lotId()).update();
		return new PaymentResult(true, "Payment received. The warehouse will now pack your lot.", order(orderId));
	}

	/** Cancels payment-pending orders past their hold and puts their lots back on sale. */
	public int releaseExpiredHolds() {
		var lotIds = jdbc.sql("""
				update sales_order set status = 'CANCELLED'
				where status = 'PAYMENT_PENDING' and hold_expires_at < now() returning lot_id""")
			.query(UUID.class)
			.list();
		for (var lotId : lotIds) {
			lots.release(lotId);
			jdbc.sql("update offer set status = 'EXPIRED', updated_at = now() where lot_id = ? and status = 'ACCEPTED'")
				.param(lotId)
				.update();
		}
		return lotIds.size();
	}

	@Transactional(readOnly = true)
	public List<OrderView> ordersOfBuyer(UUID buyerId) {
		return jdbc.sql(ORDER_SELECT + " where o.buyer_user_id = ? and o.status <> 'CANCELLED' order by o.created_at desc")
			.param(buyerId)
			.query(OrderView.class)
			.list();
	}

	@Transactional(readOnly = true)
	public OrderView orderOfBuyer(UUID buyerId, UUID orderId) {
		var order = order(orderId);
		if (!jdbc.sql("select buyer_user_id = ? from sales_order where id = ?")
			.params(buyerId, orderId)
			.query(Boolean.class)
			.single()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
		}
		return order;
	}

	@Transactional(readOnly = true)
	public List<OrderView> orders() {
		return jdbc.sql(ORDER_SELECT + " where o.status <> 'CANCELLED' order by o.created_at desc")
			.query(OrderView.class)
			.list();
	}

	@Transactional(readOnly = true)
	public List<BuyerView> buyers() {
		return jdbc.sql("""
				select u.id, u.name, u.email, u.business_name, u.gstin, u.city, r.status, r.created_at
				from buyer_tenant_relationship r join app_user u on u.id = r.buyer_user_id
				order by r.status = 'PENDING' desc, r.created_at desc""").query(BuyerView.class).list();
	}

	public void setBuyerStatus(UUID buyerId, String status) {
		if (!List.of("APPROVED", "BLOCKED").contains(status)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be APPROVED or BLOCKED");
		}
		if (jdbc.sql("update buyer_tenant_relationship set status = ? where buyer_user_id = ?")
			.params(status, buyerId)
			.update() != 1) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Buyer not found");
		}
	}

	@Transactional(readOnly = true)
	public OrderView order(UUID id) {
		return jdbc.sql(ORDER_SELECT + " where o.id = ?")
			.param(id)
			.query(OrderView.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
	}

	void requireApproved(UUID buyerId) {
		var status = buyerStatus(buyerId);
		if (!"APPROVED".equals(status)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					status == null ? "You are not registered with this seller"
							: "Your account is " + status.toLowerCase() + " with this seller");
		}
	}

	/** GST on a B2B supply: CGST + SGST within the same state, IGST across states. */
	record Gst(BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total) {

		static Gst of(BigDecimal taxable, BigDecimal ratePct, boolean intraState) {
			var full = taxable.multiply(ratePct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
			if (intraState) {
				var half = taxable.multiply(ratePct).divide(BigDecimal.valueOf(200), 2, RoundingMode.HALF_UP);
				return new Gst(half, half, BigDecimal.ZERO, taxable.add(half).add(half));
			}
			return new Gst(BigDecimal.ZERO, BigDecimal.ZERO, full, taxable.add(full));
		}
	}

}
