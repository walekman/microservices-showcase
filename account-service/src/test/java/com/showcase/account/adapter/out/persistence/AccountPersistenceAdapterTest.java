package com.showcase.account.adapter.out.persistence;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountAlreadyExistsException;
import com.showcase.account.domain.AccountConcurrentlyModifiedException;
import com.showcase.account.domain.SupportedCurrency;
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
@Import(AccountPersistenceAdapter.class)
class AccountPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AccountPersistenceAdapter accountStore;

    @Autowired
    private EntityManager entityManager;

    @Test
    void insertsAndReloadsAnAccount() {
        UUID ownerId = UUID.randomUUID();
        Account saved = accountStore.insert(new Account(ownerId, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR));
        entityManager.clear();

        Optional<Account> found = accountStore.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getOwnerId()).isEqualTo(ownerId);
        assertThat(found.get().getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(found.get().getBalance()).isEqualByComparingTo("100.00");
        assertThat(found.get().getCurrency()).isEqualTo(SupportedCurrency.EUR);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void savesAChangedBalance() {
        Account saved = accountStore.insert(new Account(UUID.randomUUID(), "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR));
        entityManager.clear();

        Account account = accountStore.findById(saved.getId()).orElseThrow();
        account.credit(new BigDecimal("10.00"));
        accountStore.save(account);
        entityManager.clear();

        assertThat(accountStore.findById(saved.getId()).orElseThrow().getBalance()).isEqualByComparingTo("110.00");
    }

    @Test
    void rejectsASecondAccountForTheSameOwner() {
        UUID ownerId = UUID.randomUUID();
        accountStore.insert(new Account(ownerId, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR));
        entityManager.clear();

        assertThatThrownBy(() -> accountStore.insert(new Account(ownerId, "Ada Again", new BigDecimal("1.00"), SupportedCurrency.EUR)))
                .isInstanceOf(AccountAlreadyExistsException.class);
    }

    @Test
    void concurrentUpdatesAreRejectedByOptimisticLocking() {
        Account saved = accountStore.insert(new Account(UUID.randomUUID(), "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR));
        entityManager.clear();

        Account firstRead = accountStore.findById(saved.getId()).orElseThrow();
        Account secondRead = accountStore.findById(saved.getId()).orElseThrow();
        entityManager.clear();

        firstRead.credit(new BigDecimal("10.00"));
        accountStore.save(firstRead);
        entityManager.clear();

        secondRead.credit(new BigDecimal("5.00"));
        assertThatThrownBy(() -> accountStore.save(secondRead))
                .isInstanceOf(AccountConcurrentlyModifiedException.class);
    }

    @Test
    void aRowFromBeforeCurrenciesExistedReadsAsEur() {
        Account saved = accountStore.insert(new Account(UUID.randomUUID(), "Ada", new BigDecimal("10.00"), SupportedCurrency.PLN));
        entityManager.createNativeQuery("UPDATE accounts SET currency = NULL WHERE id = :id")
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(accountStore.findById(saved.getId()).orElseThrow().getCurrency()).isEqualTo(SupportedCurrency.EUR);
    }
}
