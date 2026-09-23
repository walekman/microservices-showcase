package com.showcase.transfer.domain;

public enum TransferStatus {
    /**
     * Created, no leg attempted or the outcome is not yet known -- including a live debit that
     * got no definitive answer from Account. CompensationScheduler's stale-PENDING sweep
     * resolves it by replaying the debit's idempotency key.
     */
    PENDING,
    /** Source debited and destination credited. */
    COMPLETED,
    /** Rejected before or during the debit, or the source account was blocklisted. No money moved. */
    FAILED,
    /**
     * Source was debited but the destination credit did not succeed or was never attempted
     * (e.g. the destination account was blocklisted), so funds are stranded at the source.
     * CompensationScheduler drains this state by reconciling against Account Service and
     * Fraud Service: it resolves to COMPLETED (the credit had already landed), COMPENSATED
     * (the source was credited back), or COMPENSATION_FAILED (manual review).
     */
    COMPENSATION_REQUIRED,
    /** The destination definitively never received the credit, and the source was credited back. */
    COMPENSATED,
    /** Both the destination credit and the source credit-back definitively failed. Manual review. */
    COMPENSATION_FAILED
}
