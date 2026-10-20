package com.showcase.account.domain;

/**
 * Another request recorded an operation under the same idempotency key while this one was in
 * flight, so this one was not applied. Deliberately left unmapped by the web adapter: it surfaces
 * as a generic 500, which Transfer treats as an unknown outcome and settles by replaying the same
 * key -- never as a rejection, because the other request's operation did apply. See
 * docs/phase-3-resilience-compensation-idempotency.md and AccountControllerIT's same-key race test.
 */
public class ConcurrentOperationException extends RuntimeException {

    public ConcurrentOperationException(String idempotencyKey, Throwable cause) {
        super("Operation recorded concurrently under idempotency key: " + idempotencyKey, cause);
    }
}
