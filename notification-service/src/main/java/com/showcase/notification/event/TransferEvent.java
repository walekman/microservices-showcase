package com.showcase.notification.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Notification's own view of the payload Transfer Service's outbox publishes (its
 * TransferSaveService.TransferEventPayload). Mirrored rather than shared: the two services
 * ship from one repo but share no code module. failureCode stays a String so a new Transfer
 * failure code does not make an otherwise valid event undeserializable here. The conversion fields
 * (Phase 12) are null on an event published before Transfer locked conversions.
 */
public record TransferEvent(UUID transferId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                            TransferStatus status, String failureCode, String failureReason,
                            Instant settledAt, String sourceCurrency, String destinationCurrency,
                            BigDecimal rate, BigDecimal creditAmount) {
}
