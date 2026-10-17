package com.lotbazaar.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class TenancyConfig {

	@Bean
	PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
		return new TenantAwareTransactionManager() {
			{
				setEntityManagerFactory(emf);
			}
		};
	}

}
