package com.showcase.account.domain;

import java.util.UUID;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(UUID ownerId) {
        super("Account already exists for owner: " + ownerId);
    }
}
