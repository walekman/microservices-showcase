package com.showcase.account.adapter.out.persistence;

import com.showcase.account.application.port.out.AccountOperationLedger;
import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.ConcurrentOperationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class AccountOperationLedgerAdapter implements AccountOperationLedger {

    private final AccountOperationJpaRepository repository;

    AccountOperationLedgerAdapter(AccountOperationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AccountOperation> find(String idempotencyKey) {
        return repository.findById(idempotencyKey).map(AccountOperationJpaEntity::toDomain);
    }

    @Override
    public void record(AccountOperation operation) {
        try {
            // The primary key on idempotency_key makes a duplicate fail here, flushed now rather
            // than at commit -- see AccountApplicationService.apply() for why the timing matters.
            repository.saveAndFlush(AccountOperationJpaEntity.from(operation));
        } catch (DataIntegrityViolationException ex) {
            throw new ConcurrentOperationException(operation.getIdempotencyKey(), ex);
        }
    }
}
