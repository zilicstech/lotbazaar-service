package com.lotbazaar.commerce;

import com.lotbazaar.inventory.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Warehouse side of an order: pick with scan check, pack with weight check, dispatch with GST invoice. */
@Service
@Transactional
public class FulfilmentService {

	/** Order as the warehouse sees it: no money. */
	public record WorkOrder(UUID id, String orderNo, String lotCode, String lotTitle, int pieceCount, int cartons,
			int picked, String status, String buyerBusiness, String buyerCity, String warehouse, Instant paidAt,
			Instant packedAt, Instant dispatchedAt, BigDecimal expectedKg, BigDecimal dispatchKg, String transporter,
			String vehicleNo, String invoiceNo) {
	}

	public record PickLine(String lpn, String skuCode, int pieceCount, BigDecimal weightKg, String bin,
			boolean picked) {
	}

	public record Pack(BigDecimal kg, boolean confirm) {
	}

	public record Dispatch(String transporter, String vehicleNo) {
	}

	public record InvoiceView(String invoiceNo, Instant issuedAt, String irn, String ewayBillNo, String sellerName,
			String sellerGstin, String sellerWarehouse, String sellerState, String buyerName, String buyerBusiness,
			String buyerGstin, String buyerCity, String orderNo, String lotCode, String lotTitle, int pieceCount,
			String hsn, BigDecimal taxable, BigDecimal gstRate, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
			BigDecimal total, String transporter, String vehicleNo) {
	}

	/** Pack weight may differ from the sum of carton weights by this much before a recheck is required. */
	static final BigDecimal WEIGHT_TOLERANCE_PCT = BigDecimal.valueOf(5);

	/** E-way bill is mandatory above this consignment value (INR). */
	static final BigDecimal EWAY_THRESHOLD = BigDecimal.valueOf(50_000);

	private static final String WORK_SELECT = """
			select o.id, o.order_no, l.code as lot_code, l.title as lot_title, l.piece_count,
			       (select count(*) from handling_unit h where h.lot_id = l.id)::int as cartons,
			       (select count(*) from order_pick p where p.order_id = o.id)::int as picked,
			       o.status, u.business_name as buyer_business, u.city as buyer_city,
			       (select w.name from handling_unit h join warehouse w on w.id = h.warehouse_id
			        where h.lot_id = l.id limit 1) as warehouse,
			       o.paid_at, o.packed_at, o.dispatched_at,
			       (select round(sum(h.weight_g) / 1000.0, 1) from handling_unit h where h.lot_id = l.id) as expected_kg,
			       o.dispatch_kg, o.transporter, o.vehicle_no, i.invoice_no
			from sales_order o join lot l on l.id = o.lot_id join app_user u on u.id = o.buyer_user_id
			left join invoice i on i.order_id = o.id""";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final InventoryService inventory;

	FulfilmentService(JdbcClient jdbc, InventoryService inventory) {
		this.jdbc = jdbc;
		this.inventory = inventory;
	}

	@Transactional(readOnly = true)
	public List<WorkOrder> queue() {
		return jdbc.sql(WORK_SELECT + "\n" + """
				where o.status in ('PAID', 'PICKING', 'PACKED', 'DISPATCHED')
				 order by o.status = 'DISPATCHED', o.paid_at""").query(WorkOrder.class).list();
	}

	@Transactional(readOnly = true)
	public WorkOrder workOrder(UUID orderId) {
		return jdbc.sql(WORK_SELECT + " where o.id = ?")
			.param(orderId)
			.query(WorkOrder.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
	}

	@Transactional(readOnly = true)
	public List<PickLine> pickList(UUID orderId) {
		return jdbc.sql("""
				select h.lpn, b.sku_code, h.piece_count, round(h.weight_g / 1000.0, 1) as weight_kg, bl.code as bin,
				       exists (select 1 from order_pick p where p.order_id = o.id and p.hu_id = h.id) as picked
				from sales_order o join handling_unit h on h.lot_id = o.lot_id
				join sort_bucket b on b.id = h.bucket_id left join bin_location bl on bl.id = h.bin_id
				where o.id = ? order by bl.code, h.lpn""").param(orderId).query(PickLine.class).list();
	}

	/** Scan one carton label at the bin. Only cartons of this order's lot are accepted; repeats are harmless. */
	public WorkOrder scan(UUID tenantId, UUID orderId, String lpn) {
		var order = workOrder(orderId);
		if (!List.of("PAID", "PICKING").contains(order.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Order " + order.orderNo() + " is not open for picking");
		}
		var code = lpn == null ? "" : lpn.trim().toUpperCase();
		var hu = jdbc.sql("""
				select h.id from handling_unit h join sales_order o on o.lot_id = h.lot_id
				where o.id = ? and h.lpn = ?""").params(orderId, code).query(UUID.class).optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
					"Carton " + code + " is NOT part of this order. Put it back."));
		jdbc.sql("""
				insert into order_pick(tenant_id, order_id, hu_id) values (?,?,?)
				on conflict (order_id, hu_id) do nothing""").params(tenantId, orderId, hu).update();
		jdbc.sql("update sales_order set status = 'PICKING' where id = ? and status = 'PAID'").param(orderId).update();
		return workOrder(orderId);
	}

