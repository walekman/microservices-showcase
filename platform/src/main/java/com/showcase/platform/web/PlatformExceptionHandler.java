package com.showcase.platform.web;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

/**
 * The generic half of every service's RFC 7807 handling: request-shape errors
 * ({@code VALIDATION_FAILED}, {@code MALFORMED_REQUEST}), the catch-all
 * ({@code INTERNAL_ERROR}), and the guard that gives every other Spring MVC error a
 * {@code code} ({@code REQUEST_REJECTED} or {@code INTERNAL_ERROR}).
 *
 * <p>A service's {@code @RestControllerAdvice} extends this and adds only its own exceptions.
 * Both halves live in one advice, so Spring picks the handler for the most specific exception
 * type: a service's handler always wins over the inherited catch-all, and a service can
 * override any method here. See docs/phase-14-shared-platform-module.md.
 *
 * <p>An extending service needs {@code jakarta.validation-api} at runtime, since the module only
 * has it as an optional dependency: without it Spring can't read the
 * {@code ConstraintViolationException} handler and the context fails to start.
 */
public abstract class PlatformExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * @Validated on a controller routes a constraint on an @RequestHeader/@PathVariable method
     * parameter through Spring's AOP-based MethodValidationInterceptor, which throws this -- not
     * the @Valid-on-@RequestBody path MethodArgumentNotValidException covers below. Without this
     * handler, a blank or overlong Idempotency-Key fell through to the catch-all as a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> lastPathSegment(violation.getPropertyPath()) + " " + violation.getMessage())
                .orElse("Validation failed");
        return Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", detail);
    }

    private static String lastPathSegment(Path path) {
        String last = "request";
        for (Path.Node node : path) {
            last = node.getName();
        }
        return last;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return badRequest("VALIDATION_FAILED", "Validation failed", detail);
    }

    /**
     * {@code ResponseEntityExceptionHandler} has no dedicated hook for a missing
     * {@code @RequestHeader} the way it does for a missing request parameter --
     * {@link MissingRequestHeaderException} is handled through this more general one instead
     * (it extends {@code MissingRequestValueException} extends
     * {@link ServletRequestBindingException}). Special-cased for the header-name detail;
     * any other {@code ServletRequestBindingException} gets a generic message.
     */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = (ex instanceof MissingRequestHeaderException missingHeader)
                ? "Missing required header: " + missingHeader.getHeaderName()
                : "Malformed request";
        return badRequest("VALIDATION_FAILED", "Validation failed", detail);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return badRequest("VALIDATION_FAILED", "Validation failed",
                "Missing required parameter: " + ex.getParameterName());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return badRequest("MALFORMED_REQUEST", "Malformed request", "Malformed request body");
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return badRequest("MALFORMED_REQUEST", "Malformed request",
                "Invalid value for parameter: " + ex.getPropertyName());
    }

    /**
     * Last line of defence for the "every error response carries a {@code code}" contract: the
     * exceptions {@link ResponseEntityExceptionHandler} handles for us (405, 415, 406, unmapped
     * paths, missing path variables, ...) render a {@code ProblemDetail} that carries no custom
     * properties, so stamp the invariant here rather than overriding every hook individually.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", statusCode.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED");
            problem.setProperty("timestamp", Instant.now());
        }
        return response;
    }

    private static ResponseEntity<Object> badRequest(String code, String title, String detail) {
        return ResponseEntity.badRequest().body(Problems.of(HttpStatus.BAD_REQUEST, code, title, detail));
    }
}
