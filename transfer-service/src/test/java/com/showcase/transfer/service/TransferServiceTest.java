package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.client.FraudClient;
import com.showcase.transfer.client.FraudRejectedException;
import com.showcase.transfer.client.FraudServiceUnavailableException;
import com.showcase.transfer.client.FxClient;
import com.showcase.transfer.client.FxRate;
import com.showcase.transfer.client.FxServiceUnavailableException;
import com.showcase.transfer.domain.DebitOutcomeUnknownException;
import com.showcase.transfer.domain.IdempotencyKeyConflictException;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferInProgressException;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");
    private static final UUID INITIATOR_ID = UUID.randomUUID();
    private static final String KEY = "client-key-1";
    // The mock repository below never runs real JPA id generation, so transfer.getId() would
    // otherwise stay null throughout every test -- repositoryEchoesSaves() assigns this
    // instead, the way a real save() would, so the idempotency keys TransferService builds
    // from transfer.getId() are stable and assertable.
    private static final UUID TRANSFER_ID = UUID.randomUUID();

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

    @Mock
    private FraudClient fraudClient;

    @Mock
    private FxClient fxClient;

    @Mock
    private TransferSaveService transferSaveService;

    private TransferService transferService;

    /**
     * The status of each Transfer as it was at the moment {@code save} was called.
     *
     * <p>An ArgumentCaptor cannot express this: the repository echoes back the same mutable
     * entity, so every captured value is the one instance, and by assertion time it carries
     * only its final status. Snapshotting inside the stub is the only way to pin "PENDING was
     * persisted before any money moved".
     */
    private final List<TransferStatus> statusesAtSaveTime = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, accountClient, fraudClient, fxClient, transferSaveService);
        // Every account exists, in EUR, unless a test says otherwise (with doThrow/doReturn,
        // which do not invoke this stub). lenient: the replay tests never reach pre-validation.
        lenient().when(accountClient.accountCurrency(any())).thenReturn("EUR");
    }

    // Called per-test rather than from setUp: the self-transfer test never reaches the
    // repository, and Mockito strict stubbing rightly fails an unused stub. Keeping
    // strict stubbing is worth the extra line.
    // lenient: a transfer that fails pre-validation is inserted once, through transferSaveService,
    // and never touches transferRepository.save -- strict stubbing would flag that stub as unused.
    private void repositoryEchoesSaves() {
        lenient().when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
            }
            statusesAtSaveTime.add(saved.getStatus());
            return saved;
        });
        lenient().when(transferSaveService.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
            }
            statusesAtSaveTime.add(saved.getStatus());
            // Return the transfer without calling transferRepository.save() to avoid double-capture
            return saved;
        });
    }

    // setUp's lenient default already answers accountCurrency with "EUR" for every account, so
    // "both accounts exist" needs no stubbing of its own.
    private void bothAccountsExist() {
        // no-op: kept as a named no-op so every test's intent stays readable at the call site.
    }

    private void bothAccountsExistAndFraudClear() {
        bothAccountsExist();
        // Explicitly stub fraud checks to not throw by default (void methods don't throw in Mockito
        // unless stubbed to, but this makes it explicit for strict stubbing verification).
        // Use lenient() because this stub may be overridden by more specific stubs in individual tests.
        org.mockito.Mockito.lenient().doNothing().when(fraudClient).check(any());
    }

    @Test
    void completesWhenBothLegsSucceed() {
        repositoryEchoesSaves();
        bothAccountsExist();

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getSettledAt()).isNotNull();
        assertThat(result.getFailureCode()).isNull();
        verify(accountClient).debit(FROM, AMOUNT, "EUR", TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, AMOUNT, "EUR", TRANSFER_ID + ":credit");

        // PENDING must be durable BEFORE any money moves, so a crash mid-saga leaves
        // evidence. Without these two assertions, moving the first save to the end of
        // execute() would leave every other test in this class green.
        InOrder inOrder = inOrder(transferRepository, accountClient);
        inOrder.verify(transferRepository).save(any(Transfer.class));
        inOrder.verify(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());
        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.PENDING, TransferStatus.COMPLETED);
        // Proves execute()'s happy-path completion routes its terminal write through the
        // outbox choke point, not straight to transferRepository -- without this, a
        // regression back to transferRepository.save(transfer) here would leave every other
        // assertion in this test green (repositoryEchoesSaves() stubs both mocks to behave
        // the same way).
        verify(transferSaveService).save(any(Transfer.class));
    }

    @Test
    void failsWithoutDebitingWhenTheSourceAccountDoesNotExist() {
        repositoryEchoesSaves();
        org.mockito.Mockito.doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM))
                .when(accountClient).accountCurrency(FROM);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any(), any());
        // Proves fail()'s terminal write routes through the outbox choke point.
        verify(transferSaveService).save(any(Transfer.class));
    }

    @Test
    void failsWithoutDebitingWhenTheDestinationAccountDoesNotExist() {
        repositoryEchoesSaves();
        org.mockito.Mockito.doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).accountCurrency(TO);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any(), any());
    }

    @Test
    void failsWhenAccountServiceIsUnreachableDuringPreValidation() {
        repositoryEchoesSaves();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("connection refused"))
                .when(accountClient).accountCurrency(FROM);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        verify(accountClient, never()).debit(any(), any(), any(), any());
    }

    @Test
    void failsWhenTheDebitIsRejectedForInsufficientFunds() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("INSUFFICIENT_FUNDS", "not enough money"))
                .when(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(result.getFailureReason()).isEqualTo("not enough money");
        verify(accountClient, never()).credit(any(), any(), any(), any());
    }

    @Test
    void leavesTheTransferPendingWhenTheDebitOutcomeIsUnknown() {
        // Only the PENDING insert is stubbed, not repositoryEchoesSaves(): this path must never
        // reach transferSaveService, and strict stubbing would fail its unused stub.
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
            statusesAtSaveTime.add(saved.getStatus());
            return saved;
        });
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());

        assertThatThrownBy(() -> transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY))
                .isInstanceOf(DebitOutcomeUnknownException.class)
                .extracting("transferId").isEqualTo(TRANSFER_ID);

        // The debit may have committed, so the row must not be settled: it stays PENDING for
        // CompensationScheduler's stale-PENDING sweep to reconcile by replaying the debit key.
        // Only the initial PENDING insert was persisted -- no FAILED write, so no outbox event.
        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.PENDING);
        verify(transferSaveService, never()).save(any(Transfer.class));
        verify(accountClient, never()).credit(any(), any(), any(), any());
    }

    @Test
    void requiresCompensationWhenTheCreditIsRejected() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), eq("EUR"), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());
        // Proves strand()'s terminal write routes through the outbox choke point (even though
        // COMPENSATION_REQUIRED itself writes no outbox row, per OutboxEventType.forStatus).
        verify(transferSaveService).save(any(Transfer.class));
    }

    @Test
    void requiresCompensationWhenTheCreditCannotReachAccountService() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), eq("EUR"), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        assertThat(result.getFailureReason()).contains("read timed out");
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> transferService.execute(FROM, FROM, AMOUNT, INITIATOR_ID, KEY))
                .isInstanceOf(SameAccountTransferException.class);

        verify(accountClient, never()).accountCurrency(any());
        // Passes today only because the constructor throws while the save argument is being
        // evaluated. Worth pinning: a rejected transfer must leave no row behind.
        verify(transferRepository, never()).save(any());
    }

    @Test
    void failsWhenAnUnexpectedErrorOccursBeforeTheDebit() {
        repositoryEchoesSaves();
        org.mockito.Mockito.doThrow(new IllegalStateException("response mapper exploded"))
                .when(accountClient).accountCurrency(FROM);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
        assertThat(result.getFailureReason()).contains("response mapper exploded");
        verify(accountClient, never()).debit(any(), any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any(), any());
    }

    @Test
    void requiresCompensationWhenAnUnexpectedErrorFollowsTheDebit() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new IllegalStateException("response mapper exploded"))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), eq("EUR"), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
        assertThat(result.getFailureReason()).contains("response mapper exploded");
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());
    }

    @Test
    void wrapsTheCauseWithTheTransferIdWhenPersistingTheTerminalStateFails() {
        OptimisticLockingFailureException boom = new OptimisticLockingFailureException("version conflict");
        // First save (PENDING) succeeds; the final save of the terminal state blows up.
        when(transferRepository.save(any(Transfer.class)))
                .thenAnswer(invocation -> {
                    Transfer saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
                    }
                    return saved;
                })
                .thenThrow(boom);
        when(transferSaveService.save(any(Transfer.class))).thenThrow(boom);
        bothAccountsExist();

        // The entity is already COMPLETED in memory, so re-marking it would throw
        // IllegalStateException from requirePending() and bury the real cause. The original
        // exception is wrapped rather than re-marked or swallowed: the wrapper carries the
        // id so the API can hand it back -- a row exists, still reading PENDING, and the
        // caller cannot reconcile anything without knowing which one -- and keeps the
        // original as its cause, which is the only real diagnostic.
        assertThatThrownBy(() -> transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY))
                .isInstanceOf(TransferPersistenceException.class)
                .hasCause(boom);

        verify(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());
        verify(accountClient).credit(eq(TO), eq(AMOUNT), eq("EUR"), any());
    }

    @Test
    void passesADeterministicIdempotencyKeyPerLeg() {
        repositoryEchoesSaves();
        bothAccountsExist();

        transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        verify(accountClient).debit(FROM, AMOUNT, "EUR", TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, AMOUNT, "EUR", TRANSFER_ID + ":credit");
    }

    @Test
    void failsWithoutDebitingWhenTheSourceAccountIsBlocked() {
        repositoryEchoesSaves();
        bothAccountsExistAndFraudClear();
        org.mockito.Mockito.doThrow(new FraudRejectedException("Account is blocklisted: " + FROM))
                .when(fraudClient).check(FROM);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.SOURCE_ACCOUNT_BLOCKED);
        verify(accountClient, never()).debit(any(), any(), any(), any());
        verify(fraudClient, never()).check(TO);
    }

    @Test
    void failsWhenFraudServiceIsUnreachableCheckingTheSource() {
        repositoryEchoesSaves();
        bothAccountsExistAndFraudClear();
        org.mockito.Mockito.doThrow(new FraudServiceUnavailableException("read timed out"))
                .when(fraudClient).check(FROM);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.SOURCE_FRAUD_SERVICE_UNAVAILABLE);
        assertThat(result.getFailureReason()).contains("read timed out");
        verify(accountClient, never()).debit(any(), any(), any(), any());
    }

    @Test
    void requiresCompensationWhenTheDestinationAccountIsBlocked() {
        repositoryEchoesSaves();
        bothAccountsExistAndFraudClear();
        org.mockito.Mockito.doThrow(new FraudRejectedException("Account is blocklisted: " + TO))
                .when(fraudClient).check(TO);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED);
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), eq("EUR"), any());
        verify(accountClient, never()).credit(any(), any(), any(), any());
    }

    @Test
    void requiresCompensationWhenFraudServiceIsUnreachableCheckingTheDestination() {
        repositoryEchoesSaves();
        bothAccountsExistAndFraudClear();
        org.mockito.Mockito.doThrow(new FraudServiceUnavailableException("read timed out"))
                .when(fraudClient).check(TO);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.DESTINATION_FRAUD_SERVICE_UNAVAILABLE);
        assertThat(result.getFailureReason()).contains("read timed out");
        verify(accountClient, never()).credit(any(), any(), any(), any());
    }

    @Test
    void getTransferThrowsWhenTheTransferDoesNotExist() {
        UUID missing = UUID.randomUUID();
        when(transferRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransfer(missing, INITIATOR_ID))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void getTransferReturnsItForTheInitiator() {
        UUID id = UUID.randomUUID();
        Transfer transfer = new Transfer(FROM, TO, AMOUNT, INITIATOR_ID);
        when(transferRepository.findById(id)).thenReturn(Optional.of(transfer));

        Transfer result = transferService.getTransfer(id, INITIATOR_ID);

        assertThat(result).isSameAs(transfer);
        verify(accountClient, never()).isOwnedByCaller(any());
    }

    @Test
    void getTransferReturnsItForTheDestinationOwner() {
        UUID id = UUID.randomUUID();
        UUID destinationOwner = UUID.randomUUID();
        Transfer transfer = new Transfer(FROM, TO, AMOUNT, INITIATOR_ID);
        when(transferRepository.findById(id)).thenReturn(Optional.of(transfer));
        when(accountClient.isOwnedByCaller(TO)).thenReturn(true);

        Transfer result = transferService.getTransfer(id, destinationOwner);

        assertThat(result).isSameAs(transfer);
    }

    @Test
    void getTransferThrowsForACallerWhoIsNeitherTheInitiatorNorTheDestinationOwner() {
        UUID id = UUID.randomUUID();
        Transfer transfer = new Transfer(FROM, TO, AMOUNT, INITIATOR_ID);
        when(transferRepository.findById(id)).thenReturn(Optional.of(transfer));
        when(accountClient.isOwnedByCaller(TO)).thenReturn(false);

        assertThatThrownBy(() -> transferService.getTransfer(id, UUID.randomUUID()))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void listMyTransfersReturnsOutgoingAndCompletedIncomingTransfers() {
        UUID myAccount = UUID.randomUUID();
        Transfer outgoing = new Transfer(myAccount, TO, AMOUNT, INITIATOR_ID);
        Transfer incoming = new Transfer(FROM, myAccount, AMOUNT, UUID.randomUUID());
        incoming.markCompleted();
        when(transferRepository.findByInitiatorId(INITIATOR_ID)).thenReturn(List.of(outgoing));
        when(accountClient.callerAccountIds()).thenReturn(List.of(myAccount));
        when(transferRepository.findByToAccountIdInAndStatus(List.of(myAccount), TransferStatus.COMPLETED))
                .thenReturn(List.of(incoming));

        assertThat(transferService.listMyTransfers(INITIATOR_ID, null)).containsExactly(outgoing, incoming);
    }

    @Test
    void listMyTransfersDoesNotListTheCallersOwnTransferTwice() {
        // Unreachable today (SAME_ACCOUNT_TRANSFER, one account per owner), but a transfer the
        // caller started into an account they own would otherwise match both queries.
        UUID myAccount = UUID.randomUUID();
        UUID mySecondAccount = UUID.randomUUID();
        Transfer toMyself = new Transfer(myAccount, mySecondAccount, AMOUNT, INITIATOR_ID);
        toMyself.markCompleted();
        when(transferRepository.findByInitiatorId(INITIATOR_ID)).thenReturn(List.of(toMyself));
        when(accountClient.callerAccountIds()).thenReturn(List.of(myAccount, mySecondAccount));
        when(transferRepository.findByToAccountIdInAndStatus(List.of(myAccount, mySecondAccount), TransferStatus.COMPLETED))
                .thenReturn(List.of(toMyself));

        assertThat(transferService.listMyTransfers(INITIATOR_ID, null)).containsExactly(toMyself);
    }

    @Test
    void listMyTransfersFiltersIncomingByStatusToo() {
        UUID myAccount = UUID.randomUUID();
        when(transferRepository.findByInitiatorIdAndStatus(INITIATOR_ID, TransferStatus.COMPLETED)).thenReturn(List.of());
        when(accountClient.callerAccountIds()).thenReturn(List.of(myAccount));

        transferService.listMyTransfers(INITIATOR_ID, TransferStatus.COMPLETED);

        verify(transferRepository).findByToAccountIdInAndStatus(List.of(myAccount), TransferStatus.COMPLETED);
    }

    @Test
    void listMyTransfersSkipsIncomingForAnyStatusButCompleted() {
        // Incoming transfers are only ever shown once the money has landed, so a FAILED filter
        // has no incoming half at all -- and no reason to call Account.
        transferService.listMyTransfers(INITIATOR_ID, TransferStatus.FAILED);

        verify(transferRepository).findByInitiatorIdAndStatus(INITIATOR_ID, TransferStatus.FAILED);
        verify(accountClient, never()).callerAccountIds();
    }

    @Test
    void listMyTransfersSkipsTheIncomingQueryForACallerWithNoAccount() {
        when(accountClient.callerAccountIds()).thenReturn(List.of());

        transferService.listMyTransfers(INITIATOR_ID, null);

        verify(transferRepository, never()).findByToAccountIdInAndStatus(any(), any());
    }

    @Test
    void listMyTransfersPropagatesAccountBeingUnavailable() {
        // Answering with the outgoing half alone would silently hide money the caller received.
        when(accountClient.callerAccountIds()).thenThrow(new AccountServiceUnavailableException("down"));

        assertThatThrownBy(() -> transferService.listMyTransfers(INITIATOR_ID, null))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void listTransfersFiltersByStatusOnlyWhenOneIsGiven() {
        transferService.listTransfers(null);
        verify(transferRepository).findAll();
        verify(transferRepository, never()).findByStatus(any());

        transferService.listTransfers(TransferStatus.FAILED);
        verify(transferRepository).findByStatus(TransferStatus.FAILED);
    }

    // --- Idempotency-Key on POST /transfers ---

    private Transfer earlierTransfer(TransferStatus status) {
        Transfer earlier = new Transfer(FROM, TO, AMOUNT, INITIATOR_ID, KEY);
        ReflectionTestUtils.setField(earlier, "id", TRANSFER_ID);
        switch (status) {
            case PENDING -> { }
            case COMPLETED -> earlier.markCompleted();
            case FAILED -> earlier.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
            default -> throw new IllegalArgumentException("not needed by these tests: " + status);
        }
        return earlier;
    }

    @Test
    void persistsTheIdempotencyKeyOnTheNewTransfer() {
        repositoryEchoesSaves();

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getIdempotencyKey()).isEqualTo(KEY);
    }

    @Test
    void replaysACompletedTransferWithoutMovingMoneyAgain() {
        Transfer earlier = earlierTransfer(TransferStatus.COMPLETED);
        when(transferRepository.findByInitiatorIdAndIdempotencyKey(INITIATOR_ID, KEY)).thenReturn(Optional.of(earlier));

        // "40" rather than "40.00": the same amount at a different scale is the same request.
        Transfer result = transferService.execute(FROM, TO, new BigDecimal("40"), INITIATOR_ID, KEY);

        assertThat(result).isSameAs(earlier);
        verify(transferRepository, never()).save(any());
        verifyNoInteractions(accountClient, fraudClient, fxClient, transferSaveService);
    }

    @Test
    void replaysAFailedTransferAsItsRecordedFailure() {
        Transfer earlier = earlierTransfer(TransferStatus.FAILED);
        when(transferRepository.findByInitiatorIdAndIdempotencyKey(INITIATOR_ID, KEY)).thenReturn(Optional.of(earlier));

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result).isSameAs(earlier);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        verifyNoInteractions(accountClient, fraudClient, fxClient, transferSaveService);
    }

    @Test
    void replayOfAStillPendingTransferReportsItInProgress() {
        when(transferRepository.findByInitiatorIdAndIdempotencyKey(INITIATOR_ID, KEY))
                .thenReturn(Optional.of(earlierTransfer(TransferStatus.PENDING)));

        assertThatThrownBy(() -> transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY))
                .isInstanceOfSatisfying(TransferInProgressException.class,
                        ex -> assertThat(ex.getTransferId()).isEqualTo(TRANSFER_ID));
        verifyNoInteractions(accountClient, fraudClient, fxClient, transferSaveService);
    }

    @Test
    void rejectsAKeyReusedForADifferentTransfer() {
        when(transferRepository.findByInitiatorIdAndIdempotencyKey(INITIATOR_ID, KEY))
                .thenReturn(Optional.of(earlierTransfer(TransferStatus.COMPLETED)));

        assertThatThrownBy(() -> transferService.execute(FROM, TO, new BigDecimal("41.00"), INITIATOR_ID, KEY))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        assertThatThrownBy(() -> transferService.execute(FROM, UUID.randomUUID(), AMOUNT, INITIATOR_ID, KEY))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        verifyNoInteractions(accountClient, fraudClient, fxClient, transferSaveService);
    }

    @Test
    void losingTheInsertRaceReplaysTheWinner() {
        // Both requests missed the lookup; the unique constraint rejected this one's insert.
        Transfer winner = earlierTransfer(TransferStatus.PENDING);
        when(transferRepository.findByInitiatorIdAndIdempotencyKey(INITIATOR_ID, KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(transferRepository.save(any(Transfer.class)))
                .thenThrow(new DataIntegrityViolationException("uk_transfers_initiator_idempotency_key"));

        assertThatThrownBy(() -> transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY))
                .isInstanceOf(TransferInProgressException.class);
        // prepare() ran before the insert -- by design, it only reads -- but nothing moved.
        verify(accountClient, never()).debit(any(), any(), any(), any());
        verifyNoInteractions(fraudClient, transferSaveService);
    }

    @Test
    void rethrowsAnIntegrityViolationThatIsNotAKeyCollision() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("something else");
        when(transferRepository.save(any(Transfer.class))).thenThrow(violation);

        assertThatThrownBy(() -> transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY))
                .isSameAs(violation);
        verify(accountClient, never()).debit(any(), any(), any(), any());
        verifyNoInteractions(fraudClient, transferSaveService);
    }

    // --- Phase 12: pricing and the locked conversion ---

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);
    private static final FxRate PLN_TO_EUR = new FxRate("PLN", "EUR", new BigDecimal("0.22819"), AS_OF, false);

    private void sourceInPlnDestinationInEur() {
        doReturn("PLN").when(accountClient).accountCurrency(FROM);
        doReturn("EUR").when(accountClient).accountCurrency(TO);
    }

    @Test
    void aCrossCurrencyTransferDebitsTheAmountAndCreditsTheLockedConversion() {
        repositoryEchoesSaves();
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenReturn(PLN_TO_EUR);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getCreditAmount()).isEqualByComparingTo("9.13"); // 40.00 * 0.22819 = 9.1276
        assertThat(result.getRate()).isEqualByComparingTo("0.22819");
        assertThat(result.getRateAsOf()).isEqualTo(AS_OF);
        verify(accountClient).debit(FROM, AMOUNT, "PLN", TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, new BigDecimal("9.13"), "EUR", TRANSFER_ID + ":credit");
    }

    // Review Focus #2: no PENDING row may ever exist without the amounts a sweep must replay.
    @Test
    void theConversionIsOnTheRowAtItsFirstInsertBeforeAnyMoneyMoves() {
        List<BigDecimal> creditAmountAtInsert = new ArrayList<>();
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
            creditAmountAtInsert.add(saved.getCreditAmount());
            return saved;
        });
        when(transferSaveService.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenReturn(PLN_TO_EUR);

        transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        InOrder inOrder = inOrder(fxClient, transferRepository, accountClient);
        inOrder.verify(fxClient).rate("PLN", "EUR");
        inOrder.verify(transferRepository).save(any(Transfer.class));
        inOrder.verify(accountClient).debit(any(), any(), any(), any());
        assertThat(creditAmountAtInsert).containsExactly(new BigDecimal("9.13"));
    }

    @Test
    void aSameCurrencyTransferNeverCallsFx() {
        repositoryEchoesSaves();

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getSourceCurrency()).isEqualTo("EUR");
        assertThat(result.getRate()).isEqualByComparingTo("1");
        assertThat(result.getCreditAmount()).isEqualByComparingTo(AMOUNT);
        verifyNoInteractions(fxClient);
    }

    @Test
    void anFxOutageFailsTheTransferInOneInsertWithNothingMoved() {
        repositoryEchoesSaves();
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenThrow(new FxServiceUnavailableException("FX Service returned 503"));

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.FX_SERVICE_UNAVAILABLE);
        assertThat(result.getCreditAmount()).isNull();
        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.FAILED);
        verify(transferRepository, never()).save(any());
        verifyNoInteractions(fraudClient);
        verify(accountClient, never()).debit(any(), any(), any(), any());
    }

    @Test
    void anAmountThatConvertsToNothingIsRejectedBeforeAnyMoneyMoves() {
        repositoryEchoesSaves();
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenReturn(PLN_TO_EUR);

        // 0.01 PLN * 0.22819 = 0.0022819 -> 0.00 EUR
        Transfer result = transferService.execute(FROM, TO, new BigDecimal("0.01"), INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.AMOUNT_TOO_SMALL);
        verify(accountClient, never()).debit(any(), any(), any(), any());
    }

    @Test
    void aFailedPreValidationIsInsertedOnceThroughTheOutboxChokePoint() {
        repositoryEchoesSaves();
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).accountCurrency(TO);

        transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.FAILED);
        verify(transferRepository, never()).save(any());
        verify(transferSaveService).save(any(Transfer.class));
    }
}
