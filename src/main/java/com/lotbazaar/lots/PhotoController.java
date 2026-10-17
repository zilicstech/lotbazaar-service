package com.lotbazaar.lots;

import com.lotbazaar.tenancy.TenantContext;
import com.lotbazaar.tenancy.TenantDirectory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Lot photos for img tags, which cannot send a bearer token. The random photo id acts as a capability.
 * shortcut: unguessable-URL access; switch to signed, expiring, watermarked CDN URLs before the pilot.
 */
@RestController
class PhotoController {

	private final TenantDirectory tenants;

	private final LotService lots;

	PhotoController(TenantDirectory tenants, LotService lots) {
		this.tenants = tenants;
		this.lots = lots;
	}

	@GetMapping("/api/public/{tenantCode}/photos/{id}")
	ResponseEntity<byte[]> photo(@PathVariable String tenantCode, @PathVariable UUID id) {
		TenantContext.set(tenants.byCode(tenantCode).id()); // cleared by TenantFilter at request end
		var photo = lots.photo(id);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(photo.contentType()))
			.cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
			.body(photo.bytes());
	}

}
