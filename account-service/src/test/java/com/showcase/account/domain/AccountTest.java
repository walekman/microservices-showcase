package com.showcase.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    // Ownership is irrelevant to debit/credit's own arithmetic -- any fixed id does, the tests
    // below never assert on it.
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Test
    void debitReducesBalanceWhenFundsAreSufficient() {
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"));

        account.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsWhenFundsAreInsufficient() {
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("30.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("40.00")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"));

        account.credit(new BigDecimal("25.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("125.00");
    }

    @Test
    void debitThrowsWhenAmountIsNotPositive() {
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creditThrowsWhenAmountIsNotPositive() {
        Account account = new Account(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.credit(new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.credit(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
