package com.showcase.account.domain;

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

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    // The JWT `sub` of whoever created this account (see docs/phase-7b-account-ownership-authorization.md).
    // Never client-supplied -- bound server-side from the authenticated caller at creation.
    @Column(nullable = false, unique = true)
    private UUID ownerId;

    @Column(nullable = false)
    private String ownerName;

    // scale 2, not more: AmountRequest restricts every debit/credit to 2 decimal places
    // (@Digits(fraction = 2)), and Transfer rounds every converted amount to 2 places before it
    // gets here (docs/phase-12-fx-rates-redis-cache.md). A wider scale would be unused headroom.
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    // Fixed at creation. Nullable at the database level only because ddl-auto: update cannot add a
    // NOT NULL column over existing rows; getCurrency() reads such a pre-Phase-12 row as EUR.
    @Enumerated(EnumType.STRING)
    @Column(length = 3, updatable = false)
    private SupportedCurrency currency;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Account(UUID ownerId, String ownerName, BigDecimal balance, SupportedCurrency currency) {
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.balance = balance;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    // Hand-written, so Lombok's @Getter skips this field. See the comment on the field.
    public SupportedCurrency getCurrency() {
        return currency != null ? currency : SupportedCurrency.EUR;
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
