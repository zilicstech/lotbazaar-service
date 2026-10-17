package com.lotbazaar.masterdata;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MasterDataService {

	public record Option(UUID id, String code, String name) {
	}

	public record Masters(List<Option> brands, List<Option> categories, List<Option> warehouses,
			List<String> grades, List<String> genders) {
	}

	private final JdbcClient jdbc;

	MasterDataService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public Masters masters() {
		return new Masters(jdbc.sql("select id, code, name from brand order by name").query(Option.class).list(),
				jdbc.sql("select id, code, name from category order by name").query(Option.class).list(),
				jdbc.sql("select id, code, name from warehouse order by name").query(Option.class).list(),
				List.of("A", "B", "C"), List.of("M", "W", "K", "U"));
	}

	public UUID addWarehouse(UUID tenantId, String code, String name, String stateCode) {
		return addWarehouse(tenantId, code, name, stateCode, null);
	}

	public UUID addWarehouse(UUID tenantId, String code, String name, String stateCode, String gstin) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into warehouse(id, tenant_id, code, name, state_code, gstin) values (?,?,?,?,?,?)")
			.params(id, tenantId, code, name, stateCode, gstin)
			.update();
		return id;
	}

	public UUID addBrand(UUID tenantId, String code, String name) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into brand(id, tenant_id, code, name) values (?,?,?,?)").params(id, tenantId, code, name).update();
		return id;
	}

	public UUID addCategory(UUID tenantId, String code, String name) {
		return addCategory(tenantId, code, name, null);
	}

	public UUID addCategory(UUID tenantId, String code, String name, String hsn) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into category(id, tenant_id, code, name, hsn) values (?,?,?,?,?)")
			.params(id, tenantId, code, name, hsn)
			.update();
		return id;
	}

}
