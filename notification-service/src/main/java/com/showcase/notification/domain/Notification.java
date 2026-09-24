package com.showcase.notification.domain;

import com.showcase.notification.event.TransferEvent;
import com.showcase.notification.event.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per transfer outcome notified. transferId is a unique column, not the @Id: with an
 * assigned @Id, save() on an existing key merges -- a silent overwrite that would hide a
 * conflicting second event instead of surfacing it (see NotificationService).
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransferStatus status;

    private UUID fromAccountId;

    private UUID toAccountId;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 64)
    private String failureCode;

    // Transfer's own column is 512 wide; matched so any reason Transfer can store fits here.
    @Column(length = 512)
    private String failureReason;

    private Instant settledAt;

    // Where the event was read from, so a stored row can be traced back to its Kafka record.
    @Column(nullable = false)
    private String kafkaTopic;

    @Column(nullable = false)
    private int kafkaPartition;

    @Column(nullable = false)
    private long kafkaOffset;

    @Column(nullable = false, updatable = false)
    private Instant receivedAt;

    public Notification(TransferEvent event, String kafkaTopic, int kafkaPartition, long kafkaOffset) {
        this.transferId = event.transferId();
        this.status = event.status();
        this.fromAccountId = event.fromAccountId();
        this.toAccountId = event.toAccountId();
        this.amount = event.amount();
        this.failureCode = event.failureCode();
        this.failureReason = event.failureReason();
        this.settledAt = event.settledAt();
        this.kafkaTopic = kafkaTopic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.receivedAt = Instant.now();
    }
}
