package com.lotbazaar.demo;

import com.lotbazaar.commerce.ClaimService;
import com.lotbazaar.commerce.FulfilmentService;
import com.lotbazaar.commerce.OfferService;
import com.lotbazaar.commerce.OrderService;
import com.lotbazaar.identity.UserService;
import com.lotbazaar.identity.UserService.BuyerRegistration;
import com.lotbazaar.inventory.InventoryService;
import com.lotbazaar.inventory.InventoryService.DockCheck;
import com.lotbazaar.inventory.InventoryService.NewCarton;
import com.lotbazaar.inventory.InventoryService.NewContainer;
import com.lotbazaar.lots.LotService;
import com.lotbazaar.lots.LotService.Listing;
import com.lotbazaar.lots.LotService.NewLot;
import com.lotbazaar.masterdata.MasterDataService;
import com.lotbazaar.tenancy.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Seeds two tenants with data at every workflow stage: a container at the dock, one being sorted, sorted stock on
 * shelves, lots in every state, an open offer, and orders from paid through closed-with-claim. Runs only with the
 * "demo" profile and only on an empty database.
 */
@Component
@Profile("demo")
class DemoDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

	static final String PASSWORD = "demo123";

	private final JdbcClient jdbc;

	private final UserService users;

	private final MasterDataService masters;

	private final InventoryService inventory;

	private final LotService lots;

	private final OrderService orders;

	private final OfferService offers;

	private final FulfilmentService fulfilment;

	private final ClaimService claims;

	private final org.springframework.transaction.support.TransactionTemplate tx;

	private final Random random = new Random(42);

	private int nextBin;

	DemoDataSeeder(JdbcClient jdbc, UserService users, MasterDataService masters, InventoryService inventory,
			LotService lots, OrderService orders, OfferService offers, FulfilmentService fulfilment,
			ClaimService claims, org.springframework.transaction.support.TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.users = users;
		this.masters = masters;
		this.inventory = inventory;
		this.lots = lots;
		this.orders = orders;
		this.offers = offers;
		this.fulfilment = fulfilment;
		this.claims = claims;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (jdbc.sql("select count(*) from tenant").query(Integer.class).single() > 0) {
			log.info("Demo data already present; skipping seed");
			return;
		}
		var rk = tenant("rk-liquidators", "RK Brand Liquidators Pvt Ltd");
		var acme = tenant("acme-surplus", "Acme Surplus Traders");
		TenantDirectory.as(rk, () -> seedRk(rk));
		TenantDirectory.as(acme, () -> seedAcme(acme));
		log.info("Demo data seeded. Logins: owner@rk.demo, warehouse@rk.demo, sales@rk.demo, buyer@shop.demo");
	}

	private Void seedRk(UUID t) {
		users.createSeller(t, "owner@rk.demo", PASSWORD, "Rakesh Kapoor", "OWNER");
		users.createSeller(t, "warehouse@rk.demo", PASSWORD, "Suresh Yadav", "WAREHOUSE");
		users.createSeller(t, "sales@rk.demo", PASSWORD, "Neha Arora", "SALES");
		var delhi = masters.addWarehouse(t, "DEL-OKH", "Delhi – Okhla Phase 2", "07", "07AAACR5055K1Z7");
		var mumbai = masters.addWarehouse(t, "MUM-BHW", "Mumbai – Bhiwandi", "27", "27AAACR5055K1Z3");
		inventory.addBins(t, delhi, bins());
		inventory.addBins(t, mumbai, bins());
		var hm = masters.addBrand(t, "HM", "H&M");
		var zara = masters.addBrand(t, "ZARA", "Zara");
		var mango = masters.addBrand(t, "MNGO", "Mango");
		var levis = masters.addBrand(t, "LEVI", "Levi's");
		var tee = masters.addCategory(t, "TSHIRT", "T-Shirts", "6109");
		var shirt = masters.addCategory(t, "SHIRT", "Shirts", "6205");
		var jeans = masters.addCategory(t, "JEANS", "Jeans", "6203");
		var dress = masters.addCategory(t, "DRESS", "Dresses", "6204");
		var jacket = masters.addCategory(t, "JACKET", "Jackets", "6202");
		masters.addCategory(t, "SNEAKER", "Sneakers", "6404");

		// Container 1: fully sorted and shelved 75 days ago (shows up in stock aging).
		var c1 = container(t, "CN-2026-0412", "H&M EU overstock (Rotterdam)", delhi, "1850000", 3400, "1150");
		var hmTees = cartons(t, c1, hm, tee, "M", "A", 10, 120, 799, apparel());
		var hmTeesW = cartons(t, c1, hm, tee, "W", "A", 6, 100, 699, apparel());
		var hmShirts = cartons(t, c1, hm, shirt, "M", "B", 4, 80, 1299, apparel());
		var levisJeans = cartons(t, c1, levis, jeans, "M", "A", 6, 60, 2999, waist());
		finish(c1);
		// Raw SQL needs a transaction: the tenant-aware transaction manager is what applies the tenant for RLS.
		tx.executeWithoutResult(status -> {
			jdbc.sql("update handling_unit set created_at = now() - interval '75 days' where container_id = ?").param(c1).update();
			jdbc.sql("update container set received_at = now() - interval '78 days' where id = ?").param(c1).update();
		});

		// Container 2: sorted and shelved recently.
		var c2 = container(t, "CN-2026-0419", "Inditex & Mango season-end (Dubai)", delhi, "2400000", 1300, "560");
		var zaraDress = cartons(t, c2, zara, dress, "W", "A", 7, 80, 2290, apparel());
		var zaraJackets = cartons(t, c2, zara, jacket, "W", "B", 4, 40, 3990, apparel());
		var mangoTees = cartons(t, c2, mango, tee, "W", "A", 6, 100, 1190, apparel());
		finish(c2);

		// Container 3: dock-checked and half sorted; two cartons still waiting for putaway.
		var c3 = container(t, "CN-2026-0503", "Mango outlet surplus (Barcelona)", mumbai, "950000", 1200, "420");
		cartons(t, c3, mango, dress, "W", "A", 3, 70, 2590, apparel());
		sortOnly(t, c3, mango, shirt, "M", "B", 60, 1490);
		sortOnly(t, c3, mango, shirt, "M", "B", 58, 1490);

		// Container 4: just arrived at the dock. Start the demo here.
		inventory.createContainer(t, new NewContainer("CN-2026-0510", "H&M Nordic returns (Gothenburg)", delhi,
				new BigDecimal("1200000"), 1500, new BigDecimal("520")));

		var anil = users.createBuyer(t, new BuyerRegistration("Anil Sharma", "buyer@shop.demo", PASSWORD,
				"Sharma Fashion Hub", "08ABCDE1234F1Z5", "Jaipur", "rk-liquidators"), "APPROVED");
		var gupta = users.createBuyer(t, new BuyerRegistration("Mohit Gupta", "delhi@shop.demo", PASSWORD,
				"Gupta Garments", "07FGHIJ5678L1Z9", "Delhi", "rk-liquidators"), "APPROVED");
		users.createBuyer(t, new BuyerRegistration("Priya Nair", "newbuyer@shop.demo", PASSWORD, "Nair Trends",
				"32PQRST6789K1Z2", "Kochi", "rk-liquidators"), "PENDING");

		// On sale.
		lot(t, "H&M Men T-Shirts – Grade A, mixed sizes", hmTees.subList(0, 3), "18", "12",
				"Crew-neck basics, new with tags. Ideal for Tier-2 menswear counters.");
		lot(t, "Zara Women Dresses – Premium Grade A", zaraDress.subList(0, 2), "22", null,
				"Season-end Zara dresses, tags on. Mostly M and L.");
		var mixed = new ArrayList<UUID>();
		mixed.addAll(hmTeesW.subList(0, 2));
		mixed.addAll(mangoTees.subList(0, 2));
		mixed.add(hmShirts.get(0));
		var womensMix = lot(t, "Women's High-Street Mix – H&M + Mango", mixed, "16", "11",
				"Mixed women's tops from H&M and Mango, one carton of men's shirts (grade B).");
		lots.create(t, new NewLot("Zara Women Jackets – Grade B (draft)",
				"Minor defects: missing buttons on some pieces.", zaraJackets.subList(0, 2)));

		// Offers: one waiting for the seller, one accepted and awaiting payment.
		var w = lots.lot(womensMix);
		offers.make(t, gupta, womensMix, w.priceAmount().multiply(new BigDecimal("0.85")).setScale(0, java.math.RoundingMode.HALF_UP),
				"Can pick up from Okhla myself this week.");
		var levisLot = lot(t, "Levi's Men Jeans – Grade A", levisJeans.subList(0, 2), "25", "18",
				"Levi's 511/512 slim fits, waist 30–38.");
		var l = lots.lot(levisLot);
		var accepted = offers.make(t, anil, levisLot, l.priceAmount().multiply(new BigDecimal("0.9")).setScale(0,
				java.math.RoundingMode.HALF_UP), null);
		offers.accept(t, accepted.id());

		// Orders at every fulfilment stage.
		sell(t, anil, lot(t, "H&M Men T-Shirts – Grade A, starter lot", hmTees.subList(3, 5), "18", null, null), "PAID");
		sell(t, gupta, lot(t, "Mango Women Tees – Grade A", mangoTees.subList(2, 4), "17", null, null), "PACKED");
		sell(t, anil, lot(t, "Zara Women Dresses – 3 cartons", zaraDress.subList(2, 5), "21", null, null),
				"DISPATCHED");
		sell(t, gupta, lot(t, "H&M Men T-Shirts – bulk 3 cartons", hmTees.subList(5, 8), "17", null, null),
				"DELIVERED");
		var closed = sell(t, anil, lot(t, "H&M Women Tees – Grade A", hmTeesW.subList(2, 4), "17", null, null),
				"DELIVERED");
		var claim = claims.raise(t, anil, closed, new ClaimService.NewClaim("SHORT", 6,
				"Carton 2 had 6 pieces fewer than the label. Unboxing video sent on WhatsApp."));
		var order = orders.order(closed);
		claims.resolve(claim.id(), new ClaimService.Resolution(
				order.amount().multiply(BigDecimal.valueOf(6)).divide(BigDecimal.valueOf(order.pieceCount()), 0,
						java.math.RoundingMode.HALF_UP),
				"Short count confirmed on video. Credit note for 6 pieces at lot price."));
		return null;
	}

	private Void seedAcme(UUID t) {
		users.createSeller(t, "owner@acme.demo", PASSWORD, "Arjun Mehta", "OWNER");
		var wh = masters.addWarehouse(t, "SRT-01", "Surat – Sachin GIDC", "24", "24AAACA1111B1Z2");
		inventory.addBins(t, wh, bins());
		var brand = masters.addBrand(t, "MNGO", "Mango");
		var cat = masters.addCategory(t, "TSHIRT", "T-Shirts", "6109");
		var c = container(t, "ACME-0007", "Mango outlet surplus", wh, "600000", 300, "90");
		var cartons = cartons(t, c, brand, cat, "W", "B", 3, 100, 990, apparel());
		finish(c);
		lot(t, "Acme – Mango Women Tees Grade B", cartons, "12", null, null);
		return null;
	}

	private UUID tenant(String code, String name) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into tenant(id, code, legal_name) values (?,?,?)").params(id, code, name).update();
		return id;
	}

	private UUID container(UUID t, String ref, String supplier, UUID warehouse, String cost, int pieces, String kg) {
		var id = inventory.createContainer(t, new NewContainer(ref, supplier, warehouse, new BigDecimal(cost), pieces,
				new BigDecimal(kg)));
		inventory.dockCheck(id, new DockCheck(pieces / 100 + 2, new BigDecimal(kg).add(BigDecimal.valueOf(6))));
		return id;
	}

	private void finish(UUID container) {
		inventory.finishSorting(container);
	}

	private UUID lot(UUID t, String title, List<UUID> cartons, String pct, String offerFloor, String description) {
		var lot = lots.create(t, new NewLot(title, description, cartons));
		lots.list(lot.id(), new Listing(new BigDecimal(pct), offerFloor != null,
				offerFloor == null ? null : new BigDecimal(offerFloor)));
		return lot.id();
	}

	/** Walks one order from checkout to the given status using the real services. */
	private UUID sell(UUID t, UUID buyer, UUID lotId, String until) {
		var order = orders.checkout(t, buyer, lotId);
		orders.pay(buyer, order.id(), "UPI", true);
		if (until.equals("PAID")) {
			return order.id();
		}
		for (var c : inventory.cartonsOfLot(lotId)) {
			fulfilment.scan(t, order.id(), c.lpn());
		}
		fulfilment.pack(order.id(), new FulfilmentService.Pack(fulfilment.workOrder(order.id()).expectedKg(), false));
		if (until.equals("PACKED")) {
			return order.id();
		}
		fulfilment.dispatch(t, order.id(), new FulfilmentService.Dispatch("VRL Logistics", "DL01AB" + (1000 + random.nextInt(9000))));
		if (until.equals("DISPATCHED")) {
			return order.id();
		}
		claims.confirmDelivery(buyer, order.id());
		return order.id();
	}

	/** Sorts and shelves cartons, like the sorting station followed by putaway. */
	private List<UUID> cartons(UUID t, UUID container, UUID brand, UUID category, String gender, String grade,
			int count, int pieces, int avgMrp, List<String> sizes) {
		var ids = new ArrayList<UUID>();
		for (int i = 0; i < count; i++) {
			int n = pieces + random.nextInt(11) - 5;
			var carton = inventory.addCarton(t, container, carton(brand, category, gender, grade, n, avgMrp, sizes));
			var bins = bins();
			inventory.putaway(t, carton.lpn(), bins.get(nextBin++ % bins.size()));
			ids.add(carton.id());
		}
		return ids;
	}

	private UUID sortOnly(UUID t, UUID container, UUID brand, UUID category, String gender, String grade, int pieces,
			int avgMrp) {
		return inventory.addCarton(t, container, carton(brand, category, gender, grade, pieces, avgMrp, apparel())).id();
	}

	private NewCarton carton(UUID brand, UUID category, String gender, String grade, int n, int avgMrp,
			List<String> sizes) {
		var mrp = BigDecimal.valueOf((long) n * avgMrp + random.nextInt(2000));
		return new NewCarton(brand, category, gender, grade, n, n * (250 + random.nextInt(150)), mrp, tally(n, sizes));
	}

	private Map<String, Integer> tally(int pieces, List<String> sizes) {
		var weights = new int[] { 15, 30, 30, 20, 5 };
		var tally = new LinkedHashMap<String, Integer>();
		int left = pieces;
		for (int i = 0; i < sizes.size(); i++) {
			int n = i == sizes.size() - 1 ? left : Math.min(left, pieces * weights[i % weights.length] / 100);
			tally.put(sizes.get(i), n);
			left -= n;
		}
		return tally;
	}

	/** Bin codes: row A–C, rack 01–04, level 01–03 (e.g. B-02-03). */
	private static List<String> bins() {
		var bins = new ArrayList<String>();
		for (var row : List.of("A", "B", "C")) {
			for (int rack = 1; rack <= 4; rack++) {
				for (int level = 1; level <= 3; level++) {
					bins.add("%s-%02d-%02d".formatted(row, rack, level));
				}
			}
		}
		return bins;
	}

	private static List<String> apparel() {
		return List.of("S", "M", "L", "XL", "XXL");
	}

	private static List<String> waist() {
		return List.of("30", "32", "34", "36", "38");
	}

}
