package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import lombok.Getter;

/** Carries a non-completed transfer to the exception handler, which renders it as a problem response. */
@Getter
public class TransferFailedException extends RuntimeException {

    private final transient Transfer transfer;

    public TransferFailedException(Transfer transfer) {
        super("Transfer %s ended as %s".formatted(transfer.getId(), transfer.getStatus()));
        this.transfer = transfer;
    }
}
