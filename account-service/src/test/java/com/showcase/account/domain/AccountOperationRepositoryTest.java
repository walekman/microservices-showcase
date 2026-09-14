package com.showcase.account.domain;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
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
class AccountOperationRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AccountOperationRepository accountOperationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReloadsAnOperationByItsIdempotencyKey() {
        UUID accountId = UUID.randomUUID();
        accountOperationRepository.save(new AccountOperation(
                "key-1", accountId, AccountOperationType.DEBIT, new BigDecimal("40.00"), new BigDecimal("60.00")));

        Optional<AccountOperation> found = accountOperationRepository.findById("key-1");

        assertThat(found).isPresent();
        assertThat(found.get().getAccountId()).isEqualTo(accountId);
        assertThat(found.get().getOperation()).isEqualTo(AccountOperationType.DEBIT);
        assertThat(found.get().getBalanceAfter()).isEqualByComparingTo("60.00");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsADuplicateIdempotencyKeyAtTheDatabaseLevel() {
        UUID accountId = UUID.randomUUID();
        accountOperationRepository.saveAndFlush(new AccountOperation(
                "key-2", accountId, AccountOperationType.CREDIT, new BigDecimal("10.00"), new BigDecimal("10.00")));
        entityManager.clear();

        assertThatThrownBy(() -> accountOperationRepository.saveAndFlush(new AccountOperation(
                "key-2", accountId, AccountOperationType.CREDIT, new BigDecimal("10.00"), new BigDecimal("20.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
