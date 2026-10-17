package com.lotbazaar.commerce;

import com.lotbazaar.identity.CurrentUser;
import com.lotbazaar.lots.LotService;
import com.lotbazaar.tenancy.TenantContext;
import com.lotbazaar.tenancy.TenantDirectory;
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
import java.util.Map;
import java.util.UUID;

/** Buyer storefront of one tenant. The tenant comes from the path; the buyer must be approved by it. */
@RestController
@RequestMapping("/api/store/{tenantCode}")
class StoreController {

	record PayRequest(String method, boolean success) {
	}

	record OfferRequest(BigDecimal amount, String note) {
	}

	private final TenantDirectory tenants;

	private final LotService lots;

	private final OrderService orders;

	private final OfferService offers;

	private final ClaimService claims;

	private final FulfilmentService fulfilment;

	StoreController(TenantDirectory tenants, LotService lots, OrderService orders, OfferService offers,
			ClaimService claims, FulfilmentService fulfilment) {
		this.tenants = tenants;
		this.lots = lots;
		this.orders = orders;
		this.offers = offers;
		this.claims = claims;
		this.fulfilment = fulfilment;
	}

	@GetMapping("/access")
	Map<String, String> access(@PathVariable String tenantCode) {
		var tenant = enter(tenantCode);
		var status = orders.buyerStatus(buyer());
		return Map.of("tenant", tenant.legalName(), "status", status == null ? "NONE" : status);
	}

	@GetMapping("/lots")
	List<LotService.LotView> lots(@PathVariable String tenantCode) {
		enterApproved(tenantCode);
		return lots.lots("LISTED");
	}

	@GetMapping("/lots/{id}")
	LotService.LotView lot(@PathVariable String tenantCode, @PathVariable UUID id) {
		enterApproved(tenantCode);
		var lot = lots.lot(id);
		if ("DRAFT".equals(lot.status())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lot not found");
		}
		return lot;
	}

	@PostMapping("/lots/{id}/checkout")
	OrderService.OrderView checkout(@PathVariable String tenantCode, @PathVariable UUID id) {
		return orders.checkout(enter(tenantCode).id(), buyer(), id);
	}

	@PostMapping("/lots/{id}/offers")
	OfferService.OfferView offer(@PathVariable String tenantCode, @PathVariable UUID id, @RequestBody OfferRequest r) {
		return offers.make(enter(tenantCode).id(), buyer(), id, r.amount(), r.note());
	}

	@GetMapping("/offers")
	List<OfferService.OfferView> offers(@PathVariable String tenantCode) {
		enter(tenantCode);
		return offers.offersOfBuyer(buyer());
	}

	@PostMapping("/offers/{id}/accept-counter")
	OfferService.OfferView acceptCounter(@PathVariable String tenantCode, @PathVariable UUID id) {
		return offers.acceptCounter(enter(tenantCode).id(), buyer(), id);
	}

	@PostMapping("/offers/{id}/withdraw")
	OfferService.OfferView withdraw(@PathVariable String tenantCode, @PathVariable UUID id) {
		enter(tenantCode);
		return offers.withdraw(buyer(), id);
	}

	@GetMapping("/orders")
	List<OrderService.OrderView> orders(@PathVariable String tenantCode) {
		enter(tenantCode);
		return orders.ordersOfBuyer(buyer());
	}

	@GetMapping("/orders/{id}")
	OrderService.OrderView order(@PathVariable String tenantCode, @PathVariable UUID id) {
		enter(tenantCode);
		return orders.orderOfBuyer(buyer(), id);
	}

	@PostMapping("/orders/{id}/pay")
	OrderService.PaymentResult pay(@PathVariable String tenantCode, @PathVariable UUID id, @RequestBody PayRequest r) {
		enter(tenantCode);
		return orders.pay(buyer(), id, r.method(), r.success());
	}

	@PostMapping("/orders/{id}/delivered")
	OrderService.OrderView delivered(@PathVariable String tenantCode, @PathVariable UUID id) {
		enter(tenantCode);
		return claims.confirmDelivery(buyer(), id);
	}

	@PostMapping("/orders/{id}/no-issues")
	OrderService.OrderView noIssues(@PathVariable String tenantCode, @PathVariable UUID id) {
		enter(tenantCode);
		return claims.noIssues(buyer(), id);
	}

	@PostMapping("/orders/{id}/claim")
	ClaimService.ClaimView claim(@PathVariable String tenantCode, @PathVariable UUID id,
			@RequestBody ClaimService.NewClaim n) {
		return claims.raise(enter(tenantCode).id(), buyer(), id, n);
	}

	@GetMapping("/orders/{id}/claim")
	List<ClaimService.ClaimView> claimOf(@PathVariable String tenantCode, @PathVariable UUID id) {
		enter(tenantCode);
		orders.orderOfBuyer(buyer(), id);
		return claims.claims().stream().filter(c -> c.orderId().equals(id)).toList();
	}

	@GetMapping("/orders/{id}/invoice")
	FulfilmentService.InvoiceView invoice(@PathVariable String tenantCode, @PathVariable UUID id) {
		enter(tenantCode);
		orders.orderOfBuyer(buyer(), id);
		return fulfilment.invoice(id);
	}

	private static UUID buyer() {
		return CurrentUser.get().id();
	}

	private TenantDirectory.TenantInfo enter(String tenantCode) {
		var tenant = tenants.byCode(tenantCode);
		TenantContext.set(tenant.id()); // cleared by TenantFilter at request end
		return tenant;
	}

	private void enterApproved(String tenantCode) {
		enter(tenantCode);
		orders.requireApproved(buyer());
	}

}
