package com.lotbazaar.workflow;

import com.lotbazaar.TestcontainersConfiguration;
import com.lotbazaar.commerce.ClaimService;
import com.lotbazaar.commerce.FulfilmentService;
import com.lotbazaar.commerce.OfferService;
import com.lotbazaar.commerce.OrderService;
import com.lotbazaar.identity.UserService;
import com.lotbazaar.inventory.InventoryService;
import com.lotbazaar.lots.LotService;
import com.lotbazaar.masterdata.MasterDataService;
import com.lotbazaar.tenancy.TenantDirectory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared fixtures: each test builds its own tenant so tests never see each other's rows. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class WorkflowTestBase {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MasterDataService masters;

	@Autowired
	InventoryService inventory;

	@Autowired
	LotService lots;

	@Autowired
	OrderService orders;

	@Autowired
	OfferService offers;

	@Autowired
	FulfilmentService fulfilment;

	@Autowired
	ClaimService claims;

	@Autowired
	UserService users;

	@Autowired
	TransactionTemplate tx;

	/** Seller in Delhi (state 07). */
	record Shop(UUID tenant, UUID warehouse, UUID brand, UUID category) {
	}

	Shop shop() {
		var t = UUID.randomUUID();
		jdbc.sql("insert into tenant(id, code, legal_name) values (?,?,?)")
			.params(t, "t" + t.toString().substring(0, 8) + "-x", "Test Seller")
			.update();
		return as(t, () -> {
			var wh = masters.addWarehouse(t, "W1", "Okhla", "07", "07AAACR5055K1Z7");
			inventory.addBins(t, wh, List.of("A-01-01", "A-01-02"));
			return new Shop(t, wh, masters.addBrand(t, "HM", "H&M"), masters.addCategory(t, "TSHIRT", "Tees", "6109"));
		});
	}

	/** A dock-checked container, open for sorting. */
	UUID container(Shop s) {
		return as(s.tenant(), () -> {
			var c = inventory.createContainer(s.tenant(), new InventoryService.NewContainer("C-" + UUID.randomUUID(),
					"Supplier", s.warehouse(), new BigDecimal("100000"), 1000, new BigDecimal("300")));
			inventory.dockCheck(c, new InventoryService.DockCheck(10, new BigDecimal("301")));
			return c;
		});
	}

	InventoryService.CartonView sorted(Shop s, UUID container, int pieces, String mrp) {
		return as(s.tenant(), () -> inventory.addCarton(s.tenant(), container, new InventoryService.NewCarton(
				s.brand(), s.category(), "M", "A", pieces, pieces * 300, new BigDecimal(mrp), Map.of("M", pieces))));
	}

	InventoryService.CartonView shelved(Shop s, UUID container, int pieces, String mrp) {
		var c = sorted(s, container, pieces, mrp);
		return as(s.tenant(), () -> inventory.putaway(s.tenant(), c.lpn(), "A-01-01"));
	}

	/** Listed lot of the given cartons' MRP at 20% Buy Now; offers from 10% when enabled. */
	UUID listedLot(Shop s, boolean acceptOffers, String... cartonMrps) {
		var c = container(s);
		var ids = java.util.Arrays.stream(cartonMrps).map(m -> shelved(s, c, 10, m).id()).toList();
		return as(s.tenant(), () -> {
			var lot = lots.create(s.tenant(), new LotService.NewLot("Lot", null, ids));
			lots.list(lot.id(), new LotService.Listing(new BigDecimal("20"), acceptOffers,
					acceptOffers ? new BigDecimal("10") : null));
			return lot.id();
		});
	}

	/** Approved buyer; the GSTIN's first two digits are the buyer's state (07 = Delhi, 08 = Rajasthan). */
	UUID buyer(Shop s, String stateCode) {
		return as(s.tenant(), () -> users.createBuyer(s.tenant(), new UserService.BuyerRegistration("B",
				UUID.randomUUID() + "@x.test", "secret1", "Shop", stateCode + "ABCDE1234F1Z5", "City", null),
				"APPROVED"));
	}

	/** Checkout + successful payment. */
	UUID paidOrder(Shop s, UUID buyer, UUID lot) {
		return as(s.tenant(), () -> {
			var o = orders.checkout(s.tenant(), buyer, lot);
			orders.pay(buyer, o.id(), "UPI", true);
			return o.id();
		});
	}

	/** Paid, all cartons scanned, packed at the expected weight. */
	UUID packedOrder(Shop s, UUID buyer, UUID lot) {
		var id = paidOrder(s, buyer, lot);
		return as(s.tenant(), () -> {
			for (var c : inventory.cartonsOfLot(lot)) {
				fulfilment.scan(s.tenant(), id, c.lpn());
			}
			fulfilment.pack(id, new FulfilmentService.Pack(fulfilment.workOrder(id).expectedKg(), false));
			return id;
		});
	}

	UUID deliveredOrder(Shop s, UUID buyer, UUID lot) {
		var id = packedOrder(s, buyer, lot);
		return as(s.tenant(), () -> {
			fulfilment.dispatch(s.tenant(), id, new FulfilmentService.Dispatch("VRL", "DL01AB1234"));
			claims.confirmDelivery(buyer, id);
			return id;
		});
	}

	static <T> T as(UUID tenant, Supplier<T> work) {
		return TenantDirectory.as(tenant, work);
	}

	static void run(UUID tenant, Runnable work) {
		TenantDirectory.as(tenant, () -> {
			work.run();
			return null;
		});
	}

	/** Raw SQL in a transaction, so the tenant-aware transaction manager applies the tenant for RLS. */
	<T> T sql(UUID tenant, Supplier<T> work) {
		return TenantDirectory.as(tenant, () -> tx.execute(status -> work.get()));
	}

	static void assertStatus(int status, String messagePart, ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
			org.assertj.core.api.Assertions.assertThat(e.getStatusCode().value()).isEqualTo(status);
			org.assertj.core.api.Assertions.assertThat(e.getReason()).contains(messagePart);
		});
	}

}
