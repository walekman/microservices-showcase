package com.showcase.account.adapter.out.persistence;

import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.AccountOperationType;
import com.showcase.account.domain.ConcurrentOperationException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
@Import(AccountOperationLedgerAdapter.class)
class AccountOperationLedgerAdapterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AccountOperationLedgerAdapter ledger;

    @Autowired
    private EntityManager entityManager;

    @Test
    void recordsAndReloadsAnOperationByItsIdempotencyKey() {
        UUID accountId = UUID.randomUUID();
        ledger.record(new AccountOperation(
                "key-1", accountId, AccountOperationType.DEBIT, new BigDecimal("40.00"), new BigDecimal("60.00")));
        entityManager.clear();

        Optional<AccountOperation> found = ledger.find("key-1");

        assertThat(found).isPresent();
        assertThat(found.get().getAccountId()).isEqualTo(accountId);
        assertThat(found.get().getOperation()).isEqualTo(AccountOperationType.DEBIT);
        assertThat(found.get().getAmount()).isEqualByComparingTo("40.00");
        assertThat(found.get().getBalanceAfter()).isEqualByComparingTo("60.00");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsADuplicateIdempotencyKeyAtTheDatabaseLevel() {
        UUID accountId = UUID.randomUUID();
        ledger.record(new AccountOperation(
                "key-2", accountId, AccountOperationType.CREDIT, new BigDecimal("10.00"), new BigDecimal("10.00")));
        entityManager.clear();

        assertThatThrownBy(() -> ledger.record(new AccountOperation(
                "key-2", accountId, AccountOperationType.CREDIT, new BigDecimal("10.00"), new BigDecimal("20.00"))))
                .isInstanceOf(ConcurrentOperationException.class);
    }
}
