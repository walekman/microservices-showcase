package com.showcase.account.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountOperationTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Test
    void recordsTheOperationAndTheResultingBalance() {
        AccountOperation operation = new AccountOperation(
                "key-1", ACCOUNT_ID, AccountOperationType.DEBIT, new BigDecimal("40.00"), new BigDecimal("60.00"));

        assertThat(operation.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(operation.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(operation.getOperation()).isEqualTo(AccountOperationType.DEBIT);
        assertThat(operation.getAmount()).isEqualByComparingTo("40.00");
        assertThat(operation.getBalanceAfter()).isEqualByComparingTo("60.00");
        assertThat(operation.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsABlankIdempotencyKey() {
        assertThatThrownBy(() -> new AccountOperation(
                "", ACCOUNT_ID, AccountOperationType.DEBIT, new BigDecimal("40.00"), new BigDecimal("60.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountOperation(
                null, ACCOUNT_ID, AccountOperationType.DEBIT, new BigDecimal("40.00"), new BigDecimal("60.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullAccountId() {
        assertThatThrownBy(() -> new AccountOperation(
                "key-1", null, AccountOperationType.DEBIT, new BigDecimal("40.00"), new BigDecimal("60.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conflictsWithADifferentAccountOperationOrAmount() {
        AccountOperation operation = new AccountOperation(
                "key-1", ACCOUNT_ID, AccountOperationType.DEBIT, new BigDecimal("40.00"), new BigDecimal("60.00"));

        assertThat(operation.conflictsWith(ACCOUNT_ID, AccountOperationType.DEBIT, new BigDecimal("40.00"))).isFalse();
        assertThat(operation.conflictsWith(UUID.randomUUID(), AccountOperationType.DEBIT, new BigDecimal("40.00"))).isTrue();
        assertThat(operation.conflictsWith(ACCOUNT_ID, AccountOperationType.CREDIT, new BigDecimal("40.00"))).isTrue();
        assertThat(operation.conflictsWith(ACCOUNT_ID, AccountOperationType.DEBIT, new BigDecimal("41.00"))).isTrue();
    }
}
