package com.showcase.account.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An account and its balance rules. Plain Java: how it is stored is the persistence adapter's
 * concern (see adapter.out.persistence.AccountJpaEntity), which rebuilds it through
 * {@link #restore}.
 */
@Getter
public class Account {

    // Null until the account is first stored -- the database assigns it.
    private final UUID id;

    // The JWT `sub` of whoever created this account (see docs/phase-7b-account-ownership-authorization.md).
    // Never client-supplied -- bound server-side from the authenticated caller at creation.
    private final UUID ownerId;

    private final String ownerName;

    private BigDecimal balance;

    // Fixed at creation.
    private final SupportedCurrency currency;

    // The stored row's optimistic-locking version, carried through untouched so the persistence
    // adapter can tell a stale copy from a current one. Never interpreted here.
    private final long version;

    private final Instant createdAt;

    public Account(UUID ownerId, String ownerName, BigDecimal balance, SupportedCurrency currency) {
        this(null, ownerId, ownerName, balance, currency, 0, Instant.now());
    }

    private Account(UUID id, UUID ownerId, String ownerName, BigDecimal balance, SupportedCurrency currency,
                    long version, Instant createdAt) {
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.balance = balance;
        this.currency = currency;
        this.version = version;
        this.createdAt = createdAt;
    }

    /** Rebuilds an account that already exists, from its stored state. */
    public static Account restore(UUID id, UUID ownerId, String ownerName, BigDecimal balance,
                                  SupportedCurrency currency, long version, Instant createdAt) {
        return new Account(id, ownerId, ownerName, balance, currency, version, createdAt);
    }

    public void debit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        if (amount.compareTo(balance) > 0) {
            throw new InsufficientFundsException(id, amount, balance);
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        balance = balance.add(amount);
    }
}
