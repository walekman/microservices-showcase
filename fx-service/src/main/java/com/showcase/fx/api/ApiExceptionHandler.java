package com.showcase.fx.api;

import com.showcase.fx.service.RateUnavailableException;
import com.showcase.fx.service.UnsupportedCurrencyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                        "Missing required parameter: " + ex.getParameterName()));
    }

    /** Same "every error carries a code" backstop as every other service's ApiExceptionHandler. */
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

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ProblemDetail handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY", "Unsupported currency", ex.getMessage());
    }

    @ExceptionHandler(RateUnavailableException.class)
    public ProblemDetail handleRateUnavailable(RateUnavailableException ex) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "FX_RATE_UNAVAILABLE", "FX rate unavailable",
                "No exchange rate is available right now");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred");
    }
}
