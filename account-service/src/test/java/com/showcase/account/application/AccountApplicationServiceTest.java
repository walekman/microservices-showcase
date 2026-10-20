package com.showcase.account.application;

import com.showcase.account.application.port.out.AccountOperationLedger;
import com.showcase.account.application.port.out.AccountStore;
import com.showcase.account.application.port.out.TransactionRunner;
import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountAlreadyExistsException;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.AccountOperationConflictException;
import com.showcase.account.domain.AccountOperationType;
import com.showcase.account.domain.CurrencyMismatchException;
import com.showcase.account.domain.InsufficientFundsException;
import com.showcase.account.domain.SupportedCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// No Spring and no mocks: the application core runs against in-memory implementations of its
// out-ports, which is the point of keeping it framework-free.
class AccountApplicationServiceTest {

    private static final UUID OWNER_ID = UUID.randomUUID();

    private InMemoryAccountStore accountStore;
    private InMemoryLedger ledger;
    private AccountApplicationService accountService;

    @BeforeEach
    void setUp() {
        accountStore = new InMemoryAccountStore();
        ledger = new InMemoryLedger();
        accountService = new AccountApplicationService(accountStore, ledger, new DirectTransactionRunner());
    }

    @Test
    void createAccountSavesANewAccount() {
        Account result = accountService.createAccount(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(result.getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(result.getBalance()).isEqualByComparingTo("100.00");
        assertThat(result.getCurrency()).isEqualTo(SupportedCurrency.EUR);
        assertThat(accountStore.findById(result.getId())).isPresent();
    }

    @Test
    void createAccountRejectsASecondAccountForTheSameOwner() {
        givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);

        assertThatThrownBy(() -> accountService.createAccount(OWNER_ID, "Ada", new BigDecimal("1.00"), SupportedCurrency.EUR))
                .isInstanceOf(AccountAlreadyExistsException.class);
        assertThat(accountStore.findAll()).hasSize(1);
    }

    @Test
    void getAllAccountsReturnsEveryAccount() {
        Account first = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);
        Account second = givenAccount(UUID.randomUUID(), "50.00", SupportedCurrency.EUR);

        List<Account> result = accountService.getAllAccounts();

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void getMyAccountsReturnsOnlyTheCallersAccounts() {
        Account mine = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);
        givenAccount(UUID.randomUUID(), "50.00", SupportedCurrency.EUR);

        assertThat(accountService.getMyAccounts(OWNER_ID)).containsExactly(mine);
    }

    @Test
    void getAccountThrowsWhenAccountIsMissing() {
        assertThatThrownBy(() -> accountService.getAccount(UUID.randomUUID(), OWNER_ID))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getAccountReturnsItForTheOwningCaller() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);

        Account result = accountService.getAccount(account.getId(), OWNER_ID);

