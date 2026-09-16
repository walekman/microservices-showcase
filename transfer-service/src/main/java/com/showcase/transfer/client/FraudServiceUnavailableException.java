package com.showcase.transfer.client;

/** Fraud Service is broken or unreachable (5xx, timeout, connection failure). */
public class FraudServiceUnavailableException extends RuntimeException {

    public FraudServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public FraudServiceUnavailableException(String message) {
        super(message);
    }
}
