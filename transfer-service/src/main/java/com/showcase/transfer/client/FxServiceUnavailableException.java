package com.showcase.transfer.client;

/** FX Service could not supply a usable rate (5xx, 4xx, timeout, connection failure, unusable body). */
public class FxServiceUnavailableException extends RuntimeException {

    public FxServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public FxServiceUnavailableException(String message) {
        super(message);
    }
}
