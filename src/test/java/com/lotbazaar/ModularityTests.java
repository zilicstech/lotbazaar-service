package com.lotbazaar;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void modulesRespectBoundaries() {
		ApplicationModules.of(BackendApplication.class).verify();
	}

}
