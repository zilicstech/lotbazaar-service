package com.lotbazaar.tenancy;

import com.lotbazaar.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RowLevelSecurityTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	UUID tenantA = UUID.randomUUID();

	UUID tenantB = UUID.randomUUID();

	@BeforeEach
	void tenants() {
		for (UUID t : new UUID[] { tenantA, tenantB }) {
			jdbc.update("insert into tenant(id, code, legal_name) values (?, ?, ?)", t, t.toString(), "Test " + t);
		}
		inTenant(tenantA, () -> jdbc.update(
				"insert into warehouse(id, tenant_id, code, name, state_code) values (?, ?, 'DEL1', 'Delhi', '07')",
				UUID.randomUUID(), tenantA));
	}

	@AfterEach
	void clear() {
		TenantContext.clear();
	}

	@Test
	void tenantSeesOnlyOwnRows() {
		assertThat(inTenant(tenantA, this::warehouseCount)).isEqualTo(1);
		assertThat(inTenant(tenantB, this::warehouseCount)).isZero();
	}

	@Test
	void noTenantSeesNothing() {
		assertThat(inTenant(null, this::warehouseCount)).isZero();
	}

	@Test
	void cannotWriteIntoAnotherTenant() {
		assertThatThrownBy(() -> inTenant(tenantB, () -> jdbc.update(
				"insert into warehouse(id, tenant_id, code, name, state_code) values (?, ?, 'X', 'X', '27')",
				UUID.randomUUID(), tenantA)))
			.rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void settingsDoNotLeakAfterCommit() {
		inTenant(tenantA, this::warehouseCount);
		// Outside a managed transaction the tenant is gone, and the pool still runs as the RLS-bound role.
		assertThat(jdbc.queryForObject("select coalesce(current_setting('app.tenant_id', true), '')", String.class))
			.isEmpty();
		assertThat(jdbc.queryForObject("select current_user", String.class))
			.isEqualTo(TenantAwareTransactionManager.APP_ROLE);
		assertThat(warehouseCount()).isZero();
	}

	private Integer warehouseCount() {
		return jdbc.queryForObject("select count(*) from warehouse", Integer.class);
	}

	private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
		if (tenant == null) {
			TenantContext.clear();
		}
		else {
			TenantContext.set(tenant);
		}
		return tx.execute(s -> work.get());
	}

}
