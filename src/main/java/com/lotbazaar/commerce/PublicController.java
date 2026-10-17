package com.lotbazaar.commerce;

import com.lotbazaar.tenancy.TenantDirectory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class PublicController {

	private final TenantDirectory tenants;

	PublicController(TenantDirectory tenants) {
		this.tenants = tenants;
	}

	/** Storefronts a buyer can register with. */
	@GetMapping("/api/public/storefronts")
	List<TenantDirectory.TenantInfo> storefronts() {
		return tenants.active();
	}

}
