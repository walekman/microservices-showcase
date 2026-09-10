package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal TEN = new BigDecimal("10.00");

    @Test
    void newTransferStartsPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getFromAccountId()).isEqualTo(FROM);
        assertThat(transfer.getToAccountId()).isEqualTo(TO);
        assertThat(transfer.getAmount()).isEqualByComparingTo("10.00");
        assertThat(transfer.getCreatedAt()).isNotNull();
        assertThat(transfer.getSettledAt()).isNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> new Transfer(FROM, FROM, TEN))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void rejectsANonPositiveAmount() {
        assertThatThrownBy(() -> new Transfer(FROM, TO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(FROM, TO, new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markCompletedSettlesTheTransfer() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markCompleted();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getSettledAt()).isNotNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void markFailedRecordsTheReason() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(transfer.getFailureReason()).isEqualTo("not enough money");
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensationRequiredRecordsTheUnderlyingCause() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        assertThat(transfer.getFailureReason()).isEqualTo("credit leg timed out");
    }

    @Test
    void aSettledTransferCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompleted();

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsANullAccountId() {
        assertThatThrownBy(() -> new Transfer(null, TO, TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(FROM, null, TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesAnOverlongFailureReason() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x".repeat(600));

        assertThat(transfer.getFailureReason()).hasSize(512);
    }

    @Test
    void keepsAFailureReasonOfExactlyTheColumnLength() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x".repeat(512));

        assertThat(transfer.getFailureReason()).hasSize(512);
    }

    @Test
    void doesNotSplitASurrogatePairWhenTruncating() {
        // U+1F600 is two chars in UTF-16. Placed after 511 filler chars it straddles the
        // 512-char boundary: high surrogate at index 511, low surrogate at index 512.
        String emoji = new String(Character.toChars(0x1F600));
        String reason = "x".repeat(511) + emoji + "y".repeat(100);
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, reason);

        String stored = transfer.getFailureReason();
        assertThat(stored).hasSize(511);
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 1))).isFalse();
        assertThat(stored.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    void aFailedTransferCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTransferAwaitingCompensationCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
