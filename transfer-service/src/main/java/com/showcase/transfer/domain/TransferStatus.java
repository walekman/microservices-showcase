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
     * stranded at the source. Plan 3 adds a compensator that drains this state by
     * crediting the source back.
     */
    COMPENSATION_REQUIRED
}
