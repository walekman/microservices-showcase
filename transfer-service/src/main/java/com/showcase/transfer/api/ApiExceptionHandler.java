package com.showcase.transfer.api;

import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferStatus;
import com.showcase.transfer.service.TransferPersistenceException;

import java.time.Instant;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INTERNAL_FAILURE_DETAIL =
            "The transfer could not be completed due to an internal error";

    @ExceptionHandler(TransferFailedException.class)
    public ProblemDetail handleTransferFailed(TransferFailedException ex) {
        Transfer transfer = ex.getTransfer();

        HttpStatus status;
        String code;
        String title;
        if (transfer.getStatus() == TransferStatus.COMPENSATION_REQUIRED) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "COMPENSATION_REQUIRED";
            title = "Transfer needs compensation";
        } else if (transfer.getFailureCode() != null) {
            code = transfer.getFailureCode().name();
            title = "Transfer failed";
            status = switch (transfer.getFailureCode()) {
                case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS, CONCURRENT_MODIFICATION,
                     SOURCE_ACCOUNT_BLOCKED, DESTINATION_ACCOUNT_BLOCKED ->
                        HttpStatus.UNPROCESSABLE_ENTITY;
                case ACCOUNT_SERVICE_UNAVAILABLE, SOURCE_FRAUD_SERVICE_UNAVAILABLE,
                     DESTINATION_FRAUD_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                case UNEXPECTED_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        } else {
            // A non-terminal transfer (PENDING) reached the controller, so there is no failure
            // code to read. Unreachable while TransferService holds to its contract, but reading
            // the code unguarded would NPE inside this method -- and an exception thrown from an
            // @ExceptionHandler is not routed to another one: the resolver gives up and the
            // container renders its own error page, with no code and, worse, no transferId.
            // Degrade to a well-formed problem instead, so the id survives.
            logger.error("Transfer %s reached the API in non-terminal state %s"
                    .formatted(transfer.getId(), transfer.getStatus()));
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "UNEXPECTED_ERROR";
            title = "Transfer failed";
        }

        ProblemDetail problem = Problems.of(status, code, title,
                detailFor(transfer.getFailureCode(), transfer.getFailureReason()));
        // Always hand back the id: a caller that got a 500 still needs to be able to
        // fetch the record and see what state the transfer ended in.
        problem.setProperty("transferId", transfer.getId());
        problem.setProperty("transferStatus", transfer.getStatus());
        return problem;
    }

    /**
     * Chooses what the caller is allowed to read. A business rejection's recorded reason was
     * written for them and says what to do next, so it goes out as-is. The other two codes carry
     * operational text -- {@code ex.toString()} for an unexpected error, and a client message
     * that can name Account Service's internal host and port for an outage -- which is for the
     * log and the persisted row, not for an HTTP response. A null code reaches here only from
     * the non-terminal fallback above.
     */
    private static String detailFor(TransferFailureCode failureCode, String failureReason) {
        if (failureCode == null) {
            return INTERNAL_FAILURE_DETAIL;
        }
        return switch (failureCode) {
            case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS, CONCURRENT_MODIFICATION,
                 SOURCE_ACCOUNT_BLOCKED, DESTINATION_ACCOUNT_BLOCKED -> failureReason;
            case ACCOUNT_SERVICE_UNAVAILABLE -> "Account Service is currently unavailable";
            case SOURCE_FRAUD_SERVICE_UNAVAILABLE, DESTINATION_FRAUD_SERVICE_UNAVAILABLE ->
                    "Fraud Service is currently unavailable";
            case UNEXPECTED_ERROR -> INTERNAL_FAILURE_DETAIL;
        };
    }

    @ExceptionHandler(TransferPersistenceException.class)
    public ProblemDetail handlePersistenceFailure(TransferPersistenceException ex) {
        logger.error("Transfer %s could not be persisted".formatted(ex.getTransferId()), ex);

        ProblemDetail problem = Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Internal error", INTERNAL_FAILURE_DETAIL);
        // The id, and only the id. The saga's outcome was never written, so the row's status is
        // whatever the last successful save left -- reporting the in-memory state as
        // transferStatus would tell the caller something the database does not agree with.
        // GET /transfers/{id} is the honest answer, and needs exactly this.
        problem.setProperty("transferId", ex.getTransferId());
        return problem;
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleNotFound(TransferNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found", ex.getMessage());
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ProblemDetail handleSameAccount(SameAccountTransferException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT_TRANSFER", "Same account", ex.getMessage());
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
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", detail));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Malformed request body"));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Invalid value for parameter: " + ex.getPropertyName()));
    }

    /**
     * Stamps the {@code code}/{@code timestamp} invariant onto the ~14 MVC exception types
     * this class does not override explicitly (405, 415, 406, unmapped paths, and the rest).
     * Without it they render a ProblemDetail with NO code at all, and a consumer reading
     * {@code code} gets null on exactly the paths a mis-wired caller hits most. The handler
     * is duplicated per service rather than shared, so every service carries its own copy
     * of this guard.
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
}
