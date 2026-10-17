package com.lotbazaar.inventory;

import com.lotbazaar.identity.CurrentUser;
import com.lotbazaar.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/seller")
class InventoryController {

	private final InventoryService inventory;

	InventoryController(InventoryService inventory) {
		this.inventory = inventory;
	}

	@GetMapping("/containers")
	List<InventoryService.ContainerView> containers() {
		return inventory.containers().stream().map(InventoryController::costFor).toList();
	}

	@GetMapping("/containers/{id}")
	InventoryService.ContainerView container(@PathVariable UUID id) {
		return costFor(inventory.container(id));
	}

	@PostMapping("/containers/{id}/dock-check")
	InventoryService.ContainerView dockCheck(@PathVariable UUID id, @RequestBody InventoryService.DockCheck d) {
		return costFor(inventory.dockCheck(id, d));
	}

	@PostMapping("/containers")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, UUID> create(@RequestBody InventoryService.NewContainer c) {
		return Map.of("id", inventory.createContainer(tenant(), c));
	}

	@GetMapping("/containers/{id}/cartons")
	List<InventoryService.CartonView> cartonsOf(@PathVariable UUID id) {
		return inventory.cartonsOfContainer(id);
	}

	@PostMapping("/containers/{id}/cartons")
	@ResponseStatus(HttpStatus.CREATED)
	InventoryService.CartonView addCarton(@PathVariable UUID id, @RequestBody InventoryService.NewCarton c) {
		return inventory.addCarton(tenant(), id, c);
	}

	@PostMapping("/containers/{id}/finish")
	InventoryService.ContainerView finish(@PathVariable UUID id) {
		return costFor(inventory.finishSorting(id));
	}

	@GetMapping("/wh/bins")
	List<InventoryService.BinView> bins() {
		return inventory.bins();
	}

	@PostMapping("/wh/putaway")
	InventoryService.CartonView putaway(@RequestBody Map<String, String> r) {
		return inventory.putaway(tenant(), r.get("lpn"), r.get("bin"));
	}

	@GetMapping("/cartons")
	List<InventoryService.CartonView> cartons(@RequestParam(required = false) String status) {
		return inventory.cartons(status);
	}

	@GetMapping("/stock")
	List<InventoryService.StockLine> stock() {
		return inventory.stock();
	}

	private static InventoryService.ContainerView costFor(InventoryService.ContainerView c) {
		return CurrentUser.hasRole("OWNER") ? c : c.withoutCost();
	}

	private static UUID tenant() {
		return TenantContext.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
	}

}
