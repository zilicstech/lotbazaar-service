package com.lotbazaar;

import com.lotbazaar.tenancy.TenantFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		var roles = new JwtGrantedAuthoritiesConverter();
		roles.setAuthoritiesClaimName("roles");
		roles.setAuthorityPrefix("ROLE_");
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(roles);
		return http.csrf(c -> c.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/api/auth/login", "/api/auth/register-buyer",
					"/api/public/**", "/error")
				.permitAll()
				// Staff roles: OWNER sees everything, WAREHOUSE runs the floor (no prices), SALES sells.
				.requestMatchers(HttpMethod.POST, "/api/seller/containers")
				.hasRole("OWNER")
				.requestMatchers("/api/seller/dashboard", "/api/seller/claims/**")
				.hasRole("OWNER")
				.requestMatchers("/api/seller/containers/**", "/api/seller/wh/**")
				.hasAnyRole("OWNER", "WAREHOUSE")
				.requestMatchers("/api/seller/lots/**", "/api/seller/offers/**", "/api/seller/buyers/**",
						"/api/seller/orders/**")
				.hasAnyRole("OWNER", "SALES")
				.requestMatchers("/api/seller/**")
				.hasRole("SELLER")
				.requestMatchers("/api/store/**")
				.hasRole("BUYER")
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
			.addFilterAfter(new TenantFilter(), BearerTokenAuthenticationFilter.class)
			.build();
	}

}
