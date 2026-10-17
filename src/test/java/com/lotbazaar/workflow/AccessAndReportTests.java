package com.lotbazaar.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff role boundaries (step roles) and the owner's recovery report (step 17). */
@AutoConfigureMockMvc
class AccessAndReportTests extends WorkflowTestBase {

	@Autowired
	MockMvc mvc;

	@Test
	void eachStaffRoleSeesOnlyItsScreens() throws Exception {
		var s = shop();
		var owner = token(s, "OWNER");
		var warehouse = token(s, "WAREHOUSE");
		var sales = token(s, "SALES");
		container(s);

		mvc.perform(get("/api/seller/offers").header("Authorization", owner)).andExpect(status().isOk());
		mvc.perform(get("/api/seller/offers").header("Authorization", warehouse)).andExpect(status().isForbidden());
		mvc.perform(get("/api/seller/buyers").header("Authorization", warehouse)).andExpect(status().isForbidden());
		mvc.perform(get("/api/seller/lots").header("Authorization", warehouse)).andExpect(status().isForbidden());
		mvc.perform(get("/api/seller/dashboard").header("Authorization", sales)).andExpect(status().isForbidden());
		mvc.perform(post("/api/seller/wh/putaway").header("Authorization", sales).contentType("application/json")
			.content("{}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/seller/containers").header("Authorization", warehouse).contentType("application/json")
			.content("{}")).andExpect(status().isForbidden());

		// Warehouse staff see containers but never the purchase cost.
		mvc.perform(get("/api/seller/containers").header("Authorization", warehouse))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].landedCost").doesNotExist());
		mvc.perform(get("/api/seller/containers").header("Authorization", owner))
			.andExpect(jsonPath("$[0].landedCost").value(100000.0));
	}

	@Test
	void recoveryAllocatesSalesToContainerByMrpShare() throws Exception {
		var s = shop();
		var b = buyer(s, "08");
		// Container with 2 cartons of MRP 10000 each; one listed at 20% (2000) and sold.
		var lot = listedLot(s, false, "10000");
		paidOrder(s, b, lot);
		mvc.perform(get("/api/seller/dashboard").header("Authorization", token(s, "OWNER")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.headline.gmv").value(2000.0))
			.andExpect(jsonPath("$.headline.ledgerDrift").value(0))
			// 2000 revenue / 100000 landed cost = 2.0%; sold MRP 10000 of 10000 in that container = 100%.
			.andExpect(jsonPath("$.recovery[0].revenue").value(2000.0))
			.andExpect(jsonPath("$.recovery[0].recoveryPct").value(2.0))
			.andExpect(jsonPath("$.recovery[0].sellThroughPct").value(100.0))
			.andExpect(jsonPath("$.funnel[0].status").value("PAID"));
	}

	@Test
	void lotJsonCarriesOfferFloorAmountForBuyersAndSellers() throws Exception {
		var s = shop();
		var lot = listedLot(s, true, "10000"); // floor 10% of 10000
		mvc.perform(get("/api/seller/lots/" + lot).header("Authorization", token(s, "SALES")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.offerFloorAmount").value(1000))
			.andExpect(jsonPath("$.photos").isArray());
	}

	private String token(Shop s, String role) {
		var email = role.toLowerCase() + "-" + UUID.randomUUID() + "@seller.test";
		users.createSeller(s.tenant(), email, "secret1", role, role);
		return "Bearer " + users.issueToken(users.authenticate(email, "secret1").orElseThrow());
	}

}
