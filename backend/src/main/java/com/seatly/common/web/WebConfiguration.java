package com.seatly.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

	private final String[] allowedOrigins;

	public WebConfiguration(@Value("${seatly.cors.allowed-origins}") String[] allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	/**
	 * The Angular dev server runs on its own port, which makes every call to this
	 * API a cross-origin request.
	 * <p>
	 * The origins come from configuration rather than being hardcoded, and there
	 * is no wildcard: a deployment sets its own list, and forgetting to set one
	 * fails loudly at startup instead of quietly allowing the whole internet.
	 */
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(allowedOrigins)
				.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.allowCredentials(true)
				.maxAge(3600);
	}

}
