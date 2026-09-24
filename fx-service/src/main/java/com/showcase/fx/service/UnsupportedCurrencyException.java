package com.showcase.fx.service;

public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String currency) {
        super("Unsupported currency: " + currency);
    }
}