	/**
	 * Pack check: every carton picked, and the weighed consignment within 5% of the cartons' sorting weights. A bigger
	 * gap fails with 409 unless the packer re-weighed and confirms.
	 */
	public WorkOrder pack(UUID orderId, Pack p) {
		var order = workOrder(orderId);
		if (!"PICKING".equals(order.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Scan the cartons before packing");
		}
		var missing = pickList(orderId).stream().filter(l -> !l.picked()).map(PickLine::lpn).toList();
		if (!missing.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Still to pick: " + String.join(", ", missing));
		}
		if (p.kg() == null || p.kg().signum() <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the packed weight in kg");
		}
		var diffPct = p.kg()
			.subtract(order.expectedKg())
			.abs()
			.multiply(BigDecimal.valueOf(100))
			.divide(order.expectedKg(), 1, RoundingMode.HALF_UP);
		if (diffPct.compareTo(WEIGHT_TOLERANCE_PCT) > 0 && !p.confirm()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Weight " + p.kg() + " kg is " + diffPct
					+ "% off the expected " + order.expectedKg() + " kg. Re-check the pieces, then confirm.");
		}
		jdbc.sql("""
				update sales_order set status = 'PACKED', packed_at = now(), dispatch_kg = ?
				where id = ? and status = 'PICKING'""").params(p.kg(), orderId).update();
		return workOrder(orderId);
	}

	/**
	 * Dispatch: issues the GST tax invoice (gapless number per financial year), the e-invoice IRN and, above ₹50,000,
	 * an e-way bill number; the cartons leave stock.
	 */
	public WorkOrder dispatch(UUID tenantId, UUID orderId, Dispatch d) {
		if (d.transporter() == null || d.transporter().isBlank() || d.vehicleNo() == null || d.vehicleNo().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transporter and vehicle number are required");
		}
		if (jdbc.sql("""
				update sales_order set status = 'DISPATCHED', dispatched_at = now(), transporter = ?, vehicle_no = ?
				where id = ? and status = 'PACKED'""")
			.params(d.transporter().trim(), d.vehicleNo().trim().toUpperCase(), orderId)
			.update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only packed orders can be dispatched");
		}
		var fy = financialYear(LocalDate.now(ZoneId.of("Asia/Kolkata")));
		int next = jdbc.sql("""
				insert into invoice_seq(tenant_id, fy, next_no) values (?, ?, 1)
				on conflict (tenant_id, fy) do update set next_no = invoice_seq.next_no + 1
				returning next_no""").params(tenantId, fy).query(Integer.class).single();
		var prefix = jdbc.sql("select left(upper(split_part(code, '-', 1)), 4) from tenant where id = ?")
			.param(tenantId)
			.query(String.class)
			.single();
		// CGST Rule 46(b): at most 16 characters, e.g. RK/2627/000001 (prefix capped at 4).
		var invoiceNo = "%s/%s/%06d".formatted(prefix, fy, next);
		var total = jdbc.sql("select total from sales_order where id = ?").param(orderId).query(BigDecimal.class).single();
		// shortcut: IRN and e-way bill numbers are generated locally; call the GSP sandbox (story S9.2) before pilot.
		var irn = sha256(tenantId + invoiceNo);
		var eway = total.compareTo(EWAY_THRESHOLD) > 0
				? String.valueOf(100_000_000_000L + Math.abs(RANDOM.nextLong()) % 900_000_000_000L) : null;
		jdbc.sql("insert into invoice(id, tenant_id, order_id, invoice_no, fy, irn, eway_bill_no) values (?,?,?,?,?,?,?)")
			.params(UUID.randomUUID(), tenantId, orderId, invoiceNo, fy, irn, eway)
			.update();
		var lotId = jdbc.sql("select lot_id from sales_order where id = ?").param(orderId).query(UUID.class).single();
		inventory.sellLot(tenantId, lotId, orderId);
		return workOrder(orderId);
	}

	@Transactional(readOnly = true)
	public InvoiceView invoice(UUID orderId) {
		return jdbc.sql("""
				select i.invoice_no, i.issued_at, i.irn, i.eway_bill_no, t.legal_name as seller_name,
				       w.gstin as seller_gstin, w.name as seller_warehouse, w.state_code as seller_state,
				       u.name as buyer_name, u.business_name as buyer_business, u.gstin as buyer_gstin,
				       u.city as buyer_city, o.order_no, l.code as lot_code, l.title as lot_title, l.piece_count,
				       (select string_agg(distinct coalesce(ca.hsn, '6309'), ', ') from handling_unit h2
				        join sort_bucket b on b.id = h2.bucket_id join category ca on ca.id = b.category_id
				        where h2.lot_id = l.id) as hsn,
				       o.amount as taxable, o.gst_rate, o.cgst, o.sgst, o.igst, o.total, o.transporter, o.vehicle_no
				from invoice i join sales_order o on o.id = i.order_id join lot l on l.id = o.lot_id
				join app_user u on u.id = o.buyer_user_id join tenant t on t.id = o.tenant_id
				join lateral (select w.* from handling_unit h join warehouse w on w.id = h.warehouse_id
				              where h.lot_id = l.id limit 1) w on true
				where o.id = ?""").param(orderId).query(InvoiceView.class).optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No invoice yet for this order"));
	}

	/** Compact Indian financial year label, April to March: 2026-10-15 is "2627". */
	static String financialYear(LocalDate d) {
		int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
		return String.format("%02d%02d", start % 100, (start + 1) % 100);
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
