package com.showcase.transfer.domain;

import lombok.Getter;

import java.util.UUID;

/**
 * The live saga's debit call failed with ACCOUNT_SERVICE_UNAVAILABLE after every retry, so
 * whether the debit committed is unknown -- a read timeout cannot be told apart from a request
 * that never arrived. The transfer is deliberately left PENDING rather than settled:
 * CompensationScheduler's stale-PENDING sweep replays the debit's idempotency key and resolves
 * it from Account's answer. Carries the id so the caller can poll GET /transfers/{id}.
 */
@Getter
public class DebitOutcomeUnknownException extends RuntimeException {

    private final UUID transferId;

    public DebitOutcomeUnknownException(UUID transferId, Throwable cause) {
        super("Transfer " + transferId + " debit outcome unknown", cause);
        this.transferId = transferId;
    }
}
