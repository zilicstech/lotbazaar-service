package com.lotbazaar.commerce;

import com.lotbazaar.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Seller console: orders, offers, buyers, claims, invoices. Role rules are in SecurityConfig by path. */
@RestController
@RequestMapping("/api/seller")
class SellerSalesController {

	record CounterRequest(BigDecimal amount) {
	}

	private final OrderService orders;

	private final OfferService offers;

	private final ClaimService claims;

	private final FulfilmentService fulfilment;

	SellerSalesController(OrderService orders, OfferService offers, ClaimService claims, FulfilmentService fulfilment) {
		this.orders = orders;
		this.offers = offers;
		this.claims = claims;
		this.fulfilment = fulfilment;
	}

	@GetMapping("/orders")
	List<OrderService.OrderView> orders() {
		return orders.orders();
	}

	@GetMapping("/orders/{id}")
	OrderService.OrderView order(@PathVariable UUID id) {
		return orders.order(id);
	}

	@GetMapping("/invoices/{orderId}")
	FulfilmentService.InvoiceView invoice(@PathVariable UUID orderId) {
		return fulfilment.invoice(orderId);
	}

	@GetMapping("/offers")
	List<OfferService.OfferView> offers() {
		return offers.offers();
	}

	@PostMapping("/offers/{id}/accept")
	OfferService.OfferView accept(@PathVariable UUID id) {
		return offers.accept(tenant(), id);
	}

	@PostMapping("/offers/{id}/counter")
	OfferService.OfferView counter(@PathVariable UUID id, @RequestBody CounterRequest r) {
		return offers.counter(id, r.amount());
	}

	@PostMapping("/offers/{id}/reject")
	OfferService.OfferView reject(@PathVariable UUID id) {
		return offers.reject(id);
	}

	@GetMapping("/buyers")
	List<OrderService.BuyerView> buyers() {
		return orders.buyers();
	}

	@PostMapping("/buyers/{id}/approve")
	void approve(@PathVariable UUID id) {
		orders.setBuyerStatus(id, "APPROVED");
	}

	@PostMapping("/buyers/{id}/block")
	void block(@PathVariable UUID id) {
		orders.setBuyerStatus(id, "BLOCKED");
	}

	@GetMapping("/claims")
	List<ClaimService.ClaimView> claims() {
		return claims.claims();
	}

	@PostMapping("/claims/{id}/resolve")
	ClaimService.ClaimView resolve(@PathVariable UUID id, @RequestBody ClaimService.Resolution r) {
		return claims.resolve(id, r);
	}

	@PostMapping("/claims/{id}/reject")
	ClaimService.ClaimView rejectClaim(@PathVariable UUID id, @RequestBody ClaimService.Resolution r) {
		return claims.reject(id, r);
	}

	static UUID tenant() {
		return TenantContext.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
	}

}
