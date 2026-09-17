package com.showcase.account.service;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.AccountOperationConflictException;
import com.showcase.account.domain.AccountOperationRepository;
import com.showcase.account.domain.AccountOperationType;
import com.showcase.account.domain.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountOperationRepository accountOperationRepository;

    public AccountService(AccountRepository accountRepository, AccountOperationRepository accountOperationRepository) {
        this.accountRepository = accountRepository;
        this.accountOperationRepository = accountOperationRepository;
    }

    @Transactional
    public Account createAccount(UUID ownerId, String ownerName, BigDecimal initialBalance) {
        return accountRepository.save(new Account(ownerId, ownerName, initialBalance));
    }

    // Owner-gated: a non-owning caller gets the same AccountNotFoundException a genuinely
    // missing account would -- see docs/phase-7b-account-ownership-authorization.md's Design
    // Decisions for why this reuses 404/ACCOUNT_NOT_FOUND rather than a new 403 code.
    @Transactional(readOnly = true)
    public Account getAccount(UUID id, UUID callerId) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        if (!account.getOwnerId().equals(callerId)) {
            throw new AccountNotFoundException(id);
        }
        return account;
    }

    // Existence only, no ownership -- backs GET /accounts/exists/{id}, which Transfer's
    // pre-validate step calls for both legs of a transfer (including the destination account,
    // which the initiating caller never owns). See docs/phase-7b-account-ownership-authorization.md.
    @Transactional(readOnly = true)
    public void requireAccountExists(UUID id) {
        if (!accountRepository.existsById(id)) {
            throw new AccountNotFoundException(id);
        }
    }

    // Admin-only at the controller/SecurityConfig layer (account-admin) -- deliberately
    // unfiltered here, same as before Phase 7b.
    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    // Owner-gated, with an exemption for Transfer Service's own machine identity
    // (serviceCaller=true), needed so CompensationScheduler's stale-PENDING reconciliation can
    // replay a debit it doesn't hold the original customer's token for. See
    // docs/phase-7b-account-ownership-authorization.md's Design Decisions.
    @Transactional
    public Account debit(UUID id, BigDecimal amount, String idempotencyKey, UUID callerId, boolean serviceCaller) {
        requireOwnership(id, callerId, serviceCaller);
        return apply(id, amount, idempotencyKey, AccountOperationType.DEBIT);
    }

    // No ownership check -- cannot be enforced here. A real transfer's credit call always
    // carries the SOURCE customer's token, never the destination owner's, so "caller owns this
    // account" would reject every legitimate transfer to someone else. See
    // docs/phase-7b-account-ownership-authorization.md's Design Decisions for the still-open gap
    // this leaves (a customer can mint money via a direct credit call).
    @Transactional
    public Account credit(UUID id, BigDecimal amount, String idempotencyKey) {
        return apply(id, amount, idempotencyKey, AccountOperationType.CREDIT);
    }

    private void requireOwnership(UUID id, UUID callerId, boolean serviceCaller) {
        if (serviceCaller) {
            return;
        }
        Account account = accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        if (!account.getOwnerId().equals(callerId)) {
            throw new AccountNotFoundException(id);
        }
    }

    private Account apply(UUID id, BigDecimal amount, String idempotencyKey, AccountOperationType type) {
        Optional<AccountOperation> existing = accountOperationRepository.findById(idempotencyKey);
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
            return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        }

        Account account = accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        if (type == AccountOperationType.DEBIT) {
            account.debit(amount);
        } else {
            account.credit(amount);
        }
        Account saved = accountRepository.save(account);
        // The primary key on idempotency_key makes a genuinely concurrent duplicate request
        // (same key, racing between the lookup above and this insert) fail here with a
        // constraint violation instead of double-applying -- @Transactional rolls the whole
        // method back, so the loser's balance change never persists. That race is rare
        // enough under this phase's pending-stale-after margin (see the design doc) that it
        // is deliberately left to the existing generic 500 handler rather than special-cased:
        // AccountClient already treats any non-2xx/non-4xx response as retryable, so the
        // loser's caller simply retries and finds the winner's row already there.
        accountOperationRepository.saveAndFlush(
                new AccountOperation(idempotencyKey, id, type, amount, saved.getBalance()));
        return saved;
    }
}
