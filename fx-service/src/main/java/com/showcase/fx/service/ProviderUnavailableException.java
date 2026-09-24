package com.showcase.fx.service;

/** The external rate provider failed, timed out, or returned something unusable. Internal to this service. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
