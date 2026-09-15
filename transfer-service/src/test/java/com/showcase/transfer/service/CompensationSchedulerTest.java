package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompensationSchedulerTest {

    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

    @Mock
    private TransferSaveService transferSaveService;

    private CompensationScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Default behavior: transferSaveService.save() delegates to transferRepository.save()
        // so that tests verifying transferRepository.save() interactions still work.
        // Use lenient() because some tests (when Account Service is unavailable) never reach save() calls.
        lenient().when(transferSaveService.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            return transferRepository.save(transfer);
        });
        scheduler = new CompensationScheduler(transferRepository, accountClient,
                new CompensationProperties(Duration.ofSeconds(15), Duration.ofSeconds(120), 500), transferSaveService);
    }

    private Transfer strandedTransfer() {
        return strandedTransfer(TRANSFER_ID, FROM, TO);
    }

    private Transfer strandedTransfer(UUID id, UUID from, UUID to) {
        Transfer transfer = new Transfer(from, to, AMOUNT);
        ReflectionTestUtils.setField(transfer, "id", id);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        return transfer;
    }

    private Transfer stalePendingTransfer() {
        return stalePendingTransfer(TRANSFER_ID, FROM, TO);
    }

    private Transfer stalePendingTransfer(UUID id, UUID from, UUID to) {
        Transfer transfer = new Transfer(from, to, AMOUNT);
        ReflectionTestUtils.setField(transfer, "id", id);
        return transfer;
    }

    @Test
    void reconciledSuccessMarksTheTransferCompleted() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        // credit(...) succeeds by default (void mock, no stubbing needed) -- the destination
        // had actually already received the money.

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        verify(accountClient, never()).credit(eq(FROM), any(), any());
        verify(transferRepository).save(transfer);
        // Proves reconcileCredit's success branch actually routes its terminal write through
        // the outbox choke point rather than transferRepository directly -- without this, a
        // regression back to a direct transferRepository.save(transfer) call here would leave
        // every assertion in this test still green (the setUp() stub makes
        // transferSaveService.save() delegate to transferRepository.save() either way).
        verify(transferSaveService).save(transfer);
    }

    @Test
    void definitiveRejectionThenSuccessfulReversalMarksCompensated() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        // The source credit-back succeeds (void mock, no stubbing needed).

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        verify(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");
        // Proves compensateSource's success branch routes through the outbox choke point.
        verify(transferSaveService).save(transfer);
    }

    @Test
    void definitiveRejectionThenReversalAlsoRejectedMarksCompensationFailed() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM))
                .when(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        assertThat(transfer.getFailureReason()).contains("manual review required");
        // Proves compensateSource's catch(AccountRejectedException) branch routes through the
        // outbox choke point.
        verify(transferSaveService).save(transfer);
    }

    @Test
    void stillUnavailableLeavesTheTransferAwaitingTheNextSweep() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void destinationRejectedButSourceReversalStillUnavailableLeavesAwaitingTheNextSweep() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void anUnexpectedSaveFailureDoesNotBlockOtherTransfersInTheBatch() {
        Transfer failing = strandedTransfer();
        Transfer succeeding = strandedTransfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any()))
                .thenReturn(List.of(failing, succeeding));
        when(transferRepository.save(failing)).thenThrow(new OptimisticLockingFailureException("stale row"));
        when(transferRepository.save(succeeding)).thenReturn(succeeding);
        // credit(...) succeeds for both (void mock, no stubbing needed).

        scheduler.drainCompensationRequired();

        assertThat(succeeding.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(transferRepository).save(succeeding);
    }

    @Test
    void staleDebitRejectedMarksTheTransferFailed() {
        Transfer transfer = stalePendingTransfer();
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any(), any()))
                .thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM))
                .when(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");

        scheduler.sweepStalePending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).credit(any(), any(), any());
        // Proves reconcileDebit's catch(AccountRejectedException) branch routes through the
        // outbox choke point.
        verify(transferSaveService).save(transfer);
    }

    @Test
    void staleDebitConfirmedLandedPromotesToCompensationRequired() {
        Transfer transfer = stalePendingTransfer();
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any(), any()))
                .thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        // debit(...) succeeds by default (void mock, no stubbing needed).

        scheduler.sweepStalePending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void staleDebitStillAmbiguousStaysPending() {
        Transfer transfer = stalePendingTransfer();
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any(), any()))
                .thenReturn(List.of(transfer));
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");

        scheduler.sweepStalePending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void aStaleSweepUnexpectedSaveFailureDoesNotBlockOtherTransfersInTheBatch() {
        Transfer failing = stalePendingTransfer();
        Transfer succeeding = stalePendingTransfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any(), any()))
                .thenReturn(List.of(failing, succeeding));
        when(transferRepository.save(failing)).thenThrow(new OptimisticLockingFailureException("stale row"));
        when(transferRepository.save(succeeding)).thenReturn(succeeding);
        // debit(...) succeeds for both (void mock, no stubbing needed).

        scheduler.sweepStalePending();

        assertThat(succeeding.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(transferRepository).save(succeeding);
    }
}
