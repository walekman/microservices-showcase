package com.showcase.account.application.port.out;

import com.showcase.account.domain.AccountOperation;

import java.util.Optional;

public interface AccountOperationLedger {

    Optional<AccountOperation> find(String idempotencyKey);

    /**
     * Writes the operation immediately (not at commit). Throws ConcurrentOperationException if
     * its idempotency key is already recorded, including by a request still in flight -- the
     * ledger, not a prior find, is what makes the key unique.
     */
    void record(AccountOperation operation);
}
