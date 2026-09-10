package com.showcase.transfer.domain;

import java.util.UUID;

public class SameAccountTransferException extends RuntimeException {

    public SameAccountTransferException(UUID accountId) {
        super("Source and destination must differ, both were: " + accountId);
    }
}
