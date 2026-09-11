package com.showcase.transfer.client;

import lombok.Getter;

/** Account Service understood the request and refused it (4xx). Retrying will not help. */
@Getter
public class AccountRejectedException extends RuntimeException {

    private final String code;
    private final String detail;

    public AccountRejectedException(String code, String detail) {
        super("Account Service rejected the request [%s]: %s".formatted(code, detail));
        this.code = code;
        this.detail = detail;
    }
}
