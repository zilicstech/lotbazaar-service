package com.lotbazaar.lots;

import com.lotbazaar.inventory.InventoryService;
import com.lotbazaar.inventory.InventoryService.CartonView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Unique lots built from specific cartons, with an auto-derived manifest (ADR-003). */
@Service
@Transactional
public class LotService {

	public record NewLot(String title, String description, List<UUID> cartonIds) {
	}

	public record Listing(BigDecimal pricePct, boolean acceptOffers, BigDecimal offerFloorPct) {
	}

	public record LotView(UUID id, String code, String title, String status,
			@com.fasterxml.jackson.annotation.JsonRawValue String manifest, int pieceCount,
			BigDecimal totalMrp, BigDecimal pricePct, BigDecimal priceAmount, Instant createdAt, Instant listedAt,
			String description, boolean acceptOffers, BigDecimal offerFloorPct,
			@com.fasterxml.jackson.annotation.JsonRawValue String photos) {

		/** Lowest offer the seller will look at, in rupees; null when offers are off. */
		@com.fasterxml.jackson.annotation.JsonProperty("offerFloorAmount")
		public BigDecimal offerFloorAmount() {
			return acceptOffers && offerFloorPct != null
					? totalMrp.multiply(offerFloorPct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP) : null;
		}
	}

	public record Photo(String contentType, byte[] bytes) {
	}

	static final int MAX_PHOTOS = 8;

	static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;

	private static final List<String> PHOTO_TYPES = List.of("image/jpeg", "image/png", "image/webp");

	private static final String LOT_SELECT = """
			select id, code, title, status, manifest::text as manifest, piece_count, total_mrp, price_pct, price_amount,
			       created_at, listed_at, description, accept_offers, offer_floor_pct,
			       (select coalesce(json_agg(p.id order by p.created_at), '[]') from lot_photo p
			        where p.lot_id = lot.id)::text as photos
			from lot""";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final InventoryService inventory;

	private final JsonMapper json;

	LotService(JdbcClient jdbc, InventoryService inventory, JsonMapper json) {
		this.jdbc = jdbc;
		this.inventory = inventory;
		this.json = json;
	}

	public LotView create(UUID tenantId, NewLot n) {
		if (n.title() == null || n.title().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lot title is required");
		}
		if (n.cartonIds() == null || n.cartonIds().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick at least one carton");
		}
		var ids = n.cartonIds().stream().distinct().toList();
		var id = UUID.randomUUID();
		var code = "LOT-" + Long.toString(Math.abs(RANDOM.nextLong()) % 60_466_176L, 36).toUpperCase();
		jdbc.sql("insert into lot(id, tenant_id, code, title, description) values (?,?,?,?,?)")
			.params(id, tenantId, code, n.title().trim(), n.description() == null ? null : n.description().trim())
			.update();
		inventory.reserveForLot(tenantId, id, ids);
		var cartons = inventory.cartonsOfLot(id);
		int pieces = cartons.stream().mapToInt(CartonView::pieceCount).sum();
		var mrp = cartons.stream().map(CartonView::totalMrp).reduce(BigDecimal.ZERO, BigDecimal::add);
		jdbc.sql("update lot set manifest = ?::jsonb, piece_count = ?, total_mrp = ? where id = ?")
			.params(json.writeValueAsString(manifest(cartons, pieces, mrp)), pieces, mrp, id)
			.update();
		return lot(id);
	}

	/** Puts a draft lot on sale at a % of total MRP (how buyers already compare lots). */
	public LotView list(UUID lotId, Listing l) {
		var pricePct = l.pricePct();
		if (pricePct == null || pricePct.compareTo(BigDecimal.ONE) < 0 || pricePct.compareTo(BigDecimal.valueOf(95)) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price must be between 1% and 95% of MRP");
		}
		var floor = l.acceptOffers() ? l.offerFloorPct() : null;
		if (l.acceptOffers() && (floor == null || floor.signum() <= 0 || floor.compareTo(pricePct) >= 0)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Lowest acceptable offer must be above 0% and below the Buy Now price");
		}
		int updated = jdbc.sql("""
				update lot set status = 'LISTED', price_pct = ?, price_amount = round(total_mrp * ? / 100), listed_at = now(),
				       accept_offers = ?, offer_floor_pct = ?
				where id = ? and status = 'DRAFT'""").params(pricePct, pricePct, l.acceptOffers(), floor, lotId).update();
		if (updated != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only draft lots can be listed");
		}
		return lot(lotId);
	}

