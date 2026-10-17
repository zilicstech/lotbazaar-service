package com.lotbazaar.workflow;

import com.lotbazaar.commerce.ClaimService;
import com.lotbazaar.commerce.FulfilmentService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Steps 11–16: pick, pack, dispatch with invoice, delivery, claims. */
class FulfilmentTests extends WorkflowTestBase {

	@Test
	void pickRejectsForeignCartonsAndPackChecksWeight() {
		var s = shop();
		var b = buyer(s, "08");
		var lot = listedLot(s, false, "7990", "8990");
		var other = shelved(s, container(s), 10, "5000");
		var order = paidOrder(s, b, lot);
		var cartons = as(s.tenant(), () -> inventory.cartonsOfLot(lot));

		assertStatus(409, "Scan the cartons", () -> as(s.tenant(), () -> fulfilment.pack(order, new FulfilmentService.Pack(BigDecimal.TEN, false))));
		assertStatus(409, "NOT part of this order", () -> as(s.tenant(), () -> fulfilment.scan(s.tenant(), order, other.lpn())));
		var w = as(s.tenant(), () -> fulfilment.scan(s.tenant(), order, cartons.get(0).lpn()));
		assertThat(w.status()).isEqualTo("PICKING");
		assertThat(as(s.tenant(), () -> fulfilment.scan(s.tenant(), order, cartons.get(0).lpn())).picked()).isEqualTo(1);
		assertStatus(409, "Still to pick: " + cartons.get(1).lpn(),
				() -> as(s.tenant(), () -> fulfilment.pack(order, new FulfilmentService.Pack(BigDecimal.TEN, false))));

		as(s.tenant(), () -> fulfilment.scan(s.tenant(), order, cartons.get(1).lpn()));
		var expected = as(s.tenant(), () -> fulfilment.workOrder(order)).expectedKg(); // 2 x 10 pcs x 300 g = 6.0 kg
		assertThat(expected).isEqualByComparingTo("6.0");
		assertStatus(409, "10.0% off", () -> as(s.tenant(), () -> fulfilment.pack(order, new FulfilmentService.Pack(new BigDecimal("6.6"), false))));
		var packed = as(s.tenant(), () -> fulfilment.pack(order, new FulfilmentService.Pack(new BigDecimal("6.6"), true)));
		assertThat(packed.status()).isEqualTo("PACKED");
		assertThat(packed.dispatchKg()).isEqualByComparingTo("6.6");
		assertThat(as(s.tenant(), () -> fulfilment.queue())).extracting(FulfilmentService.WorkOrder::id).contains(order);
	}

	@Test
	void dispatchIssuesGaplessInvoicesAndSellsCartons() {
		var s = shop();
		var b = buyer(s, "07");
		var fy = fy(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
		String last = null;
		for (int i = 1; i <= 3; i++) {
			var lot = listedLot(s, false, i == 3 ? "300000" : "7990");
			var order = packedOrder(s, b, lot);
			assertStatus(400, "vehicle number", () -> as(s.tenant(), () -> fulfilment.dispatch(s.tenant(), order, new FulfilmentService.Dispatch("VRL", ""))));
			var w = as(s.tenant(), () -> fulfilment.dispatch(s.tenant(), order, new FulfilmentService.Dispatch("VRL", "dl01ab1234")));
			assertThat(w.invoiceNo()).endsWith("/" + fy + "/00000" + i);
			// CGST Rule 46(b) and the IRP allow at most 16 characters.
			assertThat(w.invoiceNo()).hasSizeLessThanOrEqualTo(16);
			assertThat(w.vehicleNo()).isEqualTo("DL01AB1234");
			var inv = as(s.tenant(), () -> fulfilment.invoice(order));
			assertThat(inv.hsn()).isEqualTo("6109");
			assertThat(inv.sellerGstin()).isEqualTo("07AAACR5055K1Z7");
			assertThat(inv.cgst()).isPositive();
			assertThat(inv.irn()).hasSize(64);
			// 300000 MRP at 20% = 60000 taxable > 50000: e-way bill required.
			assertThat(inv.ewayBillNo() != null).isEqualTo(i == 3);
			assertStatus(409, "Only packed", () -> as(s.tenant(), () -> fulfilment.dispatch(s.tenant(), order, new FulfilmentService.Dispatch("VRL", "X"))));
			last = inv.invoiceNo();
		}
		assertThat(last).endsWith("000003");
		assertThat(as(s.tenant(), () -> inventory.cartons("SOLD"))).hasSize(3);
		assertThat(as(s.tenant(), () -> inventory.ledgerDrift())).isZero();
	}

	@Test
	void claimsOnlyWithinWindowAndCreditCappedAtTotal() {
		var s = shop();
		var b = buyer(s, "08");
		var late = deliveredOrder(s, b, listedLot(s, false, "7990"));
		sql(s.tenant(), () -> jdbc.sql("update sales_order set delivered_at = now() - interval '73 hours' where id = ?")
			.param(late).update());
		assertStatus(409, "72-hour claim window", () -> as(s.tenant(),
				() -> claims.raise(s.tenant(), b, late, new ClaimService.NewClaim("SHORT", 2, "short"))));
		assertThat(as(s.tenant(), () -> claims.autoClose())).isEqualTo(1);
		assertThat(as(s.tenant(), () -> orders.order(late)).status()).isEqualTo("CLOSED");

		var order = deliveredOrder(s, b, listedLot(s, false, "7990"));
		assertStatus(400, "affected pieces", () -> as(s.tenant(),
				() -> claims.raise(s.tenant(), b, order, new ClaimService.NewClaim("SHORT", 99, "short"))));
		var claim = as(s.tenant(), () -> claims.raise(s.tenant(), b, order, new ClaimService.NewClaim("SHORT", 2, "2 short")));
		assertStatus(409, "has a claim", () -> as(s.tenant(), () -> claims.noIssues(b, order)));
		assertStatus(400, "Credit must be between", () -> as(s.tenant(),
				() -> claims.resolve(claim.id(), new ClaimService.Resolution(new BigDecimal("999999"), "x"))));
		var done = as(s.tenant(), () -> claims.resolve(claim.id(), new ClaimService.Resolution(new BigDecimal("320"), "ok")));
		assertThat(done.status()).isEqualTo("RESOLVED");
		assertThat(as(s.tenant(), () -> orders.order(order)).status()).isEqualTo("CLOSED");
		assertThat(as(s.tenant(), () -> orders.order(order)).claimStatus()).isEqualTo("RESOLVED");
	}

	@Test
	void happyBuyerClosesOrder() {
		var s = shop();
		var b = buyer(s, "08");
		var order = deliveredOrder(s, b, listedLot(s, false, "7990"));
		assertStatus(404, "Order not found", () -> as(s.tenant(), () -> claims.noIssues(buyer(s, "08"), order)));
		assertThat(as(s.tenant(), () -> claims.noIssues(b, order)).status()).isEqualTo("CLOSED");
	}

	private static String fy(LocalDate d) {
		int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
		return String.format("%02d%02d", start % 100, (start + 1) % 100);
	}

}
