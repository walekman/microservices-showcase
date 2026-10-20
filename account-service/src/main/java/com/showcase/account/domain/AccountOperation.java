package com.showcase.account.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A permanent, immutable record of one applied debit or credit, keyed by the caller's
 * idempotency key. Doubles as both the dedup mechanism for safe retries and a permanent
 * per-account operation history — see docs/phase-3-resilience-compensation-idempotency.md
 * "Design Decisions" for why these are the same row rather than two separate mechanisms.
 *
 * <p>Never updated after creation. A repeat of the same idempotencyKey returns this row's
 * balanceAfter instead of reapplying the operation.
 */
@Getter
public class AccountOperation {

    private final String idempotencyKey;

    private final UUID accountId;

    private final AccountOperationType operation;

    private final BigDecimal amount;

    private final BigDecimal balanceAfter;

    private final Instant createdAt;

    public AccountOperation(String idempotencyKey, UUID accountId, AccountOperationType operation,
                            BigDecimal amount, BigDecimal balanceAfter) {
        this(idempotencyKey, accountId, operation, amount, balanceAfter, Instant.now());
    }

    private AccountOperation(String idempotencyKey, UUID accountId, AccountOperationType operation,
                             BigDecimal amount, BigDecimal balanceAfter, Instant createdAt) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.operation = operation;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }

    /** Rebuilds an operation that was already recorded, from its stored state. */
    public static AccountOperation restore(String idempotencyKey, UUID accountId, AccountOperationType operation,
                                           BigDecimal amount, BigDecimal balanceAfter, Instant createdAt) {
        return new AccountOperation(idempotencyKey, accountId, operation, amount, balanceAfter, createdAt);
    }

    /**
     * True when a replay of this key arrives with different parameters than the ones
     * originally recorded — a bug signal (see {@link AccountOperationConflictException}),
     * never a legitimate replay. A given (transferId, leg) always maps to the same
     * account/operation/amount in normal operation, so this should never actually trip.
     */
    public boolean conflictsWith(UUID accountId, AccountOperationType operation, BigDecimal amount) {
        return !this.accountId.equals(accountId)
                || this.operation != operation
                || this.amount.compareTo(amount) != 0;
    }
}
