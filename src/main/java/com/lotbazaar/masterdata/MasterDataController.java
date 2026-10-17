package com.lotbazaar.masterdata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MasterDataController {

	private final MasterDataService masters;

	MasterDataController(MasterDataService masters) {
		this.masters = masters;
	}

	@GetMapping("/api/seller/masters")
	MasterDataService.Masters masters() {
		return masters.masters();
	}

}
