package com.seatly.account;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

	private final SecurityProperties properties;

	public SecurityConfiguration(SecurityProperties properties) {
		this.properties = properties;
	}

	@Bean
	public SecurityFilterChain api(HttpSecurity http) throws Exception {
		return http
				// No cookie-backed session to forge a request against, and the one
				// cookie that does exist is SameSite=Strict, so CSRF protection has
				// nothing left to protect. Disabling it without both of those in
				// place would be a mistake.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.cors(Customizer.withDefaults())
				.authorizeHttpRequests(requests -> requests
						// Browsing is public. Selling tickets to people who have not
						// signed in yet is the point of the shop.
						.requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
						.requestMatchers("/api/auth/register", "/api/auth/login",
								"/api/auth/refresh", "/api/auth/logout").permitAll()
						// The provider has no account here. It proves who it is with a
						// signature over the body, which the handler checks itself.
						.requestMatchers("/api/payments/webhook").permitAll()
						// The simulated gateway only exists under the seed profile.
						.requestMatchers("/api/dev/**").permitAll()
						.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
						.requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
						jwt.jwtAuthenticationConverter(authenticationConverter())))
				.build();
	}

	/**
	 * BCrypt at strength 12.
	 * <p>
	 * Higher than the default 10 because the default was chosen for hardware two
	 * decades old. Each step doubles the work an attacker has to do per guess, and
	 * costs this application a few tens of milliseconds on a path used twice a
	 * session.
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	public JwtEncoder jwtEncoder() {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
	}

	@Bean
	public JwtDecoder jwtDecoder() {
		return NimbusJwtDecoder.withSecretKey(secretKey()).build();
	}

	/**
	 * Turns the {@code role} claim into the authority Spring Security looks for.
	 * <p>
	 * Without this the token's role is just an unread string and every
	 * {@code hasRole} check fails, as a 403 that explains nothing.
	 */
	private JwtAuthenticationConverter authenticationConverter() {
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthorityPrefix("ROLE_");
		authorities.setAuthoritiesClaimName(AccessTokens.ROLE_CLAIM);

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	private SecretKeySpec secretKey() {
		return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

}
