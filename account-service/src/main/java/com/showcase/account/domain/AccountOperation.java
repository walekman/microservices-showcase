package com.showcase.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

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
 *
 * <p>Implements {@link Persistable} and hardcodes {@link #isNew()} to {@code true}: the
 * {@code @Id} here is a caller-assigned String, never {@code @GeneratedValue}, so Spring
 * Data JPA's default new-vs-existing detection (a null id means new) always sees a non-null
 * id and would route every {@code save()} through {@code EntityManager.merge()} -- a silent
 * upsert -- instead of {@code persist()}. That would make a duplicate idempotency key update
 * the existing row instead of hitting the unique-constraint violation the dedup logic
 * depends on. Since every save here is genuinely a new row, isNew() always returning true
 * forces persist() every time.
 */
@Entity
@Table(name = "account_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountOperation implements Persistable<String> {

    @Id
    @Column(length = 255)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private AccountOperationType operation;

    // scale 2, matching Account.balance -- see the comment there.
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public AccountOperation(String idempotencyKey, UUID accountId, AccountOperationType operation,
                             BigDecimal amount, BigDecimal balanceAfter) {
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
        this.createdAt = Instant.now();
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

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
