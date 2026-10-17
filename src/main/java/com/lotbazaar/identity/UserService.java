package com.lotbazaar.identity;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

	public record UserView(UUID id, String email, String name, String kind, String role, UUID tenantId,
			String tenantName, String tenantCode, String businessName) {
	}

	record UserRow(UUID id, String email, String passwordHash, String name, String kind, UUID tenantId, String role,
			String tenantName, String tenantCode, String businessName) {
		UserView view() {
			return new UserView(id, email, name, kind, role, tenantId, tenantName, tenantCode, businessName);
		}
	}

	public record BuyerRegistration(String name, String email, String password, String businessName, String gstin,
			String city, String tenantCode) {
	}

	private final JdbcClient jdbc;

	private final PasswordEncoder passwords;

	private final JwtEncoder jwt;

	UserService(JdbcClient jdbc, PasswordEncoder passwords, JwtEncoder jwt) {
		this.jdbc = jdbc;
		this.passwords = passwords;
		this.jwt = jwt;
	}

	@Transactional(readOnly = true)
	public Optional<UserView> authenticate(String email, String password) {
		return jdbc.sql("""
				select u.id, u.email, u.password_hash, u.name, u.kind, u.tenant_id, u.role,
				       t.legal_name as tenant_name, t.code as tenant_code, u.business_name
				from app_user u left join tenant t on t.id = u.tenant_id
				where lower(u.email) = lower(?)""").param(email).query(UserRow.class).optional()
			.filter(u -> passwords.matches(password, u.passwordHash()))
			.map(UserRow::view);
	}

	public String issueToken(UserView u) {
		var roles = new ArrayList<>(List.of(u.kind(), u.role()));
		var claims = JwtClaimsSet.builder()
			.subject(u.id().toString())
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plus(Duration.ofHours(12)))
			.claim("kind", u.kind())
			.claim("name", u.name())
			.claim("roles", roles);
		if (u.tenantId() != null) {
			claims.claim("tenant_id", u.tenantId().toString());
		}
		return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
			.getTokenValue();
	}

	/** Creates a seller user inside a tenant (used by onboarding and demo seed). */
	@Transactional
	public UUID createSeller(UUID tenantId, String email, String password, String name, String role) {
		var id = UUID.randomUUID();
		jdbc.sql("insert into app_user(id, email, password_hash, name, kind, tenant_id, role) values (?,?,?,?, 'SELLER', ?, ?)")
			.params(id, email, passwords.encode(password), name, tenantId, role)
			.update();
		return id;
	}

	public void validate(BuyerRegistration r) {
		if (r.gstin() == null || !r.gstin().matches("^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid 15-character GSTIN");
		}
		if (r.password() == null || r.password().length() < 6) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
		}
	}

	/** Creates a global buyer account and its relationship with the current tenant. */
	@Transactional
	public UUID createBuyer(UUID tenantId, BuyerRegistration r, String status) {
		var id = UUID.randomUUID();
		try {
			jdbc.sql("""
					insert into app_user(id, email, password_hash, name, kind, role, business_name, gstin, city)
					values (?,?,?,?, 'BUYER', 'RETAILER', ?,?,?)""")
				.params(id, r.email().trim(), passwords.encode(r.password()), r.name(), r.businessName(), r.gstin(),
						r.city())
				.update();
		}
		catch (DuplicateKeyException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
		}
		jdbc.sql("insert into buyer_tenant_relationship(tenant_id, buyer_user_id, status) values (?,?,?)")
			.params(tenantId, id, status)
			.update();
		return id;
	}

}
