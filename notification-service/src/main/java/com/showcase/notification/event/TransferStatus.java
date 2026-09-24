package com.showcase.notification.event;

/**
 * The terminal transfer statuses Transfer Service publishes (see its OutboxEventType.forStatus).
 * Deliberately not PENDING / COMPENSATION_REQUIRED: those are never published, so a payload
 * carrying one fails deserialization and is dead-lettered rather than stored.
 */
public enum TransferStatus {
    COMPLETED,
    FAILED,
    COMPENSATED,
    COMPENSATION_FAILED
}
