package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.client.AccountView;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");
    // The mock repository below never runs real JPA id generation, so transfer.getId() would
    // otherwise stay null throughout every test -- repositoryEchoesSaves() assigns this
    // instead, the way a real save() would, so the idempotency keys TransferService builds
    // from transfer.getId() are stable and assertable.
    private static final UUID TRANSFER_ID = UUID.randomUUID();

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

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
        transferService = new TransferService(transferRepository, accountClient);
    }

    // Called per-test rather than from setUp: the self-transfer test never reaches the
    // repository, and Mockito strict stubbing rightly fails an unused stub. Keeping
    // strict stubbing is worth the extra line.
    private void repositoryEchoesSaves() {
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
            }
            statusesAtSaveTime.add(saved.getStatus());
            return saved;
        });
    }

    private void bothAccountsExist() {
        when(accountClient.getAccount(FROM)).thenReturn(new AccountView(FROM, new BigDecimal("100.00")));
        when(accountClient.getAccount(TO)).thenReturn(new AccountView(TO, new BigDecimal("5.00")));
    }

    @Test
    void completesWhenBothLegsSucceed() {
        repositoryEchoesSaves();
        bothAccountsExist();

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getSettledAt()).isNotNull();
        assertThat(result.getFailureCode()).isNull();
        verify(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");

        // PENDING must be durable BEFORE any money moves, so a crash mid-saga leaves
        // evidence. Without these two assertions, moving the first save to the end of
        // execute() would leave every other test in this class green.
        InOrder inOrder = inOrder(transferRepository, accountClient);
        inOrder.verify(transferRepository).save(any(Transfer.class));
        inOrder.verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.PENDING, TransferStatus.COMPLETED);
    }

    @Test
    void failsWithoutDebitingWhenTheSourceAccountDoesNotExist() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM))
                .thenThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void failsWithoutDebitingWhenTheDestinationAccountDoesNotExist() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM)).thenReturn(new AccountView(FROM, new BigDecimal("100.00")));
        when(accountClient.getAccount(TO))
                .thenThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void failsWhenAccountServiceIsUnreachableDuringPreValidation() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM))
                .thenThrow(new AccountServiceUnavailableException("connection refused"));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        verify(accountClient, never()).debit(any(), any(), any());
    }

    @Test
    void failsWhenTheDebitIsRejectedForInsufficientFunds() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("INSUFFICIENT_FUNDS", "not enough money"))
                .when(accountClient).debit(eq(FROM), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(result.getFailureReason()).isEqualTo("not enough money");
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void failsWhenTheDebitCannotReachAccountService() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).debit(eq(FROM), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        // Pins ex.getMessage() as the recorded reason on the unavailable path, the way
        // ex.getDetail() is pinned on the rejected path.
        assertThat(result.getFailureReason()).contains("read timed out");
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void requiresCompensationWhenTheCreditIsRejected() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
    }

    @Test
    void requiresCompensationWhenTheCreditCannotReachAccountService() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        assertThat(result.getFailureReason()).contains("read timed out");
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> transferService.execute(FROM, FROM, AMOUNT))
                .isInstanceOf(SameAccountTransferException.class);

        verify(accountClient, never()).getAccount(any());
        // Passes today only because the constructor throws while the save argument is being
        // evaluated. Worth pinning: a rejected transfer must leave no row behind.
        verify(transferRepository, never()).save(any());
    }

    @Test
    void failsWhenAnUnexpectedErrorOccursBeforeTheDebit() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM)).thenThrow(new IllegalStateException("response mapper exploded"));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
        assertThat(result.getFailureReason()).contains("response mapper exploded");
        verify(accountClient, never()).debit(any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void requiresCompensationWhenAnUnexpectedErrorFollowsTheDebit() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new IllegalStateException("response mapper exploded"))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
        assertThat(result.getFailureReason()).contains("response mapper exploded");
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
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
        bothAccountsExist();

        // The entity is already COMPLETED in memory, so re-marking it would throw
        // IllegalStateException from requirePending() and bury the real cause. The original
        // exception is wrapped rather than re-marked or swallowed: the wrapper carries the
        // id so the API can hand it back -- a row exists, still reading PENDING, and the
        // caller cannot reconcile anything without knowing which one -- and keeps the
        // original as its cause, which is the only real diagnostic.
        assertThatThrownBy(() -> transferService.execute(FROM, TO, AMOUNT))
                .isInstanceOf(TransferPersistenceException.class)
                .hasCause(boom);

        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
        verify(accountClient).credit(eq(TO), eq(AMOUNT), any());
    }

    @Test
    void passesADeterministicIdempotencyKeyPerLeg() {
        repositoryEchoesSaves();
        bothAccountsExist();

        transferService.execute(FROM, TO, AMOUNT);

        verify(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
    }

    @Test
    void getTransferThrowsWhenTheTransferDoesNotExist() {
        UUID missing = UUID.randomUUID();
        when(transferRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransfer(missing))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void listTransfersFiltersByStatusOnlyWhenOneIsGiven() {
        transferService.listTransfers(null);
        verify(transferRepository).findAll();
        verify(transferRepository, never()).findByStatus(any());

        transferService.listTransfers(TransferStatus.FAILED);
        verify(transferRepository).findByStatus(TransferStatus.FAILED);
    }
}
