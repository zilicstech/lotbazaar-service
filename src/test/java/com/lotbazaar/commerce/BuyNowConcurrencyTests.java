package com.lotbazaar.commerce;

import com.lotbazaar.TestcontainersConfiguration;
import com.lotbazaar.identity.UserService;
import com.lotbazaar.inventory.InventoryService;
import com.lotbazaar.lots.LotService;
import com.lotbazaar.masterdata.MasterDataService;
import com.lotbazaar.tenancy.TenantDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BuyNowConcurrencyTests {

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
	UserService users;

	@Test
	void manyBuyersOneLotExactlyOneOrder() throws Exception {
		var t = UUID.randomUUID();
		jdbc.sql("insert into tenant(id, code, legal_name) values (?,?,?)").params(t, "t-" + t, "T").update();
		var lotId = TenantDirectory.as(t, () -> {
			var wh = masters.addWarehouse(t, "W1", "W1", "07");
			var brand = masters.addBrand(t, "HM", "H&M");
			var cat = masters.addCategory(t, "TSHIRT", "Tees");
			var c = inventory.createContainer(t, new InventoryService.NewContainer("C1", "S", wh, BigDecimal.TEN, 10,
					BigDecimal.ONE));
			inventory.addBins(t, wh, List.of("A-01-01"));
			inventory.dockCheck(c, new InventoryService.DockCheck(1, BigDecimal.ONE));
			var carton = inventory.addCarton(t, c, new InventoryService.NewCarton(brand, cat, "M", "A", 10, 3000,
					new BigDecimal("7990"), Map.of("M", 6, "L", 4)));
			inventory.putaway(t, carton.lpn(), "A-01-01");
			var lot = lots.create(t, new LotService.NewLot("Lot", null, List.of(carton.id())));
			lots.list(lot.id(), new LotService.Listing(new BigDecimal("20"), false, null));
			return lot.id();
		});
		var buyers = new ArrayList<UUID>();
		for (int i = 0; i < 20; i++) {
			int n = i;
			buyers.add(TenantDirectory.as(t, () -> users.createBuyer(t, new UserService.BuyerRegistration("B" + n,
					"b" + n + "-" + t + "@x.test", "secret1", "Shop", "08ABCDE1234F1Z5", "Jaipur", null), "APPROVED")));
		}

		var pool = Executors.newFixedThreadPool(20);
		var results = new ArrayList<Future<Boolean>>();
		for (var b : buyers) {
			Callable<Boolean> buy = () -> {
				try {
					TenantDirectory.as(t, () -> orders.checkout(t, b, lotId));
					return true;
				}
				catch (RuntimeException e) {
					return false;
				}
			};
			results.add(pool.submit(buy));
		}
		int wins = 0;
		for (var f : results) {
			wins += f.get() ? 1 : 0;
		}
		pool.shutdown();

		assertThat(wins).isEqualTo(1);
		TenantDirectory.as(t, () -> {
			assertThat(orders.orders()).hasSize(1);
			assertThat(inventory.ledgerDrift()).isZero();
			assertThat(lots.lot(lotId).status()).isEqualTo("RESERVED");
			return null;
		});
	}

}
