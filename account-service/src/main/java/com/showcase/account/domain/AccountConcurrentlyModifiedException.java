package com.showcase.account.domain;

import java.util.UUID;

/**
 * The account changed between being read and being saved, so this change was not applied
 * (optimistic locking). The caller may retry from a fresh read.
 */
public class AccountConcurrentlyModifiedException extends RuntimeException {

    public AccountConcurrentlyModifiedException(UUID accountId) {
        super("Account was modified concurrently: " + accountId);
    }
}
