package com.showcase.account.domain;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
class AccountRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReloadsAnAccount() {
        UUID ownerId = UUID.randomUUID();
        Account saved = accountRepository.save(new Account(ownerId, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR));

        Optional<Account> found = accountRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getOwnerId()).isEqualTo(ownerId);
        assertThat(found.get().getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(found.get().getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void concurrentUpdatesAreRejectedByOptimisticLocking() {
        Account saved = accountRepository.save(new Account(UUID.randomUUID(), "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR));
        accountRepository.flush();
        entityManager.clear();

        Account firstRead = accountRepository.findById(saved.getId()).orElseThrow();
        entityManager.clear();
        Account secondRead = accountRepository.findById(saved.getId()).orElseThrow();
        entityManager.clear();

        firstRead.credit(new BigDecimal("10.00"));
        accountRepository.saveAndFlush(firstRead);

        secondRead.credit(new BigDecimal("5.00"));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(secondRead))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
