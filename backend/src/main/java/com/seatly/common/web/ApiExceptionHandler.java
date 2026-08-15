package com.seatly.common.web;

import com.seatly.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns exceptions into RFC 9457 problem documents.
 * <p>
 * One shape for every error means a client can handle failures generically
 * instead of guessing at whatever each endpoint happens to return. The
 * {@code type} URI is what callers should branch on -- status codes are too
 * coarse to distinguish "seat already taken" from "event not found".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(NotFoundException.class)
	public ProblemDetail handleNotFound(NotFoundException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
		problem.setTitle("Not found");
		problem.setType(URI.create("https://seatly.dev/problems/not-found"));
		return problem;
	}

	/**
	 * The catch-all. The detail sent to the client is deliberately vague, while
	 * the log keeps the stack trace: an error response is not the place to
	 * publish internal class names or SQL.
	 */
	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception exception) {
		log.error("Unhandled exception", exception);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong handling this request.");
		problem.setTitle("Internal server error");
		problem.setType(URI.create("https://seatly.dev/problems/internal-error"));
		return problem;
	}

}
