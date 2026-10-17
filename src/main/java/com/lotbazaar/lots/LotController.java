package com.lotbazaar.lots;

import com.lotbazaar.inventory.InventoryService;
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

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/seller/lots")
class LotController {

	private final LotService lots;

	private final InventoryService inventory;

	LotController(LotService lots, InventoryService inventory) {
		this.lots = lots;
		this.inventory = inventory;
	}

	@GetMapping
	List<LotService.LotView> lots(@RequestParam(required = false) String status) {
		return lots.lots(status);
	}

	@GetMapping("/{id}")
	LotService.LotView lot(@PathVariable UUID id) {
		return lots.lot(id);
	}

	@GetMapping("/{id}/cartons")
	List<InventoryService.CartonView> cartons(@PathVariable UUID id) {
		return inventory.cartonsOfLot(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	LotService.LotView create(@RequestBody LotService.NewLot n) {
		return lots.create(tenant(), n);
	}

	private static UUID tenant() {
		return TenantContext.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
	}

	@PostMapping("/{id}/list")
	LotService.LotView list(@PathVariable UUID id, @RequestBody LotService.Listing l) {
		return lots.list(id, l);
	}

	@PostMapping(path = "/{id}/photos", consumes = "multipart/form-data")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, UUID> photo(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
		return Map.of("id", lots.addPhoto(tenant(), id, file.getContentType(), file.getBytes()));
	}

}
