package com.showcase.account.service;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.AccountOperationConflictException;
import com.showcase.account.domain.AccountOperationRepository;
import com.showcase.account.domain.AccountOperationType;
import com.showcase.account.domain.AccountRepository;
import com.showcase.account.domain.CurrencyMismatchException;
import com.showcase.account.domain.InsufficientFundsException;
import com.showcase.account.domain.SupportedCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountOperationRepository accountOperationRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, accountOperationRepository);
    }

    private static final UUID OWNER_ID = UUID.randomUUID();

    @Test
    void createAccountSavesANewAccount() {
        when(accountRepository.existsByOwnerId(OWNER_ID)).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountService.createAccount(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);

        assertThat(result.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(result.getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(result.getBalance()).isEqualByComparingTo("100.00");
        assertThat(result.getCurrency()).isEqualTo(SupportedCurrency.EUR);
        verify(accountRepository).existsByOwnerId(OWNER_ID);
        verify(accountRepository).saveAndFlush(any(Account.class));
    }

    @Test
    void getAllAccountsReturnsEveryAccount() {
        Account first = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);
        Account second = new Account(UUID.randomUUID(), "Alan Turing", new BigDecimal("50.00"), SupportedCurrency.EUR);
        when(accountRepository.findAll()).thenReturn(List.of(first, second));

        List<Account> result = accountService.getAllAccounts();

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void getAccountThrowsWhenAccountIsMissing() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(id, OWNER_ID))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getAccountReturnsItForTheOwningCaller() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        Account result = accountService.getAccount(id, OWNER_ID);

        assertThat(result).isSameAs(account);
    }

    @Test
    void getAccountThrowsForANonOwningCaller() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.getAccount(id, UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getCurrencyReturnsTheAccountsCurrencyRegardlessOfOwnership() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id))
                .thenReturn(Optional.of(new Account(UUID.randomUUID(), "Bob", new BigDecimal("5.00"), SupportedCurrency.GBP)));

        assertThat(accountService.getCurrency(id)).isEqualTo(SupportedCurrency.GBP);
    }

    @Test
    void getCurrencyThrowsForAMissingAccount() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getCurrency(id)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void debitReducesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.debit(id, new BigDecimal("40.00"), null, "key-1", OWNER_ID, false);

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
        verify(accountOperationRepository).saveAndFlush(any(AccountOperation.class));
    }

    @Test
    void debitThrowsForANonOwningCallerAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("40.00"), null, "key-1", UUID.randomUUID(), false))
                .isInstanceOf(AccountNotFoundException.class);
        verify(accountRepository, never()).save(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void debitAllowsTheServiceCallerRegardlessOfOwnership() {
        // Protects CompensationScheduler's reconciliation replay, which runs on transfer-service's
        // own machine identity, never the original customer's token -- see
        // docs/phase-7b-account-ownership-authorization.md's Design Decisions.
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.debit(id, new BigDecimal("40.00"), null, "key-1", UUID.randomUUID(), true);

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsWhenFundsAreInsufficientAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("10.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("40.00"), null, "key-1", OWNER_ID, false))
                .isInstanceOf(InsufficientFundsException.class);
        verify(accountRepository, never()).save(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void creditIncreasesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.credit(id, new BigDecimal("25.00"), null, "key-1");

        assertThat(result.getBalance()).isEqualByComparingTo("125.00");
        verify(accountOperationRepository).saveAndFlush(any(AccountOperation.class));
    }

    @Test
    void replayingAKnownIdempotencyKeyDoesNotReapplyTheDebit() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("60.00"), SupportedCurrency.EUR);
        // Balance already reflects the FIRST application -- if this replay reapplied the
        // debit, it would go to 20.00 instead of staying 60.00.
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.of(
                new AccountOperation("key-1", id, AccountOperationType.DEBIT,
                        new BigDecimal("40.00"), new BigDecimal("60.00"))));

        Account result = accountService.debit(id, new BigDecimal("40.00"), null, "key-1", OWNER_ID, false);

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
        verify(accountRepository, never()).save(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void replayingAKnownKeyWhenTheAccountIsGoneThrowsNotFound() {
        // Pins a documented, currently-unreachable limitation (Phase 3 final-review, see
        // docs/roadmap.md): the replay branch still does an independent account lookup, so
        // an account gone by replay time turns an already-applied operation into a false
        // rejection. Not fixed: no delete/archival capability exists anywhere in this app,
        // so this cannot happen today. If a future phase adds one, this test is the
        // tripwire to revisit AccountService.apply()'s replay branch.
        //
        // Exercised via credit, not debit: debit's ownership pre-check (Phase 7b) now does its
        // own accountRepository.findById() before apply() ever runs, so it would throw
        // AccountNotFoundException first and never actually reach the replay branch this test
        // targets. credit has no ownership check, so it still reaches apply() unmodified.
        UUID id = UUID.randomUUID();
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.of(
                new AccountOperation("key-1", id, AccountOperationType.CREDIT,
                        new BigDecimal("40.00"), new BigDecimal("60.00"))));
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.credit(id, new BigDecimal("40.00"), null, "key-1"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void replayingAKeyWithDifferentParametersConflicts() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("60.00"), SupportedCurrency.EUR);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.of(
                new AccountOperation("key-1", id, AccountOperationType.DEBIT,
                        new BigDecimal("40.00"), new BigDecimal("60.00"))));

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("99.00"), null, "key-1", OWNER_ID, false))
                .isInstanceOf(AccountOperationConflictException.class);
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void debitInTheAccountsCurrencyApplies() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("100.00"), SupportedCurrency.PLN);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.empty());
        when(accountRepository.save(account)).thenReturn(account);

        accountService.debit(id, new BigDecimal("40.00"), "PLN", "key-1", OWNER_ID, false);

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitInAnotherCurrencyIsRejectedWithoutApplyingAnything() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("100.00"), SupportedCurrency.PLN);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("40.00"), "EUR", "key-1", OWNER_ID, false))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository, never()).save(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void creditInAnotherCurrencyIsRejectedWithoutApplyingAnything() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("100.00"), SupportedCurrency.PLN);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.credit(id, new BigDecimal("40.00"), "EUR", "key-1"))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    // Review Focus #1. The ledger row is proof the operation already happened. Rejecting its
    // replay over a currency label would make CompensationScheduler's reconcileDebit record FAILED
    // for money that did move.
    @Test
    void aReplayOfAnAlreadyAppliedOperationIsNotRecheckedForCurrency() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("125.00"), SupportedCurrency.PLN);
        AccountOperation applied = new AccountOperation("key-1", id, AccountOperationType.CREDIT,
                new BigDecimal("25.00"), new BigDecimal("125.00"));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.of(applied));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        Account result = accountService.credit(id, new BigDecimal("25.00"), "EUR", "key-1");

        assertThat(result).isSameAs(account);
        verify(accountRepository, never()).save(any());
    }
}
