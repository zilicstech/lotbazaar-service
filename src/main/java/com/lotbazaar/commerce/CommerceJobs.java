package com.lotbazaar.commerce;

import com.lotbazaar.tenancy.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Timers per tenant: expired payment holds go back on sale; quiet delivered orders close after 72 hours. */
@Component
class CommerceJobs {

	private static final Logger log = LoggerFactory.getLogger(CommerceJobs.class);

	private final TenantDirectory tenants;

	private final OrderService orders;

	private final ClaimService claims;

	CommerceJobs(TenantDirectory tenants, OrderService orders, ClaimService claims) {
		this.tenants = tenants;
		this.orders = orders;
		this.claims = claims;
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
	void run() {
		for (var t : tenants.active()) {
			try {
				int released = TenantDirectory.as(t.id(), orders::releaseExpiredHolds);
				int closed = TenantDirectory.as(t.id(), claims::autoClose);
				if (released + closed > 0) {
					log.info("{}: released {} expired holds, auto-closed {} orders", t.code(), released, closed);
				}
			}
			catch (RuntimeException e) {
				log.warn("Commerce jobs failed for tenant {}", t.code(), e);
			}
		}
	}

}
