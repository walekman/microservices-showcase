package com.showcase.transfer.domain;

/** A POST /transfers reused an Idempotency-Key the same caller already spent on a different transfer. */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key already used for a different transfer: " + idempotencyKey);
    }
}
