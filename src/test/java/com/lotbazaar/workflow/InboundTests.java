package com.lotbazaar.workflow;

import com.lotbazaar.inventory.InventoryService;
import com.lotbazaar.lots.LotService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Steps 1–6: dock check, sorting, putaway, lot photos. */
class InboundTests extends WorkflowTestBase {

	@Test
	void sortingOnlyBetweenDockCheckAndFinish() {
		var s = shop();
		var c = as(s.tenant(), () -> inventory.createContainer(s.tenant(), new InventoryService.NewContainer("C-1",
				"Sup", s.warehouse(), BigDecimal.TEN, 500, new BigDecimal("100"))));
		var carton = new InventoryService.NewCarton(s.brand(), s.category(), "M", "A", 10, 3000,
				new BigDecimal("7990"), Map.of("M", 10));
		assertStatus(409, "dock check", () -> as(s.tenant(), () -> inventory.addCarton(s.tenant(), c, carton)));

		var checked = as(s.tenant(), () -> inventory.dockCheck(c, new InventoryService.DockCheck(8, new BigDecimal("104"))));
		assertThat(checked.status()).isEqualTo("SORTING");
		assertThat(checked.actualKg()).isEqualByComparingTo("104");
		assertThat(as(s.tenant(), () -> inventory.addCarton(s.tenant(), c, carton)).status()).isEqualTo("SORTED");

		assertThat(as(s.tenant(), () -> inventory.finishSorting(c)).status()).isEqualTo("SORTED");
		assertStatus(409, "already finished", () -> as(s.tenant(), () -> inventory.addCarton(s.tenant(), c, carton)));
		assertStatus(409, "just arrived", () -> as(s.tenant(),
				() -> inventory.dockCheck(c, new InventoryService.DockCheck(8, BigDecimal.ONE))));
	}

	@Test
	void putawayMakesCartonSellable() {
		var s = shop();
		var c = container(s);
		var carton = sorted(s, c, 10, "7990");
		assertStatus(409, "no longer available", () -> as(s.tenant(),
				() -> lots.create(s.tenant(), new LotService.NewLot("L", null, List.of(carton.id())))));
		assertStatus(404, "Bin Z-99", () -> as(s.tenant(), () -> inventory.putaway(s.tenant(), carton.lpn(), "Z-99")));

		var shelved = as(s.tenant(), () -> inventory.putaway(s.tenant(), carton.lpn().toLowerCase(), "a-01-02"));
		assertThat(shelved.status()).isEqualTo("AVAILABLE");
		assertThat(shelved.bin()).isEqualTo("A-01-02");
		assertStatus(409, "already shelved", () -> as(s.tenant(), () -> inventory.putaway(s.tenant(), carton.lpn(), "A-01-01")));
		assertThat(as(s.tenant(), () -> inventory.ledgerDrift())).isZero();
	}

	@Test
	void lotCannotMixCartonsFromTwoWarehouses() {
		var s = shop();
		var delhiCarton = shelved(s, container(s), 10, "7990");
		var mumbai = as(s.tenant(), () -> {
			var wh = masters.addWarehouse(s.tenant(), "W2", "Bhiwandi", "27", "27AAACR5055K1Z3");
			inventory.addBins(s.tenant(), wh, List.of("A-01-01"));
			var c = inventory.createContainer(s.tenant(), new InventoryService.NewContainer("C-M", "Sup", wh,
					BigDecimal.TEN, 10, BigDecimal.ONE));
			inventory.dockCheck(c, new InventoryService.DockCheck(1, BigDecimal.ONE));
			return c;
		});
		var mumbaiCarton = shelved(s, mumbai, 10, "7990");
		assertStatus(409, "one warehouse", () -> as(s.tenant(), () -> lots.create(s.tenant(),
				new LotService.NewLot("Mixed", null, List.of(delhiCarton.id(), mumbaiCarton.id())))));
	}

	@Test
	void lotPhotosAreValidatedAndTenantScoped() {
		var s = shop();
		var lot = listedLot(s, false, "7990");
		var jpeg = new byte[] { (byte) 0xFF, (byte) 0xD8, 1, 2 };
		assertStatus(400, "JPEG, PNG or WebP", () -> as(s.tenant(), () -> lots.addPhoto(s.tenant(), lot, "image/gif", jpeg)));
		assertStatus(400, "under 5 MB", () -> as(s.tenant(),
				() -> lots.addPhoto(s.tenant(), lot, "image/jpeg", new byte[5 * 1024 * 1024 + 1])));
		UUID first = null;
		for (int i = 0; i < 8; i++) {
			first = as(s.tenant(), () -> lots.addPhoto(s.tenant(), lot, "image/jpeg", jpeg));
		}
		assertStatus(400, "at most 8", () -> as(s.tenant(), () -> lots.addPhoto(s.tenant(), lot, "image/jpeg", jpeg)));
		assertThat(as(s.tenant(), () -> lots.lot(lot)).photos()).contains(first.toString());

		var photo = first;
		var other = shop();
		assertStatus(404, "Photo not found", () -> as(other.tenant(), () -> lots.photo(photo)));
	}

}
