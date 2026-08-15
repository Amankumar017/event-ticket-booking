package com.seatly.common.web;

import com.seatly.booking.SeatUnavailableException;
import com.seatly.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

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

	@ExceptionHandler(SeatUnavailableException.class)
	public ProblemDetail handleSeatUnavailable(SeatUnavailableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
		problem.setTitle("Seat unavailable");
		problem.setType(URI.create("https://seatly.dev/problems/seat-unavailable"));
		return problem;
	}

	/**
	 * Bean validation failures.
	 * <p>
	 * Needed explicitly: the catch-all below would otherwise swallow a bad
	 * request and report it as a 500, which blames the server for the client's
	 * mistake and hides the reason.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleInvalidRequest(MethodArgumentNotValidException exception) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : exception.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "The request was not valid.");
		problem.setTitle("Invalid request");
		problem.setType(URI.create("https://seatly.dev/problems/invalid-request"));
		problem.setProperty("errors", errors);
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
