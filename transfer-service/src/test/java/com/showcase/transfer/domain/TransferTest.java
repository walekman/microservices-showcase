package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal TEN = new BigDecimal("10.00");
    private static final UUID INITIATOR = UUID.randomUUID();

    @Test
    void newTransferStartsPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getFromAccountId()).isEqualTo(FROM);
        assertThat(transfer.getToAccountId()).isEqualTo(TO);
        assertThat(transfer.getAmount()).isEqualByComparingTo("10.00");
        assertThat(transfer.getInitiatorId()).isEqualTo(INITIATOR);
        assertThat(transfer.getCreatedAt()).isNotNull();
        assertThat(transfer.getSettledAt()).isNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> new Transfer(FROM, FROM, TEN, INITIATOR))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void rejectsANonPositiveAmount() {
        assertThatThrownBy(() -> new Transfer(FROM, TO, BigDecimal.ZERO, INITIATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(FROM, TO, new BigDecimal("-1.00"), INITIATOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markCompletedSettlesTheTransfer() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        transfer.markCompleted();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getSettledAt()).isNotNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void markCompletedAlsoSucceedsFromCompensationRequired() {
        // Reconciliation found the destination credit had already landed -- nothing to
        // reverse, the transfer genuinely completed.
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        transfer.markCompleted();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getSettledAt()).isNotNull();
        // The transfer genuinely completed -- an API response for it must not still carry
        // the stranding's stale diagnostics.
        assertThat(transfer.getFailureCode()).isNull();
        assertThat(transfer.getFailureReason()).isNull();
    }

    @Test
    void markFailedRecordsTheReason() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(transfer.getFailureReason()).isEqualTo("not enough money");
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensationRequiredRecordsTheUnderlyingCause() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        assertThat(transfer.getFailureReason()).isEqualTo("credit leg timed out");
    }

    @Test
    void markCompensatedRequiresCompensationRequired() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        transfer.markCompensated();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensatedThrowsFromPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        assertThatThrownBy(transfer::markCompensated).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markCompensationFailedRequiresCompensationRequired() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        transfer.markCompensationFailed("source account no longer exists -- manual review required");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        assertThat(transfer.getFailureReason()).isEqualTo("source account no longer exists -- manual review required");
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensationFailedThrowsFromPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        assertThatThrownBy(() -> transfer.markCompensationFailed("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSettledTransferCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markCompleted();

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(transfer::markCompensated).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationFailed("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsANullAccountId() {
        assertThatThrownBy(() -> new Transfer(null, TO, TEN, INITIATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(FROM, null, TEN, INITIATOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesAnOverlongFailureReason() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x".repeat(600));

        assertThat(transfer.getFailureReason()).hasSize(512);
    }

    @Test
    void keepsAFailureReasonOfExactlyTheColumnLength() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x".repeat(512));

        assertThat(transfer.getFailureReason()).hasSize(512);
    }

    @Test
    void doesNotSplitASurrogatePairWhenTruncating() {
        // U+1F600 is two chars in UTF-16. Placed after 511 filler chars it straddles the
        // 512-char boundary: high surrogate at index 511, low surrogate at index 512.
        String emoji = new String(Character.toChars(0x1F600));
        String reason = "x".repeat(511) + emoji + "y".repeat(100);
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, reason);

        String stored = transfer.getFailureReason();
        assertThat(stored).hasSize(511);
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 1))).isFalse();
        assertThat(stored.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    void aFailedTransferCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(transfer::markCompensated).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationFailed("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTransferAwaitingCompensationCannotBeFailedOrReMarkedCompensationRequired() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);

    @Test
    void creditAmountRoundsHalfEvenToCents() {
        // 0.50 * 0.25 = 0.125 -> 0.12 (to even); 1.50 * 0.25 = 0.375 -> 0.38 (to even)
        assertThat(Transfer.creditAmountFor(new BigDecimal("0.50"), new BigDecimal("0.25"))).isEqualByComparingTo("0.12");
        assertThat(Transfer.creditAmountFor(new BigDecimal("1.50"), new BigDecimal("0.25"))).isEqualByComparingTo("0.38");
    }

    @Test
    void lockConversionFixesEveryConversionField() {
        Transfer transfer = new Transfer(FROM, TO, new BigDecimal("40.00"), INITIATOR);

        transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), AS_OF);

        assertThat(transfer.getSourceCurrency()).isEqualTo("PLN");
        assertThat(transfer.getDestinationCurrency()).isEqualTo("EUR");
        assertThat(transfer.getRate()).isEqualByComparingTo("0.22819");
        assertThat(transfer.getRateAsOf()).isEqualTo(AS_OF);
        assertThat(transfer.getCreditAmount()).isEqualByComparingTo("9.13"); // 9.1276
        assertThat(transfer.amountToCredit()).isEqualByComparingTo("9.13");
    }

    @Test
    void aConversionCanOnlyBeLockedOnce() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.lockConversion("EUR", "EUR", BigDecimal.ONE, null);

        assertThatThrownBy(() -> transfer.lockConversion("EUR", "EUR", BigDecimal.ONE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lockConversionRefusesARateThatCreditsNothing() {
        Transfer transfer = new Transfer(FROM, TO, new BigDecimal("0.01"), INITIATOR);

        assertThatThrownBy(() -> transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), AS_OF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lockConversionIsOnlyForAPendingTransfer() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markFailed(TransferFailureCode.ACCOUNT_NOT_FOUND, "gone");

        assertThatThrownBy(() -> transfer.lockConversion("EUR", "EUR", BigDecimal.ONE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRowWithoutALockedConversionCreditsTheDebitedAmount() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        assertThat(transfer.getCreditAmount()).isNull();
        assertThat(transfer.amountToCredit()).isEqualByComparingTo("10.00");
    }
}
