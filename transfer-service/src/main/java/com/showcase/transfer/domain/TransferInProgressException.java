package com.showcase.transfer.domain;

import lombok.Getter;

import java.util.UUID;

/**
 * A POST /transfers replayed the Idempotency-Key of a transfer that is still PENDING -- the
 * original request is running, or died mid-saga and awaits CompensationScheduler. Either way the
 * outcome is not known yet, so the replay must not report one. Carries the id so the caller can
 * poll GET /transfers/{id}.
 */
@Getter
public class TransferInProgressException extends RuntimeException {

    private final UUID transferId;

    public TransferInProgressException(UUID transferId) {
        super("Transfer " + transferId + " is still in progress");
        this.transferId = transferId;
    }
}
