package com.seatly.common.web;

import com.seatly.account.CurrentAccount;
import com.seatly.account.EmailAlreadyRegisteredException;
import com.seatly.account.InvalidCredentialsException;
import com.seatly.account.InvalidRefreshTokenException;
import com.seatly.booking.SeatUnavailableException;
import com.seatly.common.NotFoundException;
import com.seatly.common.idempotency.IdempotencyConflictException;
import com.seatly.common.idempotency.IdempotencyKeyReusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into RFC 9457 problem documents.
 * <p>
 * One shape for every error means a client can handle failures generically
 * instead of guessing at whatever each endpoint happens to return. The
 * {@code type} URI is what callers should branch on: status codes are too
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

	@ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class,
			CurrentAccount.NotSignedInException.class})
	public ProblemDetail handleUnauthenticated(RuntimeException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNAUTHORIZED, exception.getMessage());
		problem.setTitle("Not authenticated");
		problem.setType(URI.create("https://seatly.dev/problems/not-authenticated"));
		return problem;
	}

	@ExceptionHandler(EmailAlreadyRegisteredException.class)
	public ProblemDetail handleDuplicateEmail(EmailAlreadyRegisteredException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
		problem.setTitle("Already registered");
		problem.setType(URI.create("https://seatly.dev/problems/already-registered"));
		return problem;
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	public ProblemDetail handleKeyReused(IdempotencyKeyReusedException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
		problem.setTitle("Idempotency key reused");
		problem.setType(URI.create("https://seatly.dev/problems/idempotency-key-reused"));
		return problem;
	}

	@ExceptionHandler(IdempotencyConflictException.class)
	public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
		problem.setTitle("Request in progress");
		problem.setType(URI.create("https://seatly.dev/problems/request-in-progress"));
		return problem;
	}

	/**
	 * Method security refusals.
	 * <p>
	 * Needed explicitly: the catch-all below would otherwise turn a deliberate
	 * 403 into a 500 and log a stack trace for a rule working exactly as written.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ProblemDetail handleAccessDenied(AccessDeniedException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.FORBIDDEN, "You do not have access to that.");
		problem.setTitle("Forbidden");
		problem.setType(URI.create("https://seatly.dev/problems/forbidden"));
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
	 * A URL that does not exist.
	 * <p>
	 * Needed explicitly, and found the hard way: without it the catch-all below
	 * reported a plain 404 as a 500, complete with a stack trace in the log for
	 * somebody mistyping a path.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ProblemDetail handleUnknownPath(NoResourceFoundException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "No such endpoint.");
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
