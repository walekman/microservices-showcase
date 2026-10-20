package com.showcase.account.adapter.out.persistence;

import com.showcase.account.application.port.out.AccountStore;
import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountAlreadyExistsException;
import com.showcase.account.domain.AccountConcurrentlyModifiedException;
import com.showcase.account.domain.AccountNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class AccountPersistenceAdapter implements AccountStore {

    private final AccountJpaRepository repository;

    AccountPersistenceAdapter(AccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return repository.findById(id).map(AccountJpaEntity::toDomain);
    }

    @Override
    public List<Account> findAll() {
        return repository.findAll().stream().map(AccountJpaEntity::toDomain).toList();
    }

    @Override
    public List<Account> findAllByOwnerId(UUID ownerId) {
        return repository.findAllByOwnerId(ownerId).stream().map(AccountJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsByOwnerId(UUID ownerId) {
        return repository.existsByOwnerId(ownerId);
    }

    @Override
    public Account insert(Account account) {
        try {
            // saveAndFlush, not save: a plain save() only queues the INSERT for the transaction's
            // commit-time flush, which happens AFTER this method returns -- too late to catch here.
            // Flushing now surfaces a genuinely concurrent duplicate (the unique constraint on
            // ownerId) as a DataIntegrityViolationException inside this method.
            return repository.saveAndFlush(AccountJpaEntity.newRow(account)).toDomain();
        } catch (DataIntegrityViolationException ex) {
            throw new AccountAlreadyExistsException(account.getOwnerId());
        }
    }

    @Override
    public Account save(Account account) {
        // Within the caller's transaction this is the instance its findById already loaded, so
        // the UPDATE below carries the version that read saw.
        AccountJpaEntity entity = repository.findById(account.getId())
                .orElseThrow(() -> new AccountNotFoundException(account.getId()));
        if (entity.getVersion() != account.getVersion()) {
            // A copy read in an earlier transaction, older than the stored row.
            throw new AccountConcurrentlyModifiedException(account.getId());
        }
        entity.updateBalance(account.getBalance());
        try {
            // Flushed now, for the same reason as insert(): at commit, the optimistic-lock failure
            // (another transaction committed first, so `WHERE version = ?` updates nothing) would
            // surface outside this adapter, untranslated.
            return repository.saveAndFlush(entity).toDomain();
        } catch (OptimisticLockingFailureException ex) {
            throw new AccountConcurrentlyModifiedException(account.getId());
        }
    }
}
