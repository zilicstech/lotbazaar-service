package com.lotbazaar.workflow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Steps 9–10: Buy Now hold, GST, mock payment; Make an Offer. */
class SalesTests extends WorkflowTestBase {

	@Test
	void gstIsSplitWithinStateAndIntegratedAcrossStates() {
		var s = shop();
		var local = as(s.tenant(), () -> orders.checkout(s.tenant(), buyer(s, "07"), listedLot(s, false, "10000")));
		assertThat(local.amount()).isEqualByComparingTo("2000");
		assertThat(local.cgst()).isEqualByComparingTo("50");
		assertThat(local.sgst()).isEqualByComparingTo("50");
		assertThat(local.igst()).isEqualByComparingTo("0");
		assertThat(local.total()).isEqualByComparingTo("2100");

		var outside = as(s.tenant(), () -> orders.checkout(s.tenant(), buyer(s, "08"), listedLot(s, false, "10000")));
		assertThat(outside.igst()).isEqualByComparingTo("100");
		assertThat(outside.cgst()).isEqualByComparingTo("0");
		assertThat(outside.total()).isEqualByComparingTo("2100");
		assertThat(outside.status()).isEqualTo("PAYMENT_PENDING");
	}

	@Test
	void failedPaymentCanBeRetriedAndSuccessSellsTheLot() {
		var s = shop();
		var b = buyer(s, "08");
		var lot = listedLot(s, false, "7990");
		var order = as(s.tenant(), () -> orders.checkout(s.tenant(), b, lot));
		assertThat(as(s.tenant(), () -> lots.lot(lot)).status()).isEqualTo("RESERVED");

		var failed = as(s.tenant(), () -> orders.pay(b, order.id(), "NETBANKING", false));
		assertThat(failed.success()).isFalse();
		assertThat(failed.order().status()).isEqualTo("PAYMENT_PENDING");

		var paid = as(s.tenant(), () -> orders.pay(b, order.id(), "UPI", true));
		assertThat(paid.order().status()).isEqualTo("PAID");
		assertThat(as(s.tenant(), () -> lots.lot(lot)).status()).isEqualTo("SOLD");
		assertThat(jdbc.sql("select count(*) from payment").query(Integer.class).single()).isZero(); // RLS: no tenant here
		assertThat(sql(s.tenant(), () -> jdbc.sql("select count(*) from payment where order_id = ?").param(order.id())
			.query(Integer.class).single())).isEqualTo(2);
		assertStatus(409, "paid", () -> as(s.tenant(), () -> orders.pay(b, order.id(), "UPI", true)));
	}

	@Test
	void expiredHoldRefusesPaymentAndReleasesTheLot() {
		var s = shop();
		var b = buyer(s, "08");
		var lot = listedLot(s, false, "7990");
		var order = as(s.tenant(), () -> orders.checkout(s.tenant(), b, lot));
		sql(s.tenant(), () -> jdbc.sql("update sales_order set hold_expires_at = now() - interval '1 minute' where id = ?")
			.param(order.id()).update());

		assertStatus(410, "hold expired", () -> as(s.tenant(), () -> orders.pay(b, order.id(), "UPI", true)));
		assertThat(as(s.tenant(), () -> orders.releaseExpiredHolds())).isEqualTo(1);
		assertThat(as(s.tenant(), () -> lots.lot(lot)).status()).isEqualTo("LISTED");
		assertThat(as(s.tenant(), () -> orders.order(order.id())).status()).isEqualTo("CANCELLED");
		assertStatus(410, "hold expired", () -> as(s.tenant(), () -> orders.pay(b, order.id(), "UPI", true)));

		var again = as(s.tenant(), () -> orders.checkout(s.tenant(), buyer(s, "08"), lot));
		assertThat(again.status()).isEqualTo("PAYMENT_PENDING");
	}

