package com.showcase.transfer.domain;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID transferId) {
        super("Transfer not found: " + transferId);
    }
}
