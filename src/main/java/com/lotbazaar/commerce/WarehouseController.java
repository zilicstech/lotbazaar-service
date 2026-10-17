package com.lotbazaar.commerce;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Warehouse floor: pick, pack and dispatch queue (OWNER and WAREHOUSE). */
@RestController
@RequestMapping("/api/seller/wh/orders")
class WarehouseController {

	private final FulfilmentService fulfilment;

	WarehouseController(FulfilmentService fulfilment) {
		this.fulfilment = fulfilment;
	}

	@GetMapping
	List<FulfilmentService.WorkOrder> queue() {
		return fulfilment.queue();
	}

	@GetMapping("/{id}")
	FulfilmentService.WorkOrder order(@PathVariable UUID id) {
		return fulfilment.workOrder(id);
	}

	@GetMapping("/{id}/picks")
	List<FulfilmentService.PickLine> picks(@PathVariable UUID id) {
		return fulfilment.pickList(id);
	}

	@PostMapping("/{id}/scan")
	FulfilmentService.WorkOrder scan(@PathVariable UUID id, @RequestBody Map<String, String> r) {
		return fulfilment.scan(SellerSalesController.tenant(), id, r.get("lpn"));
	}

	@PostMapping("/{id}/pack")
	FulfilmentService.WorkOrder pack(@PathVariable UUID id, @RequestBody FulfilmentService.Pack p) {
		return fulfilment.pack(id, p);
	}

	@PostMapping("/{id}/dispatch")
	FulfilmentService.WorkOrder dispatch(@PathVariable UUID id, @RequestBody FulfilmentService.Dispatch d) {
		return fulfilment.dispatch(SellerSalesController.tenant(), id, d);
	}

}
