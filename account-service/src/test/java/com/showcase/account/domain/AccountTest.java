package com.showcase.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void debitReducesBalanceWhenFundsAreSufficient() {
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));

        account.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsWhenFundsAreInsufficient() {
        Account account = new Account("Ada Lovelace", new BigDecimal("30.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("40.00")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));

        account.credit(new BigDecimal("25.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("125.00");
    }
}
