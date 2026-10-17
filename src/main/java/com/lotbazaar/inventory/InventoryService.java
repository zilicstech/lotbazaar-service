package com.lotbazaar.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Containers, sorting into cartons (handling units), and the inventory ledger. */
@Service
@Transactional
public class InventoryService {

	public record NewContainer(String ref, String supplier, UUID warehouseId, BigDecimal landedCost,
			Integer expectedPieces, BigDecimal expectedKg) {
	}

	public record ContainerView(UUID id, String ref, String supplier, String warehouse, BigDecimal landedCost,
			String status, Instant receivedAt, int cartons, int pieces, BigDecimal totalMrp, Integer expectedPieces,
			BigDecimal expectedKg, Integer actualBales, BigDecimal actualKg, Instant checkedAt, Instant sortedAt,
			int unshelvedCartons, BigDecimal sortedKg) {

		/** Same view with the purchase cost hidden (only the owner sees what a container cost). */
		public ContainerView withoutCost() {
			return new ContainerView(id, ref, supplier, warehouse, null, status, receivedAt, cartons, pieces, totalMrp,
					expectedPieces, expectedKg, actualBales, actualKg, checkedAt, sortedAt, unshelvedCartons, sortedKg);
		}
	}

	public record DockCheck(Integer actualBales, BigDecimal actualKg) {
	}

	public record BinView(String code, String warehouse, int cartons) {
	}

	public record NewCarton(UUID brandId, UUID categoryId, String gender, String grade, int pieceCount, int weightG,
			BigDecimal totalMrp, Map<String, Integer> sizeTally) {
	}

	public record CartonView(UUID id, String lpn, String containerRef, String skuCode, String brand, String category,
			String gender, String grade, String mrpBand, int pieceCount, int weightG, BigDecimal totalMrp,
			String sizeTally, String status, UUID lotId, String warehouse, String bin, java.time.Instant createdAt) {
	}

	public record StockLine(String skuCode, String brand, String category, String gender, String grade,
			String mrpBand, int availableCartons, int availablePieces, BigDecimal availableMrp, int reservedCartons,
			int soldCartons) {
	}

	private static final String CARTON_SELECT = """
			select h.id, h.lpn, c.ref as container_ref, b.sku_code, br.name as brand, ca.name as category,
			       b.gender, b.grade, b.mrp_band, h.piece_count, h.weight_g, h.total_mrp,
			       h.size_tally::text as size_tally, h.status, h.lot_id, w.name as warehouse, bl.code as bin, h.created_at
			from handling_unit h
			join container c on c.id = h.container_id
			join warehouse w on w.id = h.warehouse_id
			left join bin_location bl on bl.id = h.bin_id
			join sort_bucket b on b.id = h.bucket_id
			join brand br on br.id = b.brand_id
			join category ca on ca.id = b.category_id
			""";

	private static final String CONTAINER_SELECT = """
			select c.id, c.ref, c.supplier, w.name as warehouse, c.landed_cost, c.status, c.received_at,
			       count(h.id)::int as cartons, coalesce(sum(h.piece_count), 0)::int as pieces,
			       coalesce(sum(h.total_mrp), 0) as total_mrp, c.expected_pieces, c.expected_kg, c.actual_bales,
			       c.actual_kg, c.checked_at, c.sorted_at,
			       count(h.id) filter (where h.status = 'SORTED')::int as unshelved_cartons,
			       round(coalesce(sum(h.weight_g), 0) / 1000.0, 1) as sorted_kg
			from container c join warehouse w on w.id = c.warehouse_id
			left join handling_unit h on h.container_id = c.id""";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	InventoryService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public UUID createContainer(UUID tenantId, NewContainer c) {
		if (c.ref() == null || c.ref().isBlank() || c.supplier() == null || c.supplier().isBlank()
				|| c.warehouseId() == null || c.landedCost() == null || c.landedCost().signum() < 0) {
			throw bad("Container ref, supplier, warehouse and a non-negative landed cost are required");
		}
		if (c.expectedPieces() == null || c.expectedPieces() <= 0 || c.expectedKg() == null
				|| c.expectedKg().signum() <= 0) {
			throw bad("Expected pieces and gross weight from the packing list are required");
		}
		var id = UUID.randomUUID();
		try {
			jdbc.sql("""
					insert into container(id, tenant_id, ref, supplier, warehouse_id, landed_cost, expected_pieces, expected_kg)
					values (?,?,?,?,?,?,?,?)""")
				.params(id, tenantId, c.ref().trim(), c.supplier().trim(), c.warehouseId(), c.landedCost(),
						c.expectedPieces(), c.expectedKg())
				.update();
		}
		catch (org.springframework.dao.DuplicateKeyException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Container " + c.ref() + " already exists");
		}
		return id;
	}

