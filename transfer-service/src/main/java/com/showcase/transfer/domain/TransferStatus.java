package com.showcase.transfer.domain;

public enum TransferStatus {
    /** Created, no leg attempted or the outcome is not yet known. */
    PENDING,
    /** Source debited and destination credited. */
    COMPLETED,
    /** Rejected before or during the debit. No money moved. */
    FAILED,
    /**
     * Source was debited but the destination credit did not succeed, so funds are
     * stranded at the source. CompensationScheduler drains this state by reconciling
     * against Account Service: it resolves to COMPLETED (the credit had already landed),
     * COMPENSATED (the source was credited back), or COMPENSATION_FAILED (manual review).
     */
    COMPENSATION_REQUIRED,
    /** The destination definitively never received the credit, and the source was credited back. */
    COMPENSATED,
    /** Both the destination credit and the source credit-back definitively failed. Manual review. */
    COMPENSATION_FAILED
}