	/** Guarded hold: true only for the single caller that moves LISTED to RESERVED (checkout or accepted offer). */
	public boolean reserve(UUID lotId) {
		return jdbc.sql("update lot set status = 'RESERVED' where id = ? and status = 'LISTED'").param(lotId).update() == 1;
	}

	/** Puts a held lot back on sale (hold expired or order cancelled). */
	public void release(UUID lotId) {
		jdbc.sql("update lot set status = 'LISTED' where id = ? and status = 'RESERVED'").param(lotId).update();
	}

	public boolean markSold(UUID lotId) {
		return jdbc.sql("update lot set status = 'SOLD' where id = ? and status = 'RESERVED'").param(lotId).update() == 1;
	}

	public UUID addPhoto(UUID tenantId, UUID lotId, String contentType, byte[] bytes) {
		if (contentType == null || !PHOTO_TYPES.contains(contentType)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Photos must be JPEG, PNG or WebP");
		}
		if (bytes.length == 0 || bytes.length > MAX_PHOTO_BYTES) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each photo must be under 5 MB");
		}
		lot(lotId);
		if (jdbc.sql("select count(*) from lot_photo where lot_id = ?").param(lotId).query(Integer.class).single() >= MAX_PHOTOS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A lot can have at most " + MAX_PHOTOS + " photos");
		}
		var id = UUID.randomUUID();
		// shortcut: photos live in Postgres bytea; move to S3 + CDN before the pilot (architecture.md, files).
		jdbc.sql("insert into lot_photo(id, tenant_id, lot_id, content_type, bytes) values (?,?,?,?,?)")
			.params(id, tenantId, lotId, contentType, bytes)
			.update();
		return id;
	}

	@Transactional(readOnly = true)
	public Photo photo(UUID photoId) {
		return jdbc.sql("select content_type, bytes from lot_photo where id = ?")
			.param(photoId)
			.query(Photo.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
	}

	@Transactional(readOnly = true)
	public List<LotView> lots(String status) {
		return jdbc.sql(LOT_SELECT + " where (cast(? as varchar) is null or status = ?) order by created_at desc")
			.params(status, status)
			.query(LotView.class)
			.list();
	}

	@Transactional(readOnly = true)
	public LotView lot(UUID id) {
		return jdbc.sql(LOT_SELECT + " where id = ?")
			.param(id)
			.query(LotView.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lot not found"));
	}

	private Map<String, Object> manifest(List<CartonView> cartons, int pieces, BigDecimal mrp) {
		var m = new LinkedHashMap<String, Object>();
		m.put("cartons", cartons.size());
		m.put("pieces", pieces);
		m.put("totalMrp", mrp);
		m.put("avgMrp", mrp.divide(BigDecimal.valueOf(pieces), 0, RoundingMode.HALF_UP));
		m.put("weightKg", BigDecimal.valueOf(cartons.stream().mapToInt(CartonView::weightG).sum())
			.divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP));
		m.put("grades", cartons.stream().map(CartonView::grade).distinct().sorted().toList());
		m.put("brandMix", mix(cartons, CartonView::brand, pieces));
		m.put("categoryMix", mix(cartons, CartonView::category, pieces));
		m.put("genderMix", mix(cartons, CartonView::gender, pieces));
		var sizes = new TreeMap<String, Integer>();
		for (var c : cartons) {
			json.readValue(c.sizeTally(), new tools.jackson.core.type.TypeReference<Map<String, Integer>>() {
			}).forEach((k, v) -> sizes.merge(k, v, Integer::sum));
		}
		m.put("sizeCurve", sizes);
		m.put("skus", cartons.stream().map(CartonView::skuCode).distinct().sorted().toList());
		m.put("containers", cartons.stream().map(CartonView::containerRef).distinct().sorted().toList());
		m.put("lpns", cartons.stream().map(CartonView::lpn).sorted().toList());
		return m;
	}

	// Share of pieces per key, in whole percent, largest first.
	private static Map<String, Long> mix(List<CartonView> cartons, Function<CartonView, String> key, int pieces) {
		return cartons.stream()
			.collect(Collectors.groupingBy(key, Collectors.summingInt(CartonView::pieceCount)))
			.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
			.collect(Collectors.toMap(Map.Entry::getKey, e -> Math.round(100.0 * e.getValue() / pieces), (a, b) -> a,
					LinkedHashMap::new));
	}

}
