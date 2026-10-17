package com.lotbazaar.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Reads the tenant_id claim from the seller JWT. Runs after bearer-token authentication. */
public class TenantFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
					&& jwt.getToken().getClaimAsString("tenant_id") != null) {
				TenantContext.set(UUID.fromString(jwt.getToken().getClaimAsString("tenant_id")));
			}
			chain.doFilter(request, response);
		}
		finally {
			TenantContext.clear();
		}
	}

}