	@Test
	void offerRulesAndOnlyOneAcceptancePerLot() {
		var s = shop();
		var lot = listedLot(s, true, "10000"); // Buy Now 2000, offers from 1000
		var a = buyer(s, "08");
		var b = buyer(s, "08");
		assertStatus(400, "start at ₹1000", () -> as(s.tenant(), () -> offers.make(s.tenant(), a, lot, new BigDecimal("999"), null)));
		assertStatus(400, "use Buy Now", () -> as(s.tenant(), () -> offers.make(s.tenant(), a, lot, new BigDecimal("2000"), null)));

		var offerA = as(s.tenant(), () -> offers.make(s.tenant(), a, lot, new BigDecimal("1500"), "pls"));
		var offerB = as(s.tenant(), () -> offers.make(s.tenant(), b, lot, new BigDecimal("1600"), null));
		assertStatus(409, "already have an open offer",
				() -> as(s.tenant(), () -> offers.make(s.tenant(), a, lot, new BigDecimal("1700"), null)));

		var accepted = as(s.tenant(), () -> offers.accept(s.tenant(), offerB.id()));
		assertThat(accepted.status()).isEqualTo("ACCEPTED");
		var order = as(s.tenant(), () -> orders.order(accepted.orderId()));
		assertThat(order.amount()).isEqualByComparingTo("1600");
		assertThat(order.source()).isEqualTo("OFFER");
		assertStatus(409, "Only open offers", () -> as(s.tenant(), () -> offers.accept(s.tenant(), offerA.id())));
	}

	@Test
	void sellingALotClosesItsOtherOffers() {
		var s = shop();
		var lot = listedLot(s, true, "10000");
		var bidder = buyer(s, "08");
		var offer = as(s.tenant(), () -> offers.make(s.tenant(), bidder, lot, new BigDecimal("1500"), null));
		paidOrder(s, buyer(s, "08"), lot);
		assertThat(as(s.tenant(), () -> offers.offersOfBuyer(bidder)).get(0).status()).isEqualTo("EXPIRED");
		assertThat(offer.status()).isEqualTo("OPEN");
	}

	@Test
	void blockedBuyerCannotAcceptCounter() {
		var s = shop();
		var lot = listedLot(s, true, "10000");
		var a = buyer(s, "07");
		var offer = as(s.tenant(), () -> offers.make(s.tenant(), a, lot, new BigDecimal("1200"), null));
		as(s.tenant(), () -> offers.counter(offer.id(), new BigDecimal("1800")));
		run(s.tenant(), () -> orders.setBuyerStatus(a, "BLOCKED"));
		assertStatus(403, "blocked", () -> as(s.tenant(), () -> offers.acceptCounter(s.tenant(), a, offer.id())));
		assertThat(as(s.tenant(), () -> lots.lot(lot)).status()).isEqualTo("LISTED");
	}

	@Test
	void buyerAcceptsCounterAtCounterPrice() {
		var s = shop();
		var lot = listedLot(s, true, "10000");
		var a = buyer(s, "07");
		var offer = as(s.tenant(), () -> offers.make(s.tenant(), a, lot, new BigDecimal("1200"), null));
		assertStatus(400, "above the buyer's offer", () -> as(s.tenant(), () -> offers.counter(offer.id(), new BigDecimal("1100"))));
		as(s.tenant(), () -> offers.counter(offer.id(), new BigDecimal("1800")));
		assertStatus(404, "Offer not found",
				() -> as(s.tenant(), () -> offers.acceptCounter(s.tenant(), buyer(s, "07"), offer.id())));

		var done = as(s.tenant(), () -> offers.acceptCounter(s.tenant(), a, offer.id()));
		assertThat(as(s.tenant(), () -> orders.order(done.orderId())).amount()).isEqualByComparingTo("1800");
		assertThat(as(s.tenant(), () -> lots.lot(lot)).status()).isEqualTo("RESERVED");
	}

}
