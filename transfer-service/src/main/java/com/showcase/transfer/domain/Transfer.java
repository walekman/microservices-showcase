package com.showcase.transfer.domain;

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
@Table(name = "transfers")
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

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

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

    public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
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
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markCompleted() {
        requirePending();
        this.status = TransferStatus.COMPLETED;
        this.settledAt = Instant.now();
    }

    public void markFailed(TransferFailureCode failureCode, String failureReason) {
        requirePending();
        this.status = TransferStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    public void markCompensationRequired(TransferFailureCode failureCode, String failureReason) {
        requirePending();
        this.status = TransferStatus.COMPENSATION_REQUIRED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    private void requirePending() {
        if (status != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer %s is already %s".formatted(id, status));
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= 512) {
            return reason;
        }
        return reason.substring(0, 512);
    }
}
