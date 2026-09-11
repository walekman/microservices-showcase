package com.showcase.transfer.service;

import lombok.Getter;

import java.util.UUID;

/**
 * Thrown when a transfer reached a terminal state but persisting it failed.
 *
 * <p>The row exists and still reads {@code PENDING}, so this is not an ordinary bug: there is a
 * specific record that needs reconciling, and the caller cannot find it without the id. Carries
 * the id rather than the {@link com.showcase.transfer.domain.Transfer} itself, because at the
 * point this is thrown the in-memory entity disagrees with the row it came from -- handing that
 * around would invite a caller to trust a status the database never accepted. The original
 * failure is kept as the cause; it is the real diagnostic.
 */
@Getter
public class TransferPersistenceException extends RuntimeException {

    private final UUID transferId;

    public TransferPersistenceException(UUID transferId, Throwable cause) {
        super("Failed to persist the terminal state of transfer " + transferId, cause);
        this.transferId = transferId;
    }
}
