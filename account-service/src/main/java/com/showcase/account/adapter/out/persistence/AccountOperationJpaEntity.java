package com.showcase.account.adapter.out.persistence;

import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.AccountOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Stored form of {@link AccountOperation}.
 *
 * <p>Implements {@link Persistable} and hardcodes {@link #isNew()} to {@code true}: the
 * {@code @Id} here is a caller-assigned String, never {@code @GeneratedValue}, so Spring
 * Data JPA's default new-vs-existing detection (a null id means new) always sees a non-null
 * id and would route every {@code save()} through {@code EntityManager.merge()} -- a silent
 * upsert -- instead of {@code persist()}. That would make a duplicate idempotency key update
 * the existing row instead of hitting the unique-constraint violation the dedup logic
 * depends on. Since every save here is genuinely a new row, isNew() always returning true
 * forces persist() every time.
 */
@Entity
@Table(name = "account_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AccountOperationJpaEntity implements Persistable<String> {

    @Id
    @Column(length = 255)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private AccountOperationType operation;

    // scale 2, matching AccountJpaEntity.balance -- see the comment there.
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    static AccountOperationJpaEntity from(AccountOperation operation) {
        AccountOperationJpaEntity entity = new AccountOperationJpaEntity();
        entity.idempotencyKey = operation.getIdempotencyKey();
        entity.accountId = operation.getAccountId();
        entity.operation = operation.getOperation();
        entity.amount = operation.getAmount();
        entity.balanceAfter = operation.getBalanceAfter();
        entity.createdAt = operation.getCreatedAt();
        return entity;
    }

    AccountOperation toDomain() {
        return AccountOperation.restore(idempotencyKey, accountId, operation, amount, balanceAfter, createdAt);
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
