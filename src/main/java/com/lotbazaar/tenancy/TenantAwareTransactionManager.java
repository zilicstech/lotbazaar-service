package com.lotbazaar.tenancy;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.sql.PreparedStatement;

/**
 * Switches every transaction to the RLS-bound role and sets app.tenant_id. Both settings are
 * transaction-local, so nothing leaks to the next borrower of a pooled connection.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

	static final String APP_ROLE = "lotbazaar_app";

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		super.doBegin(transaction, definition);
		EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(getEntityManagerFactory());
		String tenant = TenantContext.current().map(Object::toString).orElse("");
		em.unwrap(Session.class).doWork(connection -> {
			try (PreparedStatement ps = connection
				.prepareStatement("select set_config('role', ?, true), set_config('app.tenant_id', ?, true)")) {
				ps.setString(1, APP_ROLE);
				ps.setString(2, tenant);
				ps.execute();
			}
		});
	}

}