        assertThat(result).isSameAs(account);
    }

    @Test
    void getAccountThrowsForANonOwningCaller() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);

        assertThatThrownBy(() -> accountService.getAccount(account.getId(), UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getCurrencyReturnsTheAccountsCurrencyRegardlessOfOwnership() {
        Account account = givenAccount(UUID.randomUUID(), "5.00", SupportedCurrency.GBP);

        assertThat(accountService.getCurrency(account.getId())).isEqualTo(SupportedCurrency.GBP);
    }

    @Test
    void getCurrencyThrowsForAMissingAccount() {
        assertThatThrownBy(() -> accountService.getCurrency(UUID.randomUUID())).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void debitReducesBalanceAndSaves() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);

        Account result = accountService.debit(account.getId(), new BigDecimal("40.00"), null, "key-1", OWNER_ID, false);

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
        assertThat(accountStore.saves).isEqualTo(1);
        assertThat(ledger.find("key-1")).get()
                .satisfies(operation -> assertThat(operation.getBalanceAfter()).isEqualByComparingTo("60.00"));
    }

    @Test
    void debitThrowsForANonOwningCallerAndDoesNotSave() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);

        assertThatThrownBy(() -> accountService.debit(account.getId(), new BigDecimal("40.00"), null, "key-1", UUID.randomUUID(), false))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(accountStore.saves).isZero();
        assertThat(ledger.recorded).isEmpty();
    }

    @Test
    void debitAllowsTheServiceCallerRegardlessOfOwnership() {
        // Protects CompensationScheduler's reconciliation replay, which runs on transfer-service's
        // own machine identity, never the original customer's token -- see
        // docs/phase-7b-account-ownership-authorization.md's Design Decisions.
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);

        Account result = accountService.debit(account.getId(), new BigDecimal("40.00"), null, "key-1", UUID.randomUUID(), true);

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsWhenFundsAreInsufficientAndDoesNotSave() {
        Account account = givenAccount(OWNER_ID, "10.00", SupportedCurrency.EUR);

        assertThatThrownBy(() -> accountService.debit(account.getId(), new BigDecimal("40.00"), null, "key-1", OWNER_ID, false))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(accountStore.saves).isZero();
        assertThat(ledger.recorded).isEmpty();
    }

    @Test
    void creditIncreasesBalanceAndSaves() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);

        Account result = accountService.credit(account.getId(), new BigDecimal("25.00"), null, "key-1");

        assertThat(result.getBalance()).isEqualByComparingTo("125.00");
        assertThat(ledger.find("key-1")).isPresent();
    }

    @Test
    void recordsTheLedgerEntryBeforeSavingTheBalance() {
        // Load-bearing order -- see AccountApplicationService.apply(): a same-key race must lose
        // on the ledger (500, unknown outcome), never on the account row (409, a rejection).
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.EUR);
        List<String> writes = new ArrayList<>();
        ledger.onRecord = () -> writes.add("ledger");
        accountStore.onSave = () -> writes.add("account");

        accountService.debit(account.getId(), new BigDecimal("40.00"), null, "key-1", OWNER_ID, false);

        assertThat(writes).containsExactly("ledger", "account");
    }

    @Test
    void replayingAKnownIdempotencyKeyDoesNotReapplyTheDebit() {
        // Balance already reflects the FIRST application -- if this replay reapplied the
        // debit, it would go to 20.00 instead of staying 60.00.
        Account account = givenAccount(OWNER_ID, "60.00", SupportedCurrency.EUR);
        ledger.givenRecorded(new AccountOperation("key-1", account.getId(), AccountOperationType.DEBIT,
                new BigDecimal("40.00"), new BigDecimal("60.00")));

        Account result = accountService.debit(account.getId(), new BigDecimal("40.00"), null, "key-1", OWNER_ID, false);

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
        assertThat(accountStore.saves).isZero();
        assertThat(ledger.recorded).isEmpty();
    }

    @Test
    void replayingAKnownKeyWhenTheAccountIsGoneThrowsNotFound() {
        // Pins a documented, currently-unreachable limitation (Phase 3 final-review, see
        // docs/roadmap.md): the replay branch still does an independent account lookup, so
        // an account gone by replay time turns an already-applied operation into a false
        // rejection. Not fixed: no delete/archival capability exists anywhere in this app,
        // so this cannot happen today. If a future phase adds one, this test is the
        // tripwire to revisit AccountApplicationService.apply()'s replay branch.
        //
        // Exercised via credit, not debit: debit's ownership pre-check (Phase 7b) does its own
        // account lookup before apply() ever runs, so it would throw AccountNotFoundException
        // first and never actually reach the replay branch this test targets. credit has no
        // ownership check, so it still reaches apply() unmodified.
        UUID id = UUID.randomUUID();
        ledger.givenRecorded(new AccountOperation("key-1", id, AccountOperationType.CREDIT,
                new BigDecimal("40.00"), new BigDecimal("60.00")));

        assertThatThrownBy(() -> accountService.credit(id, new BigDecimal("40.00"), null, "key-1"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void replayingAKeyWithDifferentParametersConflicts() {
        Account account = givenAccount(OWNER_ID, "60.00", SupportedCurrency.EUR);
        ledger.givenRecorded(new AccountOperation("key-1", account.getId(), AccountOperationType.DEBIT,
                new BigDecimal("40.00"), new BigDecimal("60.00")));

        assertThatThrownBy(() -> accountService.debit(account.getId(), new BigDecimal("99.00"), null, "key-1", OWNER_ID, false))
                .isInstanceOf(AccountOperationConflictException.class);
        assertThat(ledger.recorded).isEmpty();
    }

    @Test
    void debitInTheAccountsCurrencyApplies() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.PLN);

        accountService.debit(account.getId(), new BigDecimal("40.00"), "PLN", "key-1", OWNER_ID, false);

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitInAnotherCurrencyIsRejectedWithoutApplyingAnything() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.PLN);

        assertThatThrownBy(() -> accountService.debit(account.getId(), new BigDecimal("40.00"), "EUR", "key-1", OWNER_ID, false))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        assertThat(accountStore.saves).isZero();
        assertThat(ledger.recorded).isEmpty();
    }

    @Test
    void creditInAnotherCurrencyIsRejectedWithoutApplyingAnything() {
        Account account = givenAccount(OWNER_ID, "100.00", SupportedCurrency.PLN);

        assertThatThrownBy(() -> accountService.credit(account.getId(), new BigDecimal("40.00"), "EUR", "key-1"))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        assertThat(ledger.recorded).isEmpty();
    }

    // Review Focus #1. The ledger row is proof the operation already happened. Rejecting its
    // replay over a currency label would make CompensationScheduler's reconcileDebit record FAILED
    // for money that did move.
    @Test
    void aReplayOfAnAlreadyAppliedOperationIsNotRecheckedForCurrency() {
        Account account = givenAccount(OWNER_ID, "125.00", SupportedCurrency.PLN);
        ledger.givenRecorded(new AccountOperation("key-1", account.getId(), AccountOperationType.CREDIT,
                new BigDecimal("25.00"), new BigDecimal("125.00")));

        Account result = accountService.credit(account.getId(), new BigDecimal("25.00"), "EUR", "key-1");

        assertThat(result).isSameAs(account);
        assertThat(accountStore.saves).isZero();
    }

    private Account givenAccount(UUID ownerId, String balance, SupportedCurrency currency) {
        Account account = Account.restore(UUID.randomUUID(), ownerId, "Ada Lovelace", new BigDecimal(balance),
                currency, 0, Instant.now());
        accountStore.accounts.put(account.getId(), account);
        return account;
    }

    private static final class InMemoryAccountStore implements AccountStore {

        private final Map<UUID, Account> accounts = new LinkedHashMap<>();
        private int saves;
        private Runnable onSave = () -> { };

        @Override
        public Optional<Account> findById(UUID id) {
            return Optional.ofNullable(accounts.get(id));
        }

        @Override
        public List<Account> findAll() {
            return List.copyOf(accounts.values());
        }

        @Override
        public List<Account> findAllByOwnerId(UUID ownerId) {
            return accounts.values().stream().filter(account -> account.getOwnerId().equals(ownerId)).toList();
        }

        @Override
        public boolean existsByOwnerId(UUID ownerId) {
            return !findAllByOwnerId(ownerId).isEmpty();
        }

        @Override
        public Account insert(Account account) {
            Account stored = Account.restore(UUID.randomUUID(), account.getOwnerId(), account.getOwnerName(),
                    account.getBalance(), account.getCurrency(), 0, account.getCreatedAt());
            accounts.put(stored.getId(), stored);
            return stored;
        }

        @Override
        public Account save(Account account) {
            saves++;
            onSave.run();
            accounts.put(account.getId(), account);
            return account;
        }
    }

    private static final class InMemoryLedger implements AccountOperationLedger {

        private final Map<String, AccountOperation> operations = new HashMap<>();
        // Only what the service itself recorded, not what a test seeded.
        private final List<AccountOperation> recorded = new ArrayList<>();
        private Runnable onRecord = () -> { };

        void givenRecorded(AccountOperation operation) {
            operations.put(operation.getIdempotencyKey(), operation);
        }

        @Override
        public Optional<AccountOperation> find(String idempotencyKey) {
            return Optional.ofNullable(operations.get(idempotencyKey));
        }

        @Override
        public void record(AccountOperation operation) {
            onRecord.run();
            recorded.add(operation);
            operations.put(operation.getIdempotencyKey(), operation);
        }
    }

    private static final class DirectTransactionRunner implements TransactionRunner {

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public <T> T readOnly(Supplier<T> work) {
            return work.get();
        }
    }
}
