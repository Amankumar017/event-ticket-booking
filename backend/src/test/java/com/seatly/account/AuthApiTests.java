package com.seatly.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatly.support.IntegrationTest;
import com.seatly.support.SeatlyFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The auth endpoints over HTTP, where the cookie rules actually matter.
 */
@AutoConfigureMockMvc
class AuthApiTests extends IntegrationTest {

	private static final String REFRESH_COOKIE = "seatly_refresh";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SeatlyFixtures fixtures;

	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void clean() {
		fixtures.wipe();
	}

	@Test
	void registeringReturnsAnAccessTokenAndSetsTheRefreshCookie() throws Exception {
		mockMvc.perform(register("aman@example.com"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.expiresInSeconds").value(900))
				.andExpect(jsonPath("$.user.email").value("aman@example.com"))
				.andExpect(cookie().exists(REFRESH_COOKIE));
	}

	/** The refresh token must never be readable by script on the page. */
	@Test
	void theRefreshTokenNeverAppearsInTheBodyAndTheCookieIsLockedDown() throws Exception {
		MvcResult result = mockMvc.perform(register("aman@example.com"))
				.andExpect(cookie().httpOnly(REFRESH_COOKIE, true))
				.andExpect(cookie().path(REFRESH_COOKIE, "/api/auth"))
				.andReturn();

		String body = result.getResponse().getContentAsString();
		String cookieValue = result.getResponse().getCookie(REFRESH_COOKIE).getValue();

		assertThat(body).doesNotContain("refreshToken");
		assertThat(body).doesNotContain(cookieValue);
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("SameSite=Strict");
	}

	@Test
	void refreshingSwapsTheCookieForANewOne() throws Exception {
		Cookie first = cookieFrom(mockMvc.perform(register("aman@example.com")).andReturn());

		MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(first))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andReturn();

		assertThat(cookieFrom(refreshed).getValue()).isNotEqualTo(first.getValue());
	}

	@Test
	void refreshingWithoutACookieIsRejected() throws Exception {
		mockMvc.perform(post("/api/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://seatly.dev/problems/not-authenticated"));
	}

	@Test
	void replayingASpentCookieEndsTheSession() throws Exception {
		Cookie first = cookieFrom(mockMvc.perform(register("aman@example.com")).andReturn());
		Cookie second = cookieFrom(mockMvc.perform(post("/api/auth/refresh").cookie(first)).andReturn());

		mockMvc.perform(post("/api/auth/refresh").cookie(first))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/auth/refresh").cookie(second))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void loggingOutClearsTheCookieAndTheSession() throws Exception {
		Cookie session = cookieFrom(mockMvc.perform(register("aman@example.com")).andReturn());

		mockMvc.perform(post("/api/auth/logout").cookie(session))
				.andExpect(status().isNoContent())
				.andExpect(cookie().maxAge(REFRESH_COOKIE, 0));

		mockMvc.perform(post("/api/auth/refresh").cookie(session))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void signingInWithTheWrongPasswordIsA401() throws Exception {
		mockMvc.perform(register("aman@example.com"));

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email": "aman@example.com", "password": "wrong-password-here"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Email or password is incorrect"));
	}

	@Test
	void aShortPasswordIsRejectedBeforeAnAccountIsCreated() throws Exception {
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email": "aman@example.com", "password": "short", "displayName": "Aman"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").value("password must be at least 12 characters"));
	}

	@Test
	void whoAmIRequiresAToken() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void whoAmIDescribesTheSignedInAccount() throws Exception {
		String body = mockMvc.perform(register("aman@example.com")).andReturn()
				.getResponse().getContentAsString();
		String accessToken = json.readTree(body).get("accessToken").asText();

		mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("aman@example.com"))
				.andExpect(jsonPath("$.role").value("CUSTOMER"));
	}

	private org.springframework.test.web.servlet.RequestBuilder register(String email) {
		return post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email": "%s", "password": "correct-horse-battery", "displayName": "Aman"}
						""".formatted(email));
	}

	private Cookie cookieFrom(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE);
		assertThat(cookie).as("refresh cookie").isNotNull();
		return cookie;
	}

}
