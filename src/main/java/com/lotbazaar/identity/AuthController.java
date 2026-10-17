package com.lotbazaar.identity;

import com.lotbazaar.tenancy.TenantDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	record LoginResponse(String token, UserService.UserView user) {
	}

	private final UserService users;

	private final TenantDirectory tenants;

	AuthController(UserService users, TenantDirectory tenants) {
		this.users = users;
		this.tenants = tenants;
	}

	@PostMapping("/login")
	LoginResponse login(@Valid @RequestBody LoginRequest r) {
		var user = users.authenticate(r.email(), r.password())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
		return new LoginResponse(users.issueToken(user), user);
	}

	@PostMapping("/register-buyer")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, UUID> register(@RequestBody UserService.BuyerRegistration r) {
		users.validate(r);
		var tenant = tenants.byCode(r.tenantCode());
		return Map.of("id", TenantDirectory.as(tenant.id(), () -> users.createBuyer(tenant.id(), r, "PENDING")));
	}

	@GetMapping("/me")
	CurrentUser me() {
		return CurrentUser.get();
	}

}
