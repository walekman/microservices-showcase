package com.showcase.transfer.client;

import lombok.Getter;

/** Fraud Service understood the request and blocked the account (4xx). Retrying will not help. */
@Getter
public class FraudRejectedException extends RuntimeException {

    private final String detail;

    public FraudRejectedException(String detail) {
        super("Fraud Service rejected the account: " + detail);
        this.detail = detail;
    }
}
