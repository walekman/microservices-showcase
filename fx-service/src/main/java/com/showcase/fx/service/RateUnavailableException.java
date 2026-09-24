package com.showcase.fx.service;

/** No rate can be given: the provider is unreachable and Redis holds nothing to fall back on. */
public class RateUnavailableException extends RuntimeException {

    public RateUnavailableException(String message) {
        super(message);
    }

    public RateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
