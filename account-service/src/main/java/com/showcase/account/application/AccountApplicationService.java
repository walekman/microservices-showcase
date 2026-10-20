package com.showcase.account.application;

import com.showcase.account.application.port.in.AccountOperationUseCase;
import com.showcase.account.application.port.in.AccountQueries;
import com.showcase.account.application.port.in.CreateAccountUseCase;
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
import com.showcase.account.domain.SupportedCurrency;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The account use cases. Plain Java on purpose: it reaches storage and transactions only
 * through the out-ports, and config.AccountApplicationConfig wires it as a bean.
 */
public class AccountApplicationService implements CreateAccountUseCase, AccountOperationUseCase, AccountQueries {

    private final AccountStore accountStore;
    private final AccountOperationLedger ledger;
    private final TransactionRunner transactions;

    public AccountApplicationService(AccountStore accountStore, AccountOperationLedger ledger,
                                     TransactionRunner transactions) {
        this.accountStore = accountStore;
        this.ledger = ledger;
        this.transactions = transactions;
    }

    @Override
    public Account createAccount(UUID ownerId, String ownerName, BigDecimal initialBalance, SupportedCurrency currency) {
        return transactions.inTransaction(() -> {
            if (accountStore.existsByOwnerId(ownerId)) {
                throw new AccountAlreadyExistsException(ownerId);
            }
            // A genuinely concurrent duplicate gets past the check above; AccountStore.insert
            // rejects it with the same exception.
            return accountStore.insert(new Account(ownerId, ownerName, initialBalance, currency));
        });
    }

    @Override
    public Account getAccount(UUID id, UUID callerId) {
        return transactions.readOnly(() -> {
            Account account = accountStore.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
            if (!Objects.equals(account.getOwnerId(), callerId)) {
                throw new AccountNotFoundException(id);
            }
            return account;
        });
    }

    @Override
    public SupportedCurrency getCurrency(UUID id) {
        return transactions.readOnly(() -> accountStore.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id))
                .getCurrency());
    }

    @Override
    public List<Account> getAllAccounts() {
        return transactions.readOnly(accountStore::findAll);
    }

    @Override
    public List<Account> getMyAccounts(UUID ownerId) {
        return transactions.readOnly(() -> accountStore.findAllByOwnerId(ownerId));
    }

    @Override
    public Account getAccountSummary(UUID id) {
        return transactions.readOnly(() -> accountStore.findById(id).orElseThrow(() -> new AccountNotFoundException(id)));
    }

    @Override
    public Account debit(UUID id, BigDecimal amount, String currency, String idempotencyKey,
                         UUID callerId, boolean serviceCaller) {
        return transactions.inTransaction(() -> {
            requireOwnership(id, callerId, serviceCaller);
            return apply(id, amount, currency, idempotencyKey, AccountOperationType.DEBIT);
        });
    }

    @Override
    public Account credit(UUID id, BigDecimal amount, String currency, String idempotencyKey) {
        return transactions.inTransaction(() -> apply(id, amount, currency, idempotencyKey, AccountOperationType.CREDIT));
    }

    // null is a caller from before Phase 12 (a replay of an older transfer's leg): nothing to check.
    private static void requireCurrency(Account account, String currency) {
        if (currency != null && !account.getCurrency().name().equals(currency)) {
            throw new CurrencyMismatchException(account.getId(), account.getCurrency(), currency);
        }
    }

    // Objects.equals, not account.getOwnerId().equals(callerId): ddl-auto: update cannot add a
    // NOT NULL column over a table with existing rows, so a pre-Phase-7b row can have a null
    // ownerId. A raw .equals() call on that null would NPE into a 500; Objects.equals denies
    // cleanly (404) instead, same as any other non-owning caller. Found in code review.
    private void requireOwnership(UUID id, UUID callerId, boolean serviceCaller) {
        if (serviceCaller) {
            return;
        }
        Account account = accountStore.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        if (!Objects.equals(account.getOwnerId(), callerId)) {
            throw new AccountNotFoundException(id);
        }
    }

    private Account apply(UUID id, BigDecimal amount, String currency, String idempotencyKey,
                          AccountOperationType type) {
        Optional<AccountOperation> existing = ledger.find(idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().conflictsWith(id, type, amount)) {
                throw new AccountOperationConflictException(idempotencyKey);
            }
            // Already applied. The caller (Transfer's AccountClient) discards the response
            // body entirely -- it only cares that the call succeeds -- so the account's
            // current state is returned rather than reconstructing the exact historical
            // balance. AccountOperation.balanceAfter still holds the authoritative figure
            // for whenever an audit/history read is built on top of this table.
            //
            // KNOWN LIMITATION (Phase 3 final-review, see docs/roadmap.md): this still does
            // an independent account lookup, so a replay for an account that no longer
            // exists would 404 here -- turning an already-applied operation into a false
            // rejection. Deliberately left as-is: no delete/archival capability exists
            // anywhere in this app, so the account row present when the operation was first
            // recorded cannot vanish before a replay. Revisit this branch (the response
            // cannot carry a full Account body without one -- ownerName/current balance are
            // not stored on AccountOperation) if a future phase adds one.
            return accountStore.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        }

        Account account = accountStore.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        // Checked only here, on the path that is about to change the balance -- never on the
        // replay branch above. A recorded operation already happened; rejecting its replay over
        // a currency label would let CompensationScheduler read "rejected" for money that moved.
        requireCurrency(account, currency);
        if (type == AccountOperationType.DEBIT) {
            account.debit(amount);
        } else {
            account.credit(amount);
        }
        // Ledger first, then the balance -- the order is load-bearing. Both write immediately,
        // so a request racing on the SAME idempotency key (both passed the find above) blocks on
        // the winner's uncommitted ledger row and then fails with ConcurrentOperationException:
        // a generic 500, which Transfer treats as an unknown outcome and reconciles by replaying
        // the key. Written the other way round, the loser would block on the account row instead
        // and fail with AccountConcurrentlyModifiedException: a 409, which Transfer reads as a
        // definite rejection -- for a debit that the winner did apply. The transaction rolls the
        // loser back either way, so its balance change never persists. AccountControllerIT's
        // same-key race test pins this.
        //
        // That race is rare enough under the pending-stale-after margin (see the design doc) that
        // it is deliberately left to the generic 500 handler rather than special-cased:
        // AccountClient already treats any non-2xx/non-4xx response as retryable, so the loser's
        // caller simply retries and finds the winner's row already there.
        ledger.record(new AccountOperation(idempotencyKey, id, type, amount, account.getBalance()));
        return accountStore.save(account);
    }
}