	@Transactional(readOnly = true)
	public List<ContainerView> containers() {
		return jdbc.sql("""
				%s
				group by c.id, w.name order by c.received_at desc""".formatted(CONTAINER_SELECT))
			.query(ContainerView.class)
			.list();
	}

	@Transactional(readOnly = true)
	public ContainerView container(UUID id) {
		return jdbc.sql(CONTAINER_SELECT + " where c.id = ? group by c.id, w.name")
			.param(id)
			.query(ContainerView.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Container not found"));
	}

	/** Dock check: actual bales and gross weight against the packing list; opens the container for sorting. */
	public ContainerView dockCheck(UUID containerId, DockCheck d) {
		if (d.actualBales() == null || d.actualBales() <= 0 || d.actualKg() == null || d.actualKg().signum() <= 0) {
			throw bad("Enter the number of bales/cartons received and the gross weight in kg");
		}
		if (jdbc.sql("""
				update container set status = 'SORTING', actual_bales = ?, actual_kg = ?, checked_at = now()
				where id = ? and status = 'ARRIVED'""").params(d.actualBales(), d.actualKg(), containerId).update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Dock check is only for containers that just arrived");
		}
		return container(containerId);
	}

	@Transactional(readOnly = true)
	public List<CartonView> cartonsOfContainer(UUID containerId) {
		return jdbc.sql(CARTON_SELECT + " where h.container_id = ? order by h.created_at desc")
			.param(containerId)
			.query(CartonView.class)
			.list();
	}

	@Transactional(readOnly = true)
	public List<CartonView> cartons(String status) {
		return jdbc.sql(CARTON_SELECT + " where (cast(? as varchar) is null or h.status = ?) order by b.sku_code, h.lpn")
			.params(status, status)
			.query(CartonView.class)
			.list();
	}

	@Transactional(readOnly = true)
	public List<CartonView> cartonsOfLot(UUID lotId) {
		return jdbc.sql(CARTON_SELECT + " where h.lot_id = ? order by h.lpn").param(lotId).query(CartonView.class).list();
	}

	/** Sorting step: one carton of one bucket. Derives the bucket SKU, issues an LPN and writes the ledger. */
	public CartonView addCarton(UUID tenantId, UUID containerId, NewCarton n) {
		validate(n);
		var container = jdbc.sql("select warehouse_id, status from container where id = ? for update")
			.param(containerId)
			.query((rs, i) -> new Object[] { rs.getObject(1, UUID.class), rs.getString(2) })
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Container not found"));
		if (!"SORTING".equals(container[1])) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "ARRIVED".equals(container[1])
					? "Do the dock check before sorting this container" : "Sorting is already finished for this container");
		}
		var brandCode = code("brand", n.brandId());
		var categoryCode = code("category", n.categoryId());
		var band = mrpBand(n.totalMrp().divide(BigDecimal.valueOf(n.pieceCount()), 2, RoundingMode.HALF_UP));
		var sku = String.join("-", brandCode, n.gender(), categoryCode, n.grade(), band);
		jdbc.sql("""
				insert into sort_bucket(id, tenant_id, sku_code, brand_id, category_id, gender, grade, mrp_band)
				values (?,?,?,?,?,?,?,?) on conflict (tenant_id, sku_code) do nothing""")
			.params(UUID.randomUUID(), tenantId, sku, n.brandId(), n.categoryId(), n.gender(), n.grade(), band)
			.update();
		var bucketId = jdbc.sql("select id from sort_bucket where sku_code = ?").param(sku).query(UUID.class).single();
		var id = UUID.randomUUID();
		var lpn = "LPN-" + Long.toString(Math.abs(RANDOM.nextLong()) % 2_821_109_907_456L, 36).toUpperCase();
		jdbc.sql("""
				insert into handling_unit(id, tenant_id, lpn, container_id, bucket_id, warehouse_id, piece_count,
				                          weight_g, total_mrp, size_tally)
				values (?,?,?,?,?,?,?,?,?, ?::jsonb)""")
			.params(id, tenantId, lpn, containerId, bucketId, container[0], n.pieceCount(), n.weightG(), n.totalMrp(),
					sizeJson(n.sizeTally()))
			.update();
		ledger(tenantId, id, "RECEIVE_SORT", 1, n.pieceCount(), "CONTAINER", containerId);
		return jdbc.sql(CARTON_SELECT + " where h.id = ?").param(id).query(CartonView.class).single();
	}

	public ContainerView finishSorting(UUID containerId) {
		if (jdbc.sql("update container set status = 'SORTED', sorted_at = now() where id = ? and status = 'SORTING'")
			.param(containerId)
			.update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a container being sorted can be finished");
		}
		return container(containerId);
	}

	public void addBins(UUID tenantId, UUID warehouseId, List<String> codes) {
		for (var code : codes) {
			jdbc.sql("insert into bin_location(id, tenant_id, warehouse_id, code) values (?,?,?,?)")
				.params(UUID.randomUUID(), tenantId, warehouseId, code)
				.update();
		}
	}

	@Transactional(readOnly = true)
	public List<BinView> bins() {
		return jdbc.sql("""
				select b.code, w.name as warehouse, count(h.id)::int as cartons
				from bin_location b join warehouse w on w.id = b.warehouse_id
				left join handling_unit h on h.bin_id = b.id and h.status <> 'SOLD'
				group by b.id, w.name order by w.name, b.code""").query(BinView.class).list();
	}

	/**
	 * Putaway: a sorted carton is placed in a bin of its own warehouse and becomes sellable. Writes a zero-quantity
	 * ledger row so every carton movement stays on the audit trail.
	 */
	public CartonView putaway(UUID tenantId, String lpn, String binCode) {
		if (lpn == null || lpn.isBlank() || binCode == null || binCode.isBlank()) {
			throw bad("Scan the carton label and the bin label");
		}
		var hu = jdbc.sql("select id, warehouse_id, status from handling_unit where lpn = ?")
			.param(lpn.trim().toUpperCase())
			.query((rs, i) -> new Object[] { rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3) })
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No carton with label " + lpn));
		var bin = jdbc.sql("select id from bin_location where warehouse_id = ? and code = ?")
			.params(hu[1], binCode.trim().toUpperCase())
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
					"Bin " + binCode + " does not exist in this carton's warehouse"));
		if (jdbc.sql("update handling_unit set status = 'AVAILABLE', bin_id = ? where id = ? and status = 'SORTED'")
			.params(bin, hu[0])
			.update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Carton " + lpn + " is already shelved (" + hu[2] + ")");
		}
		ledger(tenantId, (UUID) hu[0], "PUTAWAY", 0, 0, "BIN", bin);
		return jdbc.sql(CARTON_SELECT + " where h.id = ?").param(hu[0]).query(CartonView.class).single();
	}

	/** Reserves AVAILABLE cartons for a lot. All-or-nothing: any carton already taken fails the whole call. */
	public void reserveForLot(UUID tenantId, UUID lotId, List<UUID> cartonIds) {
		// One lot ships from one warehouse: its GSTIN and state decide the invoice and CGST/SGST vs IGST.
		if (jdbc.sql("select count(distinct warehouse_id) from handling_unit where id in (:ids)")
			.param("ids", cartonIds)
			.query(Integer.class)
			.single() > 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"A lot must come from one warehouse; pick cartons from a single warehouse");
		}
		int updated = jdbc.sql("""
				update handling_unit set status = 'RESERVED', lot_id = :lot
				where id in (:ids) and status = 'AVAILABLE'""")
			.param("lot", lotId)
			.param("ids", cartonIds)
			.update();
		if (updated != cartonIds.size()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Some cartons are no longer available; refresh and pick again");
		}
		for (var c : cartonIds) {
			ledger(tenantId, c, "RESERVE", 0, 0, "LOT", lotId);
		}
	}

	/** Moves a lot's reserved cartons to SOLD and writes the outbound ledger rows. */
	public void sellLot(UUID tenantId, UUID lotId, UUID orderId) {
		var cartons = jdbc.sql("""
				update handling_unit set status = 'SOLD' where lot_id = ? and status = 'RESERVED'
				returning id, piece_count""")
			.param(lotId)
			.query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getInt(2)))
			.list();
		if (cartons.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Lot has no reserved cartons");
		}
		for (var c : cartons) {
			ledger(tenantId, c.getKey(), "SELL", -1, -c.getValue(), "ORDER", orderId);
		}
	}

	@Transactional(readOnly = true)
	public List<StockLine> stock() {
		return jdbc.sql("""
				select b.sku_code, br.name as brand, ca.name as category, b.gender, b.grade, b.mrp_band,
				       count(*) filter (where h.status = 'AVAILABLE')::int as available_cartons,
				       coalesce(sum(h.piece_count) filter (where h.status = 'AVAILABLE'), 0)::int as available_pieces,
				       coalesce(sum(h.total_mrp) filter (where h.status = 'AVAILABLE'), 0) as available_mrp,
				       count(*) filter (where h.status = 'RESERVED')::int as reserved_cartons,
				       count(*) filter (where h.status = 'SOLD')::int as sold_cartons
				from sort_bucket b
				join brand br on br.id = b.brand_id join category ca on ca.id = b.category_id
				join handling_unit h on h.bucket_id = b.id
				group by b.id, br.name, ca.name order by b.sku_code""").query(StockLine.class).list();
	}

	/** On-hand pieces from the ledger must equal unsold cartons; returns the drift (0 = reconciled). */
	@Transactional(readOnly = true)
	public long ledgerDrift() {
		return jdbc.sql("""
				select (select coalesce(sum(piece_delta), 0) from inventory_ledger)
				     - (select coalesce(sum(piece_count), 0) from handling_unit where status <> 'SOLD')""")
			.query(Long.class)
			.single();
	}

	static String mrpBand(BigDecimal avgMrp) {
		int v = avgMrp.intValue();
		if (v < 500) {
			return "0_499";
		}
		if (v < 1000) {
			return "500_999";
		}
		if (v < 1500) {
			return "1000_1499";
		}
		if (v < 2500) {
			return "1500_2499";
		}
		return "2500_UP";
	}

	private void validate(NewCarton n) {
		if (n.brandId() == null || n.categoryId() == null) {
			throw bad("Brand and category are required");
		}
		if (n.gender() == null || !n.gender().matches("[MWKU]")) {
			throw bad("Gender must be M, W, K or U");
		}
		if (n.grade() == null || !n.grade().matches("[ABC]")) {
			throw bad("Grade must be A, B or C");
		}
		if (n.pieceCount() <= 0 || n.pieceCount() > 5000 || n.weightG() <= 0) {
			throw bad("Piece count (1-5000) and weight are required");
		}
		if (n.totalMrp() == null || n.totalMrp().signum() <= 0) {
			throw bad("Total MRP must be positive");
		}
		if (n.sizeTally() != null && !n.sizeTally().isEmpty()) {
			for (var e : n.sizeTally().entrySet()) {
				if (!e.getKey().matches("[A-Z0-9]{1,5}") || e.getValue() == null || e.getValue() < 0) {
					throw bad("Invalid size tally entry " + e.getKey());
				}
			}
			int sum = n.sizeTally().values().stream().mapToInt(Integer::intValue).sum();
			if (sum != n.pieceCount()) {
				throw bad("Size tally adds up to " + sum + " but piece count is " + n.pieceCount());
			}
		}
	}

	// Keys are validated to [A-Z0-9]{1,5} above, so plain concatenation is safe JSON.
	private static String sizeJson(Map<String, Integer> tally) {
		if (tally == null) {
			return "{}";
		}
		return tally.entrySet()
			.stream()
			.filter(e -> e.getValue() > 0)
			.map(e -> "\"" + e.getKey() + "\":" + e.getValue())
			.collect(Collectors.joining(",", "{", "}"));
	}

	private String code(String table, UUID id) {
		return jdbc.sql("select code from " + table + " where id = ?")
			.param(id)
			.query(String.class)
			.optional()
			.orElseThrow(() -> bad("Unknown " + table));
	}

	private void ledger(UUID tenantId, UUID huId, String event, int huDelta, int pieceDelta, String refType,
			UUID refId) {
		jdbc.sql("""
				insert into inventory_ledger(tenant_id, hu_id, event_type, hu_delta, piece_delta, ref_type, ref_id)
				values (?,?,?,?,?,?,?)""").params(tenantId, huId, event, huDelta, pieceDelta, refType, refId).update();
	}

	private static ResponseStatusException bad(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

}
