package com.showcase.fraud.domain;

import java.util.UUID;

public class AccountBlockedException extends RuntimeException {

    public AccountBlockedException(UUID accountId) {
        super("Account is blocklisted: " + accountId);
    }
}
