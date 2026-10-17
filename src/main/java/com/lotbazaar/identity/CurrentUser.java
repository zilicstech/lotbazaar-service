package com.lotbazaar.identity;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

public record CurrentUser(UUID id, String kind, String name) {

	public static CurrentUser get() {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t) {
			var jwt = t.getToken();
			return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("kind"),
					jwt.getClaimAsString("name"));
		}
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
	}

	/** True when the current request carries the given staff role (e.g. OWNER). */
	public static boolean hasRole(String role) {
		var auth = SecurityContextHolder.getContext().getAuthentication();
		return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
	}

}
