package com.showcase.transfer.api;

import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferStatus;
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
        } else {
            code = transfer.getFailureCode().name();
            title = "Transfer failed";
            status = switch (transfer.getFailureCode()) {
                case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS, CONCURRENT_MODIFICATION ->
                        HttpStatus.UNPROCESSABLE_ENTITY;
                case ACCOUNT_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                case UNEXPECTED_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        }

        ProblemDetail problem = Problems.of(status, code, title, transfer.getFailureReason());
        // Always hand back the id: a caller that got a 500 still needs to be able to
        // fetch the record and see what state the transfer ended in.
        problem.setProperty("transferId", transfer.getId());
        problem.setProperty("transferStatus", transfer.getStatus());
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
}
