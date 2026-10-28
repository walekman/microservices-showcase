package com.showcase.transfer.api;

import com.showcase.platform.web.PlatformExceptionHandler;
import com.showcase.platform.web.Problems;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.DebitOutcomeUnknownException;
import com.showcase.transfer.domain.IdempotencyKeyConflictException;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferInProgressException;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferStatus;
import com.showcase.transfer.service.TransferPersistenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler extends PlatformExceptionHandler {

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
            // DESTINATION_ACCOUNT_BLOCKED and DESTINATION_FRAUD_SERVICE_UNAVAILABLE are only
            // ever set together with COMPENSATION_REQUIRED (see Transfer.markCompensationRequired
            // via TransferService.strand()), which the `if` branch above already intercepts before
            // this switch runs -- and this handler only fires from the synchronous POST /transfers
            // response, before any later CompensationScheduler transition. These two arms exist
            // only to satisfy the switch's exhaustiveness over TransferFailureCode, not because
            // they are reachable here today.
            status = switch (transfer.getFailureCode()) {
                case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS, CONCURRENT_MODIFICATION,
                     SOURCE_ACCOUNT_BLOCKED, DESTINATION_ACCOUNT_BLOCKED, AMOUNT_TOO_SMALL ->
                        HttpStatus.UNPROCESSABLE_ENTITY;
                case ACCOUNT_SERVICE_UNAVAILABLE, SOURCE_FRAUD_SERVICE_UNAVAILABLE,
                     DESTINATION_FRAUD_SERVICE_UNAVAILABLE, FX_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
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
        // Same exhaustiveness-only caveat as the switch in handleTransferFailed above:
        // DESTINATION_ACCOUNT_BLOCKED and DESTINATION_FRAUD_SERVICE_UNAVAILABLE cannot reach
        // this method with the given failureCode in practice.
        return switch (failureCode) {
            case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS, CONCURRENT_MODIFICATION,
                 SOURCE_ACCOUNT_BLOCKED, DESTINATION_ACCOUNT_BLOCKED, AMOUNT_TOO_SMALL -> failureReason;
            case ACCOUNT_SERVICE_UNAVAILABLE -> "Account Service is currently unavailable";
            case FX_SERVICE_UNAVAILABLE -> "FX Service is currently unavailable";
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

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
        return Problems.of(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency key conflict", ex.getMessage());
    }

    @ExceptionHandler(TransferInProgressException.class)
    public ProblemDetail handleInProgress(TransferInProgressException ex) {
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, "TRANSFER_IN_PROGRESS", "Transfer in progress",
                "A transfer with this idempotency key is still in progress");
        // The id is what lets the caller poll GET /transfers/{id} for the outcome instead of
        // retrying blind.
        problem.setProperty("transferId", ex.getTransferId());
        problem.setProperty("transferStatus", TransferStatus.PENDING);
        return problem;
    }

    /**
     * The live debit's outcome is unknown and the transfer was left PENDING for
     * CompensationScheduler to reconcile. Same code and status as any other Account outage, but
     * transferStatus PENDING tells the caller the transfer is not settled: keep the same
     * Idempotency-Key and poll GET /transfers/{id} rather than starting a new transfer, which
     * could move the money twice if this one completes.
     */
    @ExceptionHandler(DebitOutcomeUnknownException.class)
    public ProblemDetail handleDebitOutcomeUnknown(DebitOutcomeUnknownException ex) {
        ProblemDetail problem = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_SERVICE_UNAVAILABLE",
                "Account Service unavailable",
                "Account Service did not confirm the debit; the transfer is still being processed");
        problem.setProperty("transferId", ex.getTransferId());
        problem.setProperty("transferStatus", TransferStatus.PENDING);
        return problem;
    }

    /**
     * Only reachable from GET /transfers/{id}'s destination-owner ownership check
     * ({@code AccountClient.isOwnedByCaller}) -- every other AccountClient call the live saga
     * makes is already caught and turned into Transfer state inside TransferService.execute()
     * before it can reach here. Without this handler, Account being unreachable during a read
     * fell through to the inherited catch-all 500 instead of this project's usual 503
     * ACCOUNT_SERVICE_UNAVAILABLE for exactly that condition. Found in code review.
     */
    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ProblemDetail handleAccountUnavailable(AccountServiceUnavailableException ex) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_SERVICE_UNAVAILABLE",
                "Account Service unavailable", "Account Service is currently unavailable");
    }
}
