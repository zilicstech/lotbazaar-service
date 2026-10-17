package com.lotbazaar.tenancy;

import java.util.Optional;
import java.util.UUID;

/** Current tenant for the running request or job. Applied to the DB per transaction. */
public final class TenantContext {

	private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static Optional<UUID> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	public static void set(UUID tenantId) {
		CURRENT.set(tenantId);
	}

	public static void clear() {
		CURRENT.remove();
	}

}
