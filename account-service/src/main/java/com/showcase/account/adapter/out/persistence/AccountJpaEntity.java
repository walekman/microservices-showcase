package com.showcase.account.adapter.out.persistence;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.SupportedCurrency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Stored form of {@link Account}. */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AccountJpaEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID ownerId;

    @Column(nullable = false)
    private String ownerName;

    // scale 2, not more: AmountRequest restricts every debit/credit to 2 decimal places
    // (@Digits(fraction = 2)), and Transfer rounds every converted amount to 2 places before it
    // gets here (docs/phase-12-fx-rates-redis-cache.md). A wider scale would be unused headroom.
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    // Nullable at the database level only because ddl-auto: update cannot add a NOT NULL column
    // over existing rows; toDomain() reads such a pre-Phase-12 row as EUR.
    @Enumerated(EnumType.STRING)
    @Column(length = 3, updatable = false)
    private SupportedCurrency currency;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // A new row: no id (the database assigns it) and no version yet.
    static AccountJpaEntity newRow(Account account) {
        AccountJpaEntity entity = new AccountJpaEntity();
        entity.ownerId = account.getOwnerId();
        entity.ownerName = account.getOwnerName();
        entity.balance = account.getBalance();
        entity.currency = account.getCurrency();
        entity.createdAt = account.getCreatedAt();
        return entity;
    }

    // The balance is the only state an existing account can change.
    void updateBalance(BigDecimal balance) {
        this.balance = balance;
    }

    Account toDomain() {
        return Account.restore(id, ownerId, ownerName, balance,
                currency != null ? currency : SupportedCurrency.EUR, version, createdAt);
    }
}
