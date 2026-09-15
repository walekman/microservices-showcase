package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTypeTest {

    @Test
    void completedMapsToTransferCompleted() {
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPLETED)).isEqualTo(OutboxEventType.TRANSFER_COMPLETED);
    }

    @Test
    void failedCompensatedAndCompensationFailedAllMapToTransferFailed() {
        assertThat(OutboxEventType.forStatus(TransferStatus.FAILED)).isEqualTo(OutboxEventType.TRANSFER_FAILED);
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPENSATED)).isEqualTo(OutboxEventType.TRANSFER_FAILED);
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPENSATION_FAILED)).isEqualTo(OutboxEventType.TRANSFER_FAILED);
    }

    @Test
    void nonTerminalStatusesMapToNoEvent() {
        assertThat(OutboxEventType.forStatus(TransferStatus.PENDING)).isNull();
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPENSATION_REQUIRED)).isNull();
    }
}
