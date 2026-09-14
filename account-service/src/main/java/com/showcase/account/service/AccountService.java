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
    public Account createAccount(String ownerName, BigDecimal initialBalance) {
        return accountRepository.save(new Account(ownerName, initialBalance));
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @Transactional
    public Account debit(UUID id, BigDecimal amount, String idempotencyKey) {
        return apply(id, amount, idempotencyKey, AccountOperationType.DEBIT);
    }

    @Transactional
    public Account credit(UUID id, BigDecimal amount, String idempotencyKey) {
        return apply(id, amount, idempotencyKey, AccountOperationType.CREDIT);
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
