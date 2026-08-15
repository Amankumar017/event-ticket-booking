package com.seatly.common;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfiguration {

	/**
	 * The application's JSON mapper.
	 * <p>
	 * Declared rather than assumed: Spring Boot 4 does not publish an
	 * {@code ObjectMapper} bean of its own, and the idempotency store needs one to
	 * serialise stored replies and to fingerprint requests.
	 * <p>
	 * Instants are written as ISO-8601 strings rather than as epoch numbers, so a
	 * reply read back out of the store is byte-for-byte what the first caller
	 * received.
	 */
	@Bean
	public ObjectMapper objectMapper() {
		return JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.build();
	}

}
