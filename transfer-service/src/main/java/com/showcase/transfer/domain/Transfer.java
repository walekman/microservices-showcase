package com.showcase.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

@Entity
// Scoped per initiator: one caller's key can neither collide with nor reveal another's transfer.
// Pre-idempotency rows carry a null key, and Postgres lets any number of NULLs share a unique
// constraint -- which is what lets ddl-auto: update add it over a populated table.
@Table(name = "transfers",
        uniqueConstraints = @UniqueConstraint(name = "uk_transfers_initiator_idempotency_key",
                columnNames = {"initiator_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID fromAccountId;

    @Column(nullable = false, updatable = false)
    private UUID toAccountId;

    // The JWT `sub` of whoever called POST /transfers -- captured for free, since Transfer
    // already sees that token. See docs/phase-7b-account-ownership-authorization.md.
    @Column(nullable = false, updatable = false)
    private UUID initiatorId;

    // The caller's Idempotency-Key on POST /transfers. Nullable only because rows created before
    // the header became required have none. See docs/microservices-showcase-design.md.
    @Column(length = 255, updatable = false)
    private String idempotencyKey;

    // scale 2, matching Account.balance -- see the comment there.
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    // The conversion, locked before the row is first inserted and never changed afterwards
    // (updatable = false): every leg and every CompensationScheduler replay reads these, never a
    // fresh rate -- a replay that re-priced would send a different amount under an idempotency key
    // Account has already recorded. All null on a transfer that failed before it was priced, and on
    // a row from before Phase 12 (same-currency by construction; see amountToCredit()). See
    // docs/phase-12-fx-rates-redis-cache.md.
    @Column(length = 3, updatable = false)
    private String sourceCurrency;

    @Column(length = 3, updatable = false)
    private String destinationCurrency;

    @Column(precision = 19, scale = 10, updatable = false)
    private BigDecimal rate;

    // The provider's publication date for rate; null for a same-currency transfer (rate 1).
    @Column(updatable = false)
    private LocalDate rateAsOf;

    // scale 2, matching Account.balance.
    @Column(precision = 19, scale = 2, updatable = false)
    private BigDecimal creditAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TransferFailureCode failureCode;

    @Column(length = 512)
    private String failureReason;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant settledAt;

    public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, UUID initiatorId) {
        this(fromAccountId, toAccountId, amount, initiatorId, null);
    }

    public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, UUID initiatorId,
                    String idempotencyKey) {
        if (fromAccountId == null || toAccountId == null) {
            throw new IllegalArgumentException("Both account ids are required");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new SameAccountTransferException(fromAccountId);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.initiatorId = initiatorId;
        this.idempotencyKey = idempotencyKey;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * True when a replayed POST /transfers reused this transfer's idempotency key for a
     * different transfer. compareTo, not equals, for the amount: "40" and "40.00" are the
     * same request.
     */
    public boolean conflictsWith(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        return !this.fromAccountId.equals(fromAccountId)
                || !this.toAccountId.equals(toAccountId)
                || this.amount.compareTo(amount) != 0;
    }

    /** What the destination is credited for amount at rate: HALF_EVEN to scale 2, matching Account.balance. */
    public static BigDecimal creditAmountFor(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Fixes the conversion this transfer will use for every leg and every replay. Called exactly
     * once, by TransferService, before the row's first insert -- so no PENDING row ever exists
     * without it.
     */
    public void lockConversion(String sourceCurrency, String destinationCurrency, BigDecimal rate, LocalDate rateAsOf) {
        requireStatus(TransferStatus.PENDING);
        if (this.creditAmount != null) {
            throw new IllegalStateException("Transfer %s already has a locked conversion".formatted(id));
        }
        if (sourceCurrency == null || destinationCurrency == null || rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("A conversion needs both currencies and a positive rate");
        }
        BigDecimal credit = creditAmountFor(amount, rate);
        if (credit.signum() <= 0) {
            throw new IllegalArgumentException("%s at rate %s credits %s".formatted(amount, rate, credit));
        }
        this.sourceCurrency = sourceCurrency;
        this.destinationCurrency = destinationCurrency;
        this.rate = rate;
        this.rateAsOf = rateAsOf;
        this.creditAmount = credit;
    }

    /**
     * The amount the credit leg sends: the locked creditAmount, or -- for a row from before Phase 12,
     * which was same-currency by construction -- the debited amount. The saga and the compensator
     * use this; the API and the outbox report the raw (nullable) creditAmount.
     */
    public BigDecimal amountToCredit() {
        return creditAmount != null ? creditAmount : amount;
    }

    /** Reachable from PENDING (the live saga) or COMPENSATION_REQUIRED (reconciliation found the credit had already landed). */
    public void markCompleted() {
        requireStatus(TransferStatus.PENDING, TransferStatus.COMPENSATION_REQUIRED);
        this.status = TransferStatus.COMPLETED;
        // From COMPENSATION_REQUIRED, markCompensationRequired() already set both fields to
        // the stranding's diagnostics. The transfer just reconciled as genuinely completed,
        // so an API response for it must not go on reporting a failure that did not happen.
        this.failureCode = null;
        this.failureReason = null;
        this.settledAt = Instant.now();
    }

    public void markFailed(TransferFailureCode failureCode, String failureReason) {
        requireStatus(TransferStatus.PENDING);
        this.status = TransferStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    public void markCompensationRequired(TransferFailureCode failureCode, String failureReason) {
        requireStatus(TransferStatus.PENDING);
        this.status = TransferStatus.COMPENSATION_REQUIRED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    /** The destination definitively never received the credit; the source has now been credited back. */
    public void markCompensated() {
        requireStatus(TransferStatus.COMPENSATION_REQUIRED);
        this.status = TransferStatus.COMPENSATED;
        this.settledAt = Instant.now();
    }

    /** Both the destination credit and the source credit-back definitively failed. Manual review. */
    public void markCompensationFailed(String failureReason) {
        requireStatus(TransferStatus.COMPENSATION_REQUIRED);
        this.status = TransferStatus.COMPENSATION_FAILED;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    private void requireStatus(TransferStatus... allowed) {
        for (TransferStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
                "Transfer %s is %s, expected one of %s".formatted(id, status, Arrays.toString(allowed)));
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= 512) {
            return reason;
        }
        // Cutting at 512 blindly can split a surrogate pair (an emoji straddling the boundary),
        // leaving an unpaired surrogate that the PostgreSQL driver refuses to encode. That would
        // recreate the exact failure this method exists to prevent: a recorded failure silently
        // becoming an unrecorded one. Drop the lone high surrogate instead.
        int end = Character.isHighSurrogate(reason.charAt(511)) ? 511 : 512;
        return reason.substring(0, end);
    }
}
