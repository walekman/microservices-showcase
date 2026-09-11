package com.showcase.transfer.client;

/** Account Service is broken or unreachable (5xx, timeout, connection failure). */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}
