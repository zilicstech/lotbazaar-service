package com.lotbazaar.tenancy;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Platform-scoped tenant lookups (the tenant table has no RLS). */
@Service
@Transactional(readOnly = true)
public class TenantDirectory {

	public record TenantInfo(UUID id, String code, String legalName) {
	}

	private final JdbcClient jdbc;

	TenantDirectory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public TenantInfo byCode(String code) {
		return jdbc.sql("select id, code, legal_name from tenant where code = ? and status = 'ACTIVE'")
			.param(code)
			.query(TenantInfo.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown storefront " + code));
	}

	public TenantInfo byId(UUID id) {
		return jdbc.sql("select id, code, legal_name from tenant where id = ?").param(id).query(TenantInfo.class).single();
	}

	public List<TenantInfo> active() {
		return jdbc.sql("select id, code, legal_name from tenant where status = 'ACTIVE' order by legal_name")
			.query(TenantInfo.class)
			.list();
	}

	/** Runs work with the given tenant as the current tenant, restoring the previous one after. */
	public static <T> T as(UUID tenantId, java.util.function.Supplier<T> work) {
		var previous = TenantContext.current();
		TenantContext.set(tenantId);
		try {
			return work.get();
		}
		finally {
			previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
		}
	}

}
