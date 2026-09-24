package com.showcase.notification.service;

/**
 * A record that deserialized but cannot be a real transfer outcome (e.g. no transferId).
 * Permanent: redelivering the same bytes cannot fix it, so the error handler dead-letters it
 * on the first failure.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
