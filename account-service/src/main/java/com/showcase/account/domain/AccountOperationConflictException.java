package com.showcase.account.domain;

public class AccountOperationConflictException extends RuntimeException {

    public AccountOperationConflictException(String idempotencyKey) {
        super("Idempotency key already used with different parameters: " + idempotencyKey);
    }
}
