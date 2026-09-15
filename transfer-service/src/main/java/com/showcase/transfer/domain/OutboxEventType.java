package com.showcase.transfer.domain;

/** The two events Notification Service consumes. */
public enum OutboxEventType {
    TRANSFER_COMPLETED,
    TRANSFER_FAILED;

    /**
     * Maps a Transfer's status onto the event TransferSaveService writes, or null for a
     * non-terminal status. FAILED, COMPENSATED, and COMPENSATION_FAILED all resolve to
     * TRANSFER_FAILED -- the payload's own status field is what lets a consumer tell them
     * apart; see docs/phase-4-outbox-kafka-notification.md's Design Decisions.
     */
    public static OutboxEventType forStatus(TransferStatus status) {
        return switch (status) {
            case COMPLETED -> TRANSFER_COMPLETED;
            case FAILED, COMPENSATED, COMPENSATION_FAILED -> TRANSFER_FAILED;
            case PENDING, COMPENSATION_REQUIRED -> null;
        };
    }
}
