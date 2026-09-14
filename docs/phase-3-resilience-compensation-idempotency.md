# Resilience4j + Compensation + Idempotency Implementation Phase

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Transfer Service's calls into Account Service resilient (CircuitBreaker + Retry), make those calls safe to retry (idempotency keys on debit/credit), and add a scheduled compensator that automatically drains the `COMPENSATION_REQUIRED` and stale-`PENDING` states Phase 2 deliberately left unresolved.

**Architecture:** Two coordinated additions, one per service, tied together by one mechanism: idempotent replay.

- **Account Service** gains `AccountOperation`, a permanent, append-only ledger entry written atomically with every applied debit/credit, keyed by a caller-supplied `Idempotency-Key`. A repeat key returns the original result instead of reapplying the operation. This is the ground truth Transfer needs to resolve ambiguity — not a guess, an authoritative answer.
- **Transfer Service** gains: (1) Resilience4j `@CircuitBreaker`/`@Retry` wrapping every `AccountClient` call, using the deterministic key `"{transferId}:{leg}"` so a retry is automatically safe; (2) `CompensationScheduler`, an in-process `@Scheduled` job that periodically resolves every `COMPENSATION_REQUIRED` and stale-`PENDING` row by *replaying* the ambiguous call — Account's dedup either confirms it landed or applies it now — rather than guessing from Transfer's own possibly-stale record.

No new infrastructure (no Kafka, no outbox, no second service) — this stays in-process on both existing services, consistent with the single-instance Docker Compose deployment (no leader election needed for the scheduler).

**Tech Stack (additions):** `resilience4j-spring-boot3` on `transfer-service` — pin to the version compatible with Spring Boot 3.3.4 and verify it actually starts against that Boot line before committing to it, the same discipline already applied to springdoc-openapi's pin (see `docs/roadmap.md`).

**Spec:** [docs/microservices-showcase-design.md](microservices-showcase-design.md) §3 (Resilience), §4 (Compensation)

## Global Constraints

- Java 21 floor; Spring Boot 3.3.4. Use `C:\dev\openjdk-21.0.2` / `JAVA_HOME`. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads via `spring.threads.virtual.enabled=true`. `AccountClient` stays a blocking `RestClient` — see the TimeLimiter decision below for why no async/bulkhead machinery is introduced. (spec §3)
- One logical database per stateful service — `AccountOperation` lives in Account Service's own database; Transfer never queries it directly, only through `AccountClient`. (spec §2, §3)
- Integration-level tests use Testcontainers against real Postgres — never H2. (spec §6)
- Lombok for entity boilerplate: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic. (CLAUDE.md)
- `ddl-auto: update` stays; Flyway is still deferred to its own phase (unchanged from Phase 2).
- Never commit directly to `master`; work happens on a `feature/phase-3-*` branch. Subagent review is manual, on request — not automatic. (CLAUDE.md)
- springdoc-openapi stays pinned to `2.6.0`. (docs/roadmap.md)

## Scope Boundary

**In scope:**
- `AccountOperation` entity + idempotency dedup on Account's `debit`/`credit` (required `Idempotency-Key` header, 409 on a key reused with mismatched parameters).
- Resilience4j CircuitBreaker + Retry on all three `AccountClient` calls (`getAccount` × 2, `debit`, `credit`).
- `CompensationScheduler` with the two sweeps (`drainCompensationRequired`, `sweepStalePending`), and the new `COMPENSATED`/`COMPENSATION_FAILED` terminal states.

**Explicitly out of scope** — each is a named follow-up, not an oversight:

| Deferred | Why not now | Lands in |
|---|---|---|
| Resilience4j TimeLimiter | Would require wrapping blocking `RestClient` calls in `CompletableFuture` on a dedicated bulkhead executor purely to let Resilience4j enforce a second, redundant deadline — `AccountClientProperties`' existing connect/read timeouts already bound how long a call can hang. Revisit only if the existing timeouts prove insufficient in practice. | not planned |
| Idempotency key on `POST /transfers` (caller → Transfer) | Protects against a *caller* retrying transfer creation, not Transfer retrying into Account — a different gap. No automated caller exists yet to retry it (no Gateway, no Fraud calling Transfer). | Phase 5 or 6, once one exists |
| Transactional outbox + Kafka publisher | Distinct subsystem, its own container and test infrastructure | Phase 4 |
| Fraud Service (the saga's third call) | The saga is built to accept another step; adding one is additive | Phase 5 |
| Flyway migrations | Would require baselining two existing schemas — real work outside this slice | its own phase |
| Auth / JWT | No security on any service yet | Phase 6 |

## Design Decisions Worth Knowing Before You Start

**Reconciliation is replay, not a query API.** The Phase 2 deferral table's blocking precondition — "reconcile against Account before crediting anything back" — could have meant building a new read endpoint on Account to answer "did operation X happen?" Instead, the compensator resolves ambiguity by **replaying the original call with its original idempotency key**. Account's dedup then gives a definitive answer: applied now (first time), or already-applied (confirms it landed). This reuses the exact mechanism that makes retries safe in the first place, instead of building a second one.

**`COMPENSATION_FAILED` is reached only by a definitive rejection, never by exhausted unavailability.** If Account is merely unreachable when the compensator tries the source credit-back, that recreates the same ambiguity the compensator exists to resolve — so it just retries next sweep, logged at ERROR like `COMPENSATION_REQUIRED` already is. Only an explicit `AccountRejectedException` (e.g. the source account no longer exists) escalates to the manual-review terminal state.

**The stale-`PENDING` sweep resolves only the debit leg, then hands off.** It never independently re-checks the credit leg — if the debit is confirmed landed, the row is promoted to `COMPENSATION_REQUIRED` and left for the other sweep to resolve, reusing `markCompensationRequired()` (the same transition the live saga already uses). This keeps each sweep single-purpose. One consequence worth remembering, not a bug: a stale-`PENDING` row where *both* legs actually landed (only the final `save` failed) still passes through `COMPENSATION_REQUIRED` for one extra sweep before self-correcting to `COMPLETED` — the label is momentarily imprecise but never user-facing and never wrong for longer than one `sweep-interval`.

**`CallNotPermittedException` is mapped to `AccountServiceUnavailableException` inside `AccountClient`.** An open circuit breaker throws Resilience4j's own exception type, not either of the two domain exceptions `TransferService` already branches on. Catching and remapping it inside the client means `TransferService.execute()` needs zero changes — an open circuit is, correctly, indistinguishable from Account being unavailable.

**`pending-stale-after` must clear the saga's own worst case, not just look generous.** With Retry's `max-attempts: 3` and `wait-duration: 200ms` wrapping a call whose connect+read timeout can total 7s, one `AccountClient` call can legitimately take up to ~21.4s before giving up (`3×7s + 2×0.2s`). The saga makes up to four such calls in sequence (two pre-validation `getAccount`s, `debit`, `credit`), so a transfer can legitimately still be running for up to ~85s under a cold circuit breaker (before enough failures accumulate to start short-circuiting). `pending-stale-after` defaults to 120s specifically to clear that worst case with margin — a smaller number risks the stale-`PENDING` sweep firing on a transfer that is still genuinely in flight, not stuck.

**`AccountOperation` is a ledger entry, not a transient cache.** Unlike a generic idempotency-key cache (Stripe's model expires keys after 24h), these rows are permanent and never deleted — Account Service needs a per-operation record anyway for a system whose job is moving money, so idempotency dedup rides along on top of it rather than building a second, overlapping mechanism with its own lifecycle.

## Error Code Contract (additions)

| Account code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Already existed (Phase 2) — now also covers a missing `Idempotency-Key` header |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | The key was reused with a different `accountId`/`operation`/`amount` than its stored record |

Transfer needs no new `TransferFailureCode` for `IDEMPOTENCY_KEY_CONFLICT` — `TransferFailureCode.fromAccountCode(String)`'s `default` branch already maps any code it doesn't explicitly recognize to `UNEXPECTED_ERROR`, which is the right read here (a genuine bug condition, not a business rejection). This should never actually surface in normal operation, since a given `(transferId, leg)` always maps to the same account/operation/amount (amount is immutable on `Transfer`).

## File Structure

**Account Service (modified):**

| File | Responsibility |
|---|---|
| `domain/AccountOperation.java` (new) | Entity: `idempotencyKey` (unique), `accountId`, `operation` (`DEBIT`/`CREDIT`), `amount`, `balanceAfter`, `createdAt` |
| `domain/AccountOperationRepository.java` (new) | Spring Data repository, lookup by `idempotencyKey` |
| `service/AccountService.java` (modified) | `debit`/`credit` check for an existing `AccountOperation` first; on the race between two duplicate inserts, catch the constraint violation and re-read rather than fail |
| `api/AccountController.java` (modified) | `debit`/`credit` require `@RequestHeader("Idempotency-Key")` |
| `api/ApiExceptionHandler.java` (modified) | Maps missing header → `VALIDATION_FAILED`; mismatched replay → `IDEMPOTENCY_KEY_CONFLICT` |

**Transfer Service (modified):**

| File | Responsibility |
|---|---|
| `client/AccountClient.java` (modified) | `debit`/`credit` take an `idempotencyKey` parameter, sent as the `Idempotency-Key` header; `@CircuitBreaker`/`@Retry` on all three public methods; catches `CallNotPermittedException` → `AccountServiceUnavailableException` |
| `service/TransferService.java` (modified) | Passes `"{transferId}:debit"` / `"{transferId}:credit"` as the idempotency key on each call — otherwise unchanged |
| `service/CompensationScheduler.java` (new) | `drainCompensationRequired()` and `sweepStalePending()`, each `@Scheduled` |
| `service/CompensationProperties.java` (new) | `sweep-interval` (default 15s), `pending-stale-after` (default 120s — see Design Decisions) |
| `domain/TransferStatus.java` (modified) | Adds `COMPENSATED`, `COMPENSATION_FAILED` |
| `domain/Transfer.java` (modified) | `requirePending()` generalizes to `requireStatus(TransferStatus...)`; `markCompleted()` now also accepts `COMPENSATION_REQUIRED` as a pre-state; new `markCompensated(...)`, `markCompensationFailed(...)`, both requiring `COMPENSATION_REQUIRED` |
| `domain/TransferRepository.java` (modified) | New `findByStatusAndCreatedAtBefore(TransferStatus, Instant)` |

**Infrastructure (modified):** `transfer-service/pom.xml` (resilience4j dependency), both services' `application.yml` (resilience4j config, compensation properties), `docs/roadmap.md`.

---

### Task 1: `AccountOperation` entity and repository

**Files:**
- Create: `account-service/src/main/java/com/showcase/account/domain/AccountOperationType.java`
- Create: `account-service/src/main/java/com/showcase/account/domain/AccountOperation.java`
- Create: `account-service/src/main/java/com/showcase/account/domain/AccountOperationRepository.java`
- Create: `account-service/src/main/java/com/showcase/account/domain/AccountOperationConflictException.java`
- Test: `account-service/src/test/java/com/showcase/account/domain/AccountOperationRepositoryTest.java`
- Test: `account-service/src/test/java/com/showcase/account/domain/AccountOperationTest.java`

**Interfaces:**
- Produces: `AccountOperation` (getters `idempotencyKey`, `accountId`, `operation`, `amount`, `balanceAfter`, `createdAt`; constructor `(String idempotencyKey, UUID accountId, AccountOperationType operation, BigDecimal amount, BigDecimal balanceAfter)`; `boolean conflictsWith(UUID accountId, AccountOperationType operation, BigDecimal amount)`), `AccountOperationRepository extends JpaRepository<AccountOperation, String>`, `AccountOperationConflictException` — all consumed by Task 2.

- [ ] **Step 1: Write the failing entity test**

```java
// account-service/src/test/java/com/showcase/account/domain/AccountOperationTest.java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl account-service test -Dtest=AccountOperationTest`
Expected: FAIL to compile — `AccountOperation` and `AccountOperationType` do not exist yet.

- [ ] **Step 3: Implement `AccountOperationType`, `AccountOperation`, and the conflict exception**

```java
// account-service/src/main/java/com/showcase/account/domain/AccountOperationType.java
package com.showcase.account.domain;

public enum AccountOperationType {
    DEBIT,
    CREDIT
}
```

```java
// account-service/src/main/java/com/showcase/account/domain/AccountOperation.java
package com.showcase.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A permanent, immutable record of one applied debit or credit, keyed by the caller's
 * idempotency key. Doubles as both the dedup mechanism for safe retries and a permanent
 * per-account operation history — see docs/phase-3-resilience-compensation-idempotency.md
 * "Design Decisions" for why these are the same row rather than two separate mechanisms.
 *
 * <p>Never updated after creation. A repeat of the same idempotencyKey returns this row's
 * balanceAfter instead of reapplying the operation.
 */
@Entity
@Table(name = "account_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountOperation {

    @Id
    @Column(length = 255)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private AccountOperationType operation;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public AccountOperation(String idempotencyKey, UUID accountId, AccountOperationType operation,
                             BigDecimal amount, BigDecimal balanceAfter) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.operation = operation;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
    }

    /**
     * True when a replay of this key arrives with different parameters than the ones
     * originally recorded — a bug signal (see {@link AccountOperationConflictException}),
     * never a legitimate replay. A given (transferId, leg) always maps to the same
     * account/operation/amount in normal operation, so this should never actually trip.
     */
    public boolean conflictsWith(UUID accountId, AccountOperationType operation, BigDecimal amount) {
        return !this.accountId.equals(accountId)
                || this.operation != operation
                || this.amount.compareTo(amount) != 0;
    }
}
```

```java
// account-service/src/main/java/com/showcase/account/domain/AccountOperationConflictException.java
package com.showcase.account.domain;

public class AccountOperationConflictException extends RuntimeException {

    public AccountOperationConflictException(String idempotencyKey) {
        super("Idempotency key already used with different parameters: " + idempotencyKey);
    }
}
```

```java
// account-service/src/main/java/com/showcase/account/domain/AccountOperationRepository.java
package com.showcase.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountOperationRepository extends JpaRepository<AccountOperation, String> {
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountOperationTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing repository test**

```java
// account-service/src/test/java/com/showcase/account/domain/AccountOperationRepositoryTest.java
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
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountOperationRepositoryTest`
Expected: PASS, 2 tests. `ddl-auto: update` creates the `account_operations` table with `idempotency_key` as its primary key automatically — no migration file needed (Flyway is still deferred).

- [ ] **Step 7: Commit**

```bash
git add account-service/src/main/java/com/showcase/account/domain account-service/src/test/java/com/showcase/account/domain
git commit -m "feat(account): add AccountOperation as the idempotency ledger"
```

---

### Task 2: Idempotency dedup in `AccountService.debit`/`credit`, and the `Idempotency-Key` header

**Files:**
- Modify: `account-service/src/main/java/com/showcase/account/service/AccountService.java`
- Modify: `account-service/src/main/java/com/showcase/account/api/AccountController.java`
- Modify: `account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java`
- Modify (full rewrite): `account-service/src/test/java/com/showcase/account/service/AccountServiceTest.java`
- Modify (full rewrite): `account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java`

**Interfaces:**
- Consumes: `AccountOperation`, `AccountOperationType`, `AccountOperationRepository`, `AccountOperationConflictException` (Task 1).
- Produces: `AccountService.debit(UUID id, BigDecimal amount, String idempotencyKey)` / `.credit(...)` — the new three-arg signature every caller (Task 3's `AccountClient`, and every existing test) must now use.

- [ ] **Step 1: Update `AccountServiceTest` for the new signature and idempotency behavior**

```java
// account-service/src/test/java/com/showcase/account/service/AccountServiceTest.java
package com.showcase.account.service;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.AccountOperationConflictException;
import com.showcase.account.domain.AccountOperationRepository;
import com.showcase.account.domain.AccountOperationType;
import com.showcase.account.domain.AccountRepository;
import com.showcase.account.domain.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountOperationRepository accountOperationRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, accountOperationRepository);
    }

    @Test
    void createAccountSavesANewAccount() {
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountService.createAccount("Ada Lovelace", new BigDecimal("100.00"));

        assertThat(result.getOwnerName()).isEqualTo("Ada Lovelace");
        assertThat(result.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void getAllAccountsReturnsEveryAccount() {
        Account first = new Account("Ada Lovelace", new BigDecimal("100.00"));
        Account second = new Account("Alan Turing", new BigDecimal("50.00"));
        when(accountRepository.findAll()).thenReturn(List.of(first, second));

        List<Account> result = accountService.getAllAccounts();

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void getAccountThrowsWhenAccountIsMissing() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(id))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void debitReducesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.debit(id, new BigDecimal("40.00"), "key-1");

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
        verify(accountOperationRepository).saveAndFlush(any(AccountOperation.class));
    }

    @Test
    void debitThrowsWhenFundsAreInsufficientAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("10.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("40.00"), "key-1"))
                .isInstanceOf(InsufficientFundsException.class);
        verify(accountRepository, never()).save(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void creditIncreasesBalanceAndSaves() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("100.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        Account result = accountService.credit(id, new BigDecimal("25.00"), "key-1");

        assertThat(result.getBalance()).isEqualByComparingTo("125.00");
        verify(accountOperationRepository).saveAndFlush(any(AccountOperation.class));
    }

    @Test
    void replayingAKnownIdempotencyKeyDoesNotReapplyTheDebit() {
        UUID id = UUID.randomUUID();
        Account account = new Account("Ada Lovelace", new BigDecimal("60.00"));
        // Balance already reflects the FIRST application -- if this replay reapplied the
        // debit, it would go to 20.00 instead of staying 60.00.
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.of(
                new AccountOperation("key-1", id, AccountOperationType.DEBIT,
                        new BigDecimal("40.00"), new BigDecimal("60.00"))));

        Account result = accountService.debit(id, new BigDecimal("40.00"), "key-1");

        assertThat(result.getBalance()).isEqualByComparingTo("60.00");
        verify(accountRepository, never()).save(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void replayingAKeyWithDifferentParametersConflicts() {
        UUID id = UUID.randomUUID();
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.of(
                new AccountOperation("key-1", id, AccountOperationType.DEBIT,
                        new BigDecimal("40.00"), new BigDecimal("60.00"))));

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("99.00"), "key-1"))
                .isInstanceOf(AccountOperationConflictException.class);
        verify(accountRepository, never()).findById(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl account-service test -Dtest=AccountServiceTest`
Expected: FAIL to compile — `AccountService` has no such constructor, and `debit`/`credit` do not take a third argument yet.

- [ ] **Step 3: Update `AccountService`**

```java
// account-service/src/main/java/com/showcase/account/service/AccountService.java
package com.showcase.account.service;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountOperation;
import com.showcase.account.domain.AccountOperationConflictException;
import com.showcase.account.domain.AccountOperationRepository;
import com.showcase.account.domain.AccountOperationType;
import com.showcase.account.domain.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountOperationRepository accountOperationRepository;

    public AccountService(AccountRepository accountRepository, AccountOperationRepository accountOperationRepository) {
        this.accountRepository = accountRepository;
        this.accountOperationRepository = accountOperationRepository;
    }

    @Transactional
    public Account createAccount(String ownerName, BigDecimal initialBalance) {
        return accountRepository.save(new Account(ownerName, initialBalance));
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @Transactional
    public Account debit(UUID id, BigDecimal amount, String idempotencyKey) {
        return apply(id, amount, idempotencyKey, AccountOperationType.DEBIT);
    }

    @Transactional
    public Account credit(UUID id, BigDecimal amount, String idempotencyKey) {
        return apply(id, amount, idempotencyKey, AccountOperationType.CREDIT);
    }

    private Account apply(UUID id, BigDecimal amount, String idempotencyKey, AccountOperationType type) {
        Optional<AccountOperation> existing = accountOperationRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().conflictsWith(id, type, amount)) {
                throw new AccountOperationConflictException(idempotencyKey);
            }
            // Already applied. The caller (Transfer's AccountClient) discards the response
            // body entirely -- it only cares that the call succeeds -- so the account's
            // current state is returned rather than reconstructing the exact historical
            // balance. AccountOperation.balanceAfter still holds the authoritative figure
            // for whenever an audit/history read is built on top of this table.
            return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        }

        Account account = accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        if (type == AccountOperationType.DEBIT) {
            account.debit(amount);
        } else {
            account.credit(amount);
        }
        Account saved = accountRepository.save(account);
        // The primary key on idempotency_key makes a genuinely concurrent duplicate request
        // (same key, racing between the lookup above and this insert) fail here with a
        // constraint violation instead of double-applying -- @Transactional rolls the whole
        // method back, so the loser's balance change never persists. That race is rare
        // enough under this phase's pending-stale-after margin (see the design doc) that it
        // is deliberately left to the existing generic 500 handler rather than special-cased:
        // AccountClient already treats any non-2xx/non-4xx response as retryable, so the
        // loser's caller simply retries and finds the winner's row already there.
        accountOperationRepository.saveAndFlush(
                new AccountOperation(idempotencyKey, id, type, amount, saved.getBalance()));
        return saved;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl account-service test -Dtest=AccountServiceTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Require the `Idempotency-Key` header on `debit`/`credit`**

```java
// account-service/src/main/java/com/showcase/account/api/AccountController.java
package com.showcase.account.api;

import com.showcase.account.domain.Account;
import com.showcase.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request.ownerName(), request.initialBalance());
        return ResponseEntity.created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping
    public List<AccountResponse> getAllAccounts() {
        return accountService.getAllAccounts().stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id));
    }

    @PostMapping("/{id}/debit")
    public AccountResponse debit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return AccountResponse.from(accountService.debit(id, request.amount(), idempotencyKey));
    }

    @PostMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return AccountResponse.from(accountService.credit(id, request.amount(), idempotencyKey));
    }
}
```

- [ ] **Step 6: Map the missing-header and conflict exceptions**

```java
// account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java
package com.showcase.account.api;

import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.AccountOperationConflictException;
import com.showcase.account.domain.InsufficientFundsException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleNotFound(AccountNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds", ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConflict(ObjectOptimisticLockingFailureException ex) {
        return Problems.of(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Concurrent modification",
                "Account was modified concurrently, please retry");
    }

    @ExceptionHandler(AccountOperationConflictException.class)
    public ProblemDetail handleIdempotencyConflict(AccountOperationConflictException ex) {
        return Problems.of(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency key conflict", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", detail));
    }

    @Override
    protected ResponseEntity<Object> handleMissingRequestHeader(
            MissingRequestHeaderException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                        "Missing required header: " + ex.getHeaderName()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Malformed request body"));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Invalid value for parameter: " + ex.getPropertyName()));
    }

    /**
     * Last line of defence for the "every error response carries a {@code code}" contract: the
     * exceptions {@link ResponseEntityExceptionHandler} handles for us (405, 415, 406, unmapped
     * paths, missing path variables, ...) render a {@code ProblemDetail} that carries no custom
     * properties, so stamp the invariant here rather than overriding every hook individually.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", statusCode.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED");
            problem.setProperty("timestamp", Instant.now());
        }
        return response;
    }
}
```

- [ ] **Step 7: Update `AccountControllerIT` for the required header**

Every existing `debit`/`credit` call needs an `Idempotency-Key` header now, and the 10-concurrent-requests test needs a **distinct** key per request — giving them the same key would make 9 of the 10 short-circuit on the dedup path instead of racing on the optimistic lock, defeating the point of that test.

```java
// account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java
package com.showcase.account.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsAndFetchesAnAccount() {
        ResponseEntity<AccountResponse> createResponse = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("100.00")), AccountResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = createResponse.getBody().id();

        ResponseEntity<AccountResponse> getResponse = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void listsAllAccounts() {
        // Test methods in this class share one Testcontainers Postgres instance (no per-test
        // cleanup), so other tests' accounts may already be in the table -- assert this test's
        // own accounts are present in the list rather than asserting an exact total count.
        UUID firstId = createAccount(new BigDecimal("100.00"));
        UUID secondId = createAccount(new BigDecimal("50.00"));

        ResponseEntity<AccountResponse[]> response = restTemplate.getForEntity("/accounts", AccountResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(AccountResponse::id)
                .contains(firstId, secondId);
    }

    @Test
    void returns404ForUnknownAccount() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/accounts/" + UUID.randomUUID(), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getProperties()).containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(response.getBody().getProperties()).containsKey("timestamp");
    }

    @Test
    void debitsAnAccountSuccessfully() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> response = debit(id, new BigDecimal("40.00"), "debit-key-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void rejectsDebitWithInsufficientFunds() {
        UUID id = createAccount(new BigDecimal("10.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST,
                amountRequest(new BigDecimal("40.00"), "debit-key-2"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getProperties()).containsEntry("code", "INSUFFICIENT_FUNDS");
    }

    @Test
    void creditsAnAccountSuccessfully() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> response = credit(id, new BigDecimal("40.00"), "credit-key-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo("140.00");
    }

    @Test
    void rejectsADebitWithNoIdempotencyKeyHeader() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("40.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void replayingTheSameIdempotencyKeyDoesNotDebitTwice() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> first = debit(id, new BigDecimal("40.00"), "replay-key");
        ResponseEntity<AccountResponse> second = debit(id, new BigDecimal("40.00"), "replay-key");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<AccountResponse> current = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
        assertThat(current.getBody().balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void reusingAKeyWithADifferentAmountConflicts() {
        UUID id = createAccount(new BigDecimal("100.00"));
        debit(id, new BigDecimal("40.00"), "conflict-key");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST,
                amountRequest(new BigDecimal("15.00"), "conflict-key"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("code", "IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void returns409ForConcurrentUpdateConflict() throws Exception {
        // A 2-thread version of this test (one barrier-released pair of requests) was flaky
        // in CI: on a slow/constrained runner, both requests can fully serialize (one commits
        // entirely before the other even reads), so no version conflict ever occurs and both
        // return 200 OK instead of one returning 409. Widening to CONCURRENT_REQUESTS
        // genuinely concurrent debits against the same account makes it overwhelmingly likely
        // that at least two of them interleave and collide, without relying on exact
        // microsecond-level thread-scheduling timing. This still exercises the real HTTP debit
        // endpoint end-to-end (not the repository directly) — see AccountRepositoryTest for the
        // deterministic repository-level proof of the same optimistic-locking behavior.
        //
        // Each request uses its OWN idempotency key: giving them all the same key would make
        // the dedup path short-circuit 9 of the 10 requests instead of letting them race.
        int concurrentRequests = 10;
        UUID id = createAccount(new BigDecimal("1000.00"));

        CyclicBarrier barrier = new CyclicBarrier(concurrentRequests);
        List<Callable<ResponseEntity<String>>> debitCalls = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            String key = "concurrent-key-" + i;
            debitCalls.add(() -> {
                barrier.await();
                return restTemplate.exchange(
                        "/accounts/" + id + "/debit", HttpMethod.POST,
                        amountRequest(new BigDecimal("1.00"), key), String.class);
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> call : debitCalls) {
                futures.add(executor.submit(call));
            }

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }

            List<HttpStatus> statuses = new ArrayList<>();
            for (ResponseEntity<String> response : responses) {
                statuses.add((HttpStatus) response.getStatusCode());
            }

            assertThat(statuses).hasSize(concurrentRequests);
            assertThat(statuses).allMatch(status -> status == HttpStatus.OK || status == HttpStatus.CONFLICT);
            assertThat(statuses).as("at least one of %s concurrent debits should lose the optimistic-lock race", concurrentRequests)
                    .contains(HttpStatus.CONFLICT);
            // Transfer Service records CONCURRENT_MODIFICATION and aborts (does not retry);
            // retry policy is a later plan's concern. This is the only error code derived
            // from a framework exception type rather than an app-owned one, so it is the most
            // likely to drift silently under a Spring/Hibernate upgrade. The assertion above
            // guarantees at least one CONFLICT, so this filtered check is never vacuous.
            assertThat(responses)
                    .filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                    .as("every 409 body must carry the CONCURRENT_MODIFICATION code")
                    .allSatisfy(response -> assertThat(response.getBody()).contains("CONCURRENT_MODIFICATION"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsNegativeInitialBalance() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("-5.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void returns405WithCodeForUnsupportedMethod() {
        // Covers the exceptions ResponseEntityExceptionHandler handles for us: they render through
        // handleExceptionInternal, which must stamp the code/timestamp invariant on every problem body.
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id, HttpMethod.DELETE, null, ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getProperties()).containsEntry("code", "REQUEST_REJECTED");
        assertThat(response.getBody().getProperties()).containsKey("timestamp");
    }

    @Test
    void returns400ForMalformedAccountId() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/accounts/not-a-uuid", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "MALFORMED_REQUEST");
    }

    private UUID createAccount(BigDecimal initialBalance) {
        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", initialBalance), AccountResponse.class);
        return response.getBody().id();
    }

    private HttpEntity<AmountRequest> amountRequest(BigDecimal amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(new AmountRequest(amount), headers);
    }

    private ResponseEntity<AccountResponse> debit(UUID id, BigDecimal amount, String idempotencyKey) {
        return restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST, amountRequest(amount, idempotencyKey), AccountResponse.class);
    }

    private ResponseEntity<AccountResponse> credit(UUID id, BigDecimal amount, String idempotencyKey) {
        return restTemplate.exchange(
                "/accounts/" + id + "/credit", HttpMethod.POST, amountRequest(amount, idempotencyKey), AccountResponse.class);
    }
}
```

- [ ] **Step 8: Run the whole Account test suite**

Run: `./mvnw -pl account-service test`
Expected: PASS, all tests.

- [ ] **Step 9: Commit**

```bash
git add account-service/src/main/java/com/showcase/account account-service/src/test/java/com/showcase/account
git commit -m "feat(account): require Idempotency-Key on debit/credit, dedup via AccountOperation"
```

---

### Task 3: `AccountClient` idempotency keys + Resilience4j, wired through `TransferService`

**Files:**
- Modify: `pom.xml` (root)
- Modify: `transfer-service/pom.xml`
- Modify: `transfer-service/src/main/resources/application.yml`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/client/AccountClient.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java`
- Modify (full rewrite): `transfer-service/src/test/java/com/showcase/transfer/client/AccountClientTest.java`
- Create: `transfer-service/src/test/java/com/showcase/transfer/client/AccountClientResilienceTest.java`
- Modify (full rewrite): `transfer-service/src/test/java/com/showcase/transfer/service/TransferServiceTest.java`

**Interfaces:**
- Consumes: Account Service's `Idempotency-Key` header requirement (Task 2).
- Produces: `AccountClient.debit(UUID, BigDecimal, String)` / `.credit(UUID, BigDecimal, String)` — the new three-arg signature Task 4/5's `CompensationScheduler` will also call.

- [ ] **Step 1: Add the Resilience4j dependency**

```xml
<!-- pom.xml (root) -- add inside <properties> -->
<resilience4j.version>2.4.0</resilience4j.version>
```

```xml
<!-- transfer-service/pom.xml -- add inside <dependencies> -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
  <version>${resilience4j.version}</version>
</dependency>
```

`spring-boot-starter-aop` is required — `@CircuitBreaker`/`@Retry` are applied via a Spring AOP proxy, and nothing wraps `AccountClient`'s methods without it.

- [ ] **Step 2: Configure the `accountService` Retry/CircuitBreaker instance**

```yaml
# transfer-service/src/main/resources/application.yml -- append at the end
resilience4j:
  circuitbreaker:
    instances:
      accountService:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        record-exceptions:
          - com.showcase.transfer.client.AccountServiceUnavailableException
        ignore-exceptions:
          - com.showcase.transfer.client.AccountRejectedException
  retry:
    instances:
      accountService:
        max-attempts: 3
        wait-duration: 200ms
        retry-exceptions:
          - com.showcase.transfer.client.AccountServiceUnavailableException
        ignore-exceptions:
          - com.showcase.transfer.client.AccountRejectedException
```

- [ ] **Step 3: Update `AccountClient`**

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountClient.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls Account Service over blocking HTTP. Every outcome collapses onto two
 * exceptions so the saga can branch on "rejected" versus "broken" without ever
 * seeing an HTTP status.
 *
 * <p>Wrapped in Resilience4j CircuitBreaker + Retry (see application.yml's
 * resilience4j.* "accountService" instance): {@link AccountRejectedException} is an
 * ignored exception (never retried, never trips the breaker — a business rejection
 * is not infrastructure trouble), {@link AccountServiceUnavailableException} is
 * retried. When the circuit is open, Resilience4j's own {@code CallNotPermittedException}
 * is thrown by the AOP proxy BEFORE the method body runs, so it cannot be caught inside
 * these methods — it is handled by the fallback methods below instead, which remap it
 * onto {@link AccountServiceUnavailableException} so an open circuit looks, correctly,
 * exactly like Account being unavailable to every caller of this class.
 */
public class AccountClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AccountClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService", fallbackMethod = "getAccountFallback")
    public AccountView getAccount(UUID accountId) {
        // A 204, or a 200 with Content-Length: 0, makes the message converter return null.
        // Without this guard the saga NPEs on account.balance() instead of branching.
        AccountView account = call(() -> restClient.get()
                .uri("/accounts/{id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
                .body(AccountView.class));
        if (account == null) {
            throw new AccountServiceUnavailableException(
                    "Account Service returned an empty body for " + accountId);
        }
        return account;
    }

    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService", fallbackMethod = "debitCreditFallback")
    public void debit(UUID accountId, BigDecimal amount, String idempotencyKey) {
        post(accountId, amount, "debit", idempotencyKey);
    }

    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService", fallbackMethod = "debitCreditFallback")
    public void credit(UUID accountId, BigDecimal amount, String idempotencyKey) {
        post(accountId, amount, "credit", idempotencyKey);
    }

    private void post(UUID accountId, BigDecimal amount, String operation, String idempotencyKey) {
        call(() -> restClient.post()
                .uri("/accounts/{id}/{operation}", accountId, operation)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("amount", amount))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                // Treat anything that is not 2xx as unavailable: 5xx and 3xx (redirects) are
                // both unconfirmed outcomes. If a 3xx fell through and was treated as success,
                // a redirect in the debit leg would silently create money (credit committed,
                // debit unconfirmed).
                .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
                .toBodilessEntity());
    }

    /**
     * Connection refused, DNS failure and read timeout all surface as
     * ResourceAccessException rather than a status code, so they are translated here
     * instead of in an onStatus handler.
     */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            // Deliberately the broad superclass, not just ResourceAccessException.
            // ResourceAccessException covers connect-refused/DNS/read-timeout, but a 2xx
            // carrying an unreadable body throws UnknownContentTypeException (proxy
            // interstitial HTML) or a plain RestClientException (malformed JSON) instead.
            // Catching only the narrow type lets those escape BOTH domain exceptions and
            // reach the saga unhandled -- which defeats this class entirely. The two
            // domain exceptions do not extend RestClientException, so they still pass
            // through untouched.
            throw new AccountServiceUnavailableException("Account Service call failed: " + ex.getMessage(), ex);
        }
    }

    private void rejected(HttpRequest request, ClientHttpResponse response) {
        AccountProblem problem = readProblem(response);
        throw new AccountRejectedException(problem.code(), problem.detail());
    }

    private void unavailable(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new AccountServiceUnavailableException(
                "Account Service returned " + response.getStatusCode().value());
    }

    /**
     * An error body that is not a well-formed problem document (an HTML page from a
     * proxy, an empty body) must still produce a domain exception, never a parse error.
     */
    private AccountProblem readProblem(ClientHttpResponse response) {
        try {
            AccountProblem problem = objectMapper.readValue(response.getBody(), AccountProblem.class);
            if (problem == null || problem.code() == null) {
                return new AccountProblem("UNKNOWN", "Account Service returned an unrecognised error body");
            }
            // Account may omit "detail"; without this the exception message reads "...: null".
            return (problem.detail() != null) ? problem : new AccountProblem(problem.code(), "");
        } catch (Exception ex) {
            return new AccountProblem("UNKNOWN", "Account Service returned an unreadable error body");
        }
    }

    /**
     * Invoked by Resilience4j instead of getAccount's body once retries are exhausted or the
     * circuit is open. AccountRejectedException never reaches here — it is an ignored
     * exception (see application.yml), so it propagates straight past Resilience4j
     * untouched, exactly as it did before this class had any resilience wrapping.
     */
    private AccountView getAccountFallback(UUID accountId, Throwable t) {
        throw asUnavailable(t);
    }

    /** Shared fallback for debit and credit — both have the same (UUID, BigDecimal, String) shape. */
    private void debitCreditFallback(UUID accountId, BigDecimal amount, String idempotencyKey, Throwable t) {
        throw asUnavailable(t);
    }

    private static AccountServiceUnavailableException asUnavailable(Throwable t) {
        if (t instanceof AccountServiceUnavailableException already) {
            return already;
        }
        return new AccountServiceUnavailableException("Account Service call failed: " + t.getMessage(), t);
    }
}
```

- [ ] **Step 4: Pass a deterministic idempotency key from `TransferService`**

In `transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java`, change the two call sites:

```java
// Was: accountClient.debit(fromAccountId, amount);
accountClient.debit(fromAccountId, amount, transfer.getId() + ":debit");
```

```java
// Was: accountClient.credit(toAccountId, amount);
accountClient.credit(toAccountId, amount, transfer.getId() + ":credit");
```

Nothing else in `TransferService` changes — `transfer.getId()` is already populated at both call sites (the initial `transferRepository.save(new Transfer(...))` on line 46 assigns it before execution reaches either branch).

- [ ] **Step 5: Update `AccountClientTest` for the new signature**

This file tests request/response mapping only — constructed directly (`new AccountClient(...)`), it never goes through a Spring proxy, so `@CircuitBreaker`/`@Retry` are inert here regardless of what's written on the class. Retry/circuit-breaker *behavior* is Step 6's job.

```java
// transfer-service/src/test/java/com/showcase/transfer/client/AccountClientTest.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountClientTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://account-service:8081";

    private MockRestServiceServer server;
    private AccountClient accountClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        accountClient = new AccountClient(builder.build(), new ObjectMapper());
    }

    @Test
    void getAccountReturnsTheBalance() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":100.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        AccountView account = accountClient.getAccount(ACCOUNT_ID);

        assertThat(account.id()).isEqualTo(ACCOUNT_ID);
        assertThat(account.balance()).isEqualByComparingTo("100.00");
        server.verify();
    }

    @Test
    void getAccountThrowsRejectedWithAccountCodeOn404() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + ACCOUNT_ID));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> {
                    assertThat(((AccountRejectedException) thrown).getCode()).isEqualTo("ACCOUNT_NOT_FOUND");
                    assertThat(((AccountRejectedException) thrown).getDetail())
                            .isEqualTo("Account not found: " + ACCOUNT_ID);
                });
        server.verify();
    }

    @Test
    void debitPostsTheAmountAndIdempotencyKeyAndSucceeds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(40.00))
                .andExpect(header("Idempotency-Key", "transfer-1:debit"))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":60.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit");

        server.verify();
    }

    @Test
    void debitThrowsRejectedOnInsufficientFunds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "not enough money"));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("INSUFFICIENT_FUNDS"));
        server.verify();
    }

    @Test
    void creditPostsTheIdempotencyKey() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andExpect(header("Idempotency-Key", "transfer-1:credit"))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":140.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:credit");

        server.verify();
    }

    @Test
    void throwsUnavailableOnServerError() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:credit"))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void throwsUnavailableWhenTheConnectionFails() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andRespond(withException(new java.net.ConnectException("connection refused")));

        assertThatThrownBy(() -> accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:credit"))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void throwsRejectedWithUnknownCodeWhenTheErrorBodyIsNotAProblem() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway says no</html>"));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("UNKNOWN"));
        server.verify();
    }

    @Test
    void getAccountThrowsUnavailableWhenTheBodyIsEmpty() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void getAccountThrowsUnavailableWhenA2xxBodyIsNotReadable() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withSuccess("<html>proxy interstitial</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void throwsRejectedWithUnknownCodeWhenTheErrorHasNoBodyAtAll() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("UNKNOWN"));
        server.verify();
    }

    @Test
    void debitThrowsUnavailableOn3xxRedirect() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void getAccountThrowsUnavailableOn3xxRedirect() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    private static org.springframework.test.web.client.ResponseCreator problem(
            HttpStatus status, String code, String detail) {
        return withStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"type":"https://showcase.example/errors/x","title":"t","status":%d,
                         "detail":"%s","code":"%s","timestamp":"2026-09-10T12:00:00Z"}
                        """.formatted(status.value(), detail, code));
    }
}
```

- [ ] **Step 6: Add a Resilience4j behavior test**

This exercises the exact Retry/CircuitBreaker config values above against this client's exception classification, using Resilience4j's core API directly rather than Spring AOP — `AccountClient` is constructed directly here too, so the `@CircuitBreaker`/`@Retry` annotations are inert; the test hand-builds equivalent decorators from the same numbers instead of depending on Spring's annotation processing (which needs a full application context to exercise honestly). The fallback-method remapping of `CallNotPermittedException` is Spring-AOP-specific and isn't reachable this way — it's covered by the phase's manual verification checklist instead (see the end of this document).

```java
// transfer-service/src/test/java/com/showcase/transfer/client/AccountClientResilienceTest.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountClientResilienceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://account-service:8081";

    private MockRestServiceServer server;
    private AccountClient accountClient;
    private Retry retry;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        accountClient = new AccountClient(builder.build(), new ObjectMapper());

        // Mirrors application.yml's resilience4j.*.instances.accountService exactly --
        // if these numbers and that file drift apart, this test is the tripwire.
        retry = Retry.of("accountService", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(AccountServiceUnavailableException.class)
                .ignoreExceptions(AccountRejectedException.class)
                .build());
        circuitBreaker = CircuitBreaker.of("accountService", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(AccountServiceUnavailableException.class)
                .ignoreExceptions(AccountRejectedException.class)
                .build());
    }

    private <T> T retryAndBreak(Supplier<T> call) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    @Test
    void retriesATransientFailureAndEventuallySucceeds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":100.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        AccountView account = retryAndBreak(() -> accountClient.getAccount(ACCOUNT_ID));

        assertThat(account.balance()).isEqualByComparingTo("100.00");
        // Exactly the two expectations registered above were consumed -- if Retry had not
        // fired, the first (failing) response alone would have propagated and this call
        // would have thrown instead of returning.
        server.verify();
    }

    @Test
    void doesNotRetryABusinessRejection() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type":"https://showcase.example/errors/x","title":"t","status":404,
                                 "detail":"not found","code":"ACCOUNT_NOT_FOUND","timestamp":"2026-09-10T12:00:00Z"}
                                """));

        assertThatThrownBy(() -> retryAndBreak(() -> accountClient.getAccount(ACCOUNT_ID)))
                .isInstanceOf(AccountRejectedException.class);
        // Only one request was registered above -- if this had been retried, verify() would
        // fail with "no further requests expected" instead of this test reaching this line.
        server.verify();
    }

    @Test
    void opensAfterEnoughFailuresAndShortCircuitsWithoutANewRequest() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        // Drive exactly 5 failures through the circuit breaker alone (bypassing Retry here,
        // so each iteration is exactly one HTTP call) to reach minimumNumberOfCalls.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> CircuitBreaker.decorateSupplier(circuitBreaker,
                    () -> accountClient.getAccount(ACCOUNT_ID)).get())
                    .isInstanceOf(AccountServiceUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> CircuitBreaker.decorateSupplier(circuitBreaker,
                () -> accountClient.getAccount(ACCOUNT_ID)).get())
                .isInstanceOf(CallNotPermittedException.class);
        // No 6th expectation was registered -- if the circuit had not actually opened, the
        // call above would attempt a real 6th request and MockRestServiceServer would fail
        // with "no further requests expected" instead of this reaching CallNotPermittedException.
        server.verify();
    }
}
```

- [ ] **Step 7: Run the client tests**

Run: `./mvnw -pl transfer-service test -Dtest=AccountClientTest,AccountClientResilienceTest`
Expected: PASS, all tests.

- [ ] **Step 8: Update `TransferServiceTest` for the new signature**

```java
// transfer-service/src/test/java/com/showcase/transfer/service/TransferServiceTest.java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.client.AccountView;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");
    // The mock repository below never runs real JPA id generation, so transfer.getId() would
    // otherwise stay null throughout every test -- repositoryEchoesSaves() assigns this
    // instead, the way a real save() would, so the idempotency keys TransferService builds
    // from transfer.getId() are stable and assertable.
    private static final UUID TRANSFER_ID = UUID.randomUUID();

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

    private TransferService transferService;

    /**
     * The status of each Transfer as it was at the moment {@code save} was called.
     *
     * <p>An ArgumentCaptor cannot express this: the repository echoes back the same mutable
     * entity, so every captured value is the one instance, and by assertion time it carries
     * only its final status. Snapshotting inside the stub is the only way to pin "PENDING was
     * persisted before any money moved".
     */
    private final List<TransferStatus> statusesAtSaveTime = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, accountClient);
    }

    // Called per-test rather than from setUp: the self-transfer test never reaches the
    // repository, and Mockito strict stubbing rightly fails an unused stub. Keeping
    // strict stubbing is worth the extra line.
    private void repositoryEchoesSaves() {
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
            }
            statusesAtSaveTime.add(saved.getStatus());
            return saved;
        });
    }

    private void bothAccountsExist() {
        when(accountClient.getAccount(FROM)).thenReturn(new AccountView(FROM, new BigDecimal("100.00")));
        when(accountClient.getAccount(TO)).thenReturn(new AccountView(TO, new BigDecimal("5.00")));
    }

    @Test
    void completesWhenBothLegsSucceed() {
        repositoryEchoesSaves();
        bothAccountsExist();

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getSettledAt()).isNotNull();
        assertThat(result.getFailureCode()).isNull();
        verify(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");

        // PENDING must be durable BEFORE any money moves, so a crash mid-saga leaves
        // evidence. Without these two assertions, moving the first save to the end of
        // execute() would leave every other test in this class green.
        InOrder inOrder = inOrder(transferRepository, accountClient);
        inOrder.verify(transferRepository).save(any(Transfer.class));
        inOrder.verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.PENDING, TransferStatus.COMPLETED);
    }

    @Test
    void failsWithoutDebitingWhenTheSourceAccountDoesNotExist() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM))
                .thenThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void failsWithoutDebitingWhenTheDestinationAccountDoesNotExist() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM)).thenReturn(new AccountView(FROM, new BigDecimal("100.00")));
        when(accountClient.getAccount(TO))
                .thenThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void failsWhenAccountServiceIsUnreachableDuringPreValidation() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM))
                .thenThrow(new AccountServiceUnavailableException("connection refused"));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        verify(accountClient, never()).debit(any(), any(), any());
    }

    @Test
    void failsWhenTheDebitIsRejectedForInsufficientFunds() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("INSUFFICIENT_FUNDS", "not enough money"))
                .when(accountClient).debit(eq(FROM), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(result.getFailureReason()).isEqualTo("not enough money");
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void failsWhenTheDebitCannotReachAccountService() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).debit(eq(FROM), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        // Pins ex.getMessage() as the recorded reason on the unavailable path, the way
        // ex.getDetail() is pinned on the rejected path.
        assertThat(result.getFailureReason()).contains("read timed out");
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void requiresCompensationWhenTheCreditIsRejected() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
    }

    @Test
    void requiresCompensationWhenTheCreditCannotReachAccountService() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        assertThat(result.getFailureReason()).contains("read timed out");
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> transferService.execute(FROM, FROM, AMOUNT))
                .isInstanceOf(SameAccountTransferException.class);

        verify(accountClient, never()).getAccount(any());
        // Passes today only because the constructor throws while the save argument is being
        // evaluated. Worth pinning: a rejected transfer must leave no row behind.
        verify(transferRepository, never()).save(any());
    }

    @Test
    void failsWhenAnUnexpectedErrorOccursBeforeTheDebit() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM)).thenThrow(new IllegalStateException("response mapper exploded"));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
        assertThat(result.getFailureReason()).contains("response mapper exploded");
        verify(accountClient, never()).debit(any(), any(), any());
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void requiresCompensationWhenAnUnexpectedErrorFollowsTheDebit() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new IllegalStateException("response mapper exploded"))
                .when(accountClient).credit(eq(TO), eq(AMOUNT), any());

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
        assertThat(result.getFailureReason()).contains("response mapper exploded");
        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
    }

    @Test
    void wrapsTheCauseWithTheTransferIdWhenPersistingTheTerminalStateFails() {
        OptimisticLockingFailureException boom = new OptimisticLockingFailureException("version conflict");
        // First save (PENDING) succeeds; the final save of the terminal state blows up.
        when(transferRepository.save(any(Transfer.class)))
                .thenAnswer(invocation -> {
                    Transfer saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
                    }
                    return saved;
                })
                .thenThrow(boom);
        bothAccountsExist();

        // The entity is already COMPLETED in memory, so re-marking it would throw
        // IllegalStateException from requirePending() and bury the real cause. The original
        // exception is wrapped rather than re-marked or swallowed: the wrapper carries the
        // id so the API can hand it back -- a row exists, still reading PENDING, and the
        // caller cannot reconcile anything without knowing which one -- and keeps the
        // original as its cause, which is the only real diagnostic.
        assertThatThrownBy(() -> transferService.execute(FROM, TO, AMOUNT))
                .isInstanceOf(TransferPersistenceException.class)
                .hasCause(boom);

        verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
        verify(accountClient).credit(eq(TO), eq(AMOUNT), any());
    }

    @Test
    void passesADeterministicIdempotencyKeyPerLeg() {
        repositoryEchoesSaves();
        bothAccountsExist();

        transferService.execute(FROM, TO, AMOUNT);

        verify(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
    }

    @Test
    void getTransferThrowsWhenTheTransferDoesNotExist() {
        UUID missing = UUID.randomUUID();
        when(transferRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getTransfer(missing))
                .isInstanceOf(TransferNotFoundException.class);
    }

    @Test
    void listTransfersFiltersByStatusOnlyWhenOneIsGiven() {
        transferService.listTransfers(null);
        verify(transferRepository).findAll();
        verify(transferRepository, never()).findByStatus(any());

        transferService.listTransfers(TransferStatus.FAILED);
        verify(transferRepository).findByStatus(TransferStatus.FAILED);
    }
}
```

- [ ] **Step 9: Run the whole Transfer test suite**

Run: `./mvnw -pl transfer-service test`
Expected: PASS, all tests.

- [ ] **Step 10: Commit**

```bash
git add pom.xml transfer-service/pom.xml transfer-service/src/main transfer-service/src/test
git commit -m "feat(transfer): Resilience4j CircuitBreaker+Retry and idempotency keys on AccountClient"
```

---

### Task 4: State machine changes and `CompensationScheduler.drainCompensationRequired`

**Files:**
- Modify: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferStatus.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/domain/Transfer.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/TransferServiceApplication.java`
- Modify (full rewrite): `transfer-service/src/test/java/com/showcase/transfer/domain/TransferTest.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/service/CompensationProperties.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java`
- Create: `transfer-service/src/test/java/com/showcase/transfer/service/CompensationSchedulerTest.java`
- Modify: `transfer-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `AccountClient.credit(UUID, BigDecimal, String)` (Task 3).
- Produces: `Transfer.markCompensated()`, `Transfer.markCompensationFailed(String)`, `TransferStatus.COMPENSATED`/`COMPENSATION_FAILED`, `CompensationProperties` — all consumed by Task 5.

- [ ] **Step 1: Add the two new terminal states**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferStatus.java
package com.showcase.transfer.domain;

public enum TransferStatus {
    /** Created, no leg attempted or the outcome is not yet known. */
    PENDING,
    /** Source debited and destination credited. */
    COMPLETED,
    /** Rejected before or during the debit. No money moved. */
    FAILED,
    /**
     * Source was debited but the destination credit did not succeed, so funds are
     * stranded at the source. CompensationScheduler drains this state by reconciling
     * against Account Service: it resolves to COMPLETED (the credit had already landed),
     * COMPENSATED (the source was credited back), or COMPENSATION_FAILED (manual review).
     */
    COMPENSATION_REQUIRED,
    /** The destination definitively never received the credit, and the source was credited back. */
    COMPENSATED,
    /** Both the destination credit and the source credit-back definitively failed. Manual review. */
    COMPENSATION_FAILED
}
```

- [ ] **Step 2: Update `TransferTest` for the widened and new transitions**

`markCompleted()` now succeeds from `COMPENSATION_REQUIRED` too (reconciliation found the credit had already landed) — the old assertion that it throws from that state is wrong under the new design and is removed, not just retargeted. Two new transitions, `markCompensated()` and `markCompensationFailed(String)`, are added along with their guards.

```java
// transfer-service/src/test/java/com/showcase/transfer/domain/TransferTest.java
package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal TEN = new BigDecimal("10.00");

    @Test
    void newTransferStartsPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getFromAccountId()).isEqualTo(FROM);
        assertThat(transfer.getToAccountId()).isEqualTo(TO);
        assertThat(transfer.getAmount()).isEqualByComparingTo("10.00");
        assertThat(transfer.getCreatedAt()).isNotNull();
        assertThat(transfer.getSettledAt()).isNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> new Transfer(FROM, FROM, TEN))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void rejectsANonPositiveAmount() {
        assertThatThrownBy(() -> new Transfer(FROM, TO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(FROM, TO, new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markCompletedSettlesTheTransfer() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markCompleted();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getSettledAt()).isNotNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void markCompletedAlsoSucceedsFromCompensationRequired() {
        // Reconciliation found the destination credit had already landed -- nothing to
        // reverse, the transfer genuinely completed.
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        transfer.markCompleted();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markFailedRecordsTheReason() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(transfer.getFailureReason()).isEqualTo("not enough money");
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensationRequiredRecordsTheUnderlyingCause() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        assertThat(transfer.getFailureReason()).isEqualTo("credit leg timed out");
    }

    @Test
    void markCompensatedRequiresCompensationRequired() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        transfer.markCompensated();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensatedThrowsFromPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        assertThatThrownBy(transfer::markCompensated).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markCompensationFailedRequiresCompensationRequired() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        transfer.markCompensationFailed("source account no longer exists -- manual review required");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        assertThat(transfer.getFailureReason()).isEqualTo("source account no longer exists -- manual review required");
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensationFailedThrowsFromPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        assertThatThrownBy(() -> transfer.markCompensationFailed("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSettledTransferCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompleted();

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(transfer::markCompensated).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationFailed("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsANullAccountId() {
        assertThatThrownBy(() -> new Transfer(null, TO, TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(FROM, null, TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesAnOverlongFailureReason() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x".repeat(600));

        assertThat(transfer.getFailureReason()).hasSize(512);
    }

    @Test
    void keepsAFailureReasonOfExactlyTheColumnLength() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x".repeat(512));

        assertThat(transfer.getFailureReason()).hasSize(512);
    }

    @Test
    void doesNotSplitASurrogatePairWhenTruncating() {
        // U+1F600 is two chars in UTF-16. Placed after 511 filler chars it straddles the
        // 512-char boundary: high surrogate at index 511, low surrogate at index 512.
        String emoji = new String(Character.toChars(0x1F600));
        String reason = "x".repeat(511) + emoji + "y".repeat(100);
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, reason);

        String stored = transfer.getFailureReason();
        assertThat(stored).hasSize(511);
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 1))).isFalse();
        assertThat(stored.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    void aFailedTransferCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(transfer::markCompensated).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationFailed("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTransferAwaitingCompensationCannotBeFailedOrReMarkedCompensationRequired() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR, "x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=TransferTest`
Expected: FAIL to compile — `Transfer` has no `markCompensated`/`markCompensationFailed` methods yet.

- [ ] **Step 4: Generalize the state guard and add the two new transitions**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/Transfer.java
package com.showcase.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID fromAccountId;

    @Column(nullable = false, updatable = false)
    private UUID toAccountId;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TransferFailureCode failureCode;

    @Column(length = 512)
    private String failureReason;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant settledAt;

    public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        if (fromAccountId == null || toAccountId == null) {
            throw new IllegalArgumentException("Both account ids are required");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new SameAccountTransferException(fromAccountId);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /** Reachable from PENDING (the live saga) or COMPENSATION_REQUIRED (reconciliation found the credit had already landed). */
    public void markCompleted() {
        requireStatus(TransferStatus.PENDING, TransferStatus.COMPENSATION_REQUIRED);
        this.status = TransferStatus.COMPLETED;
        this.settledAt = Instant.now();
    }

    public void markFailed(TransferFailureCode failureCode, String failureReason) {
        requireStatus(TransferStatus.PENDING);
        this.status = TransferStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    public void markCompensationRequired(TransferFailureCode failureCode, String failureReason) {
        requireStatus(TransferStatus.PENDING);
        this.status = TransferStatus.COMPENSATION_REQUIRED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    /** The destination definitively never received the credit; the source has now been credited back. */
    public void markCompensated() {
        requireStatus(TransferStatus.COMPENSATION_REQUIRED);
        this.status = TransferStatus.COMPENSATED;
        this.settledAt = Instant.now();
    }

    /** Both the destination credit and the source credit-back definitively failed. Manual review. */
    public void markCompensationFailed(String failureReason) {
        requireStatus(TransferStatus.COMPENSATION_REQUIRED);
        this.status = TransferStatus.COMPENSATION_FAILED;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    private void requireStatus(TransferStatus... allowed) {
        for (TransferStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
                "Transfer %s is %s, expected one of %s".formatted(id, status, Arrays.toString(allowed)));
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= 512) {
            return reason;
        }
        // Cutting at 512 blindly can split a surrogate pair (an emoji straddling the boundary),
        // leaving an unpaired surrogate that the PostgreSQL driver refuses to encode. That would
        // recreate the exact failure this method exists to prevent: a recorded failure silently
        // becoming an unrecorded one. Drop the lone high surrogate instead.
        int end = Character.isHighSurrogate(reason.charAt(511)) ? 511 : 512;
        return reason.substring(0, end);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl transfer-service test -Dtest=TransferTest`
Expected: PASS, all tests.

- [ ] **Step 6: Add `CompensationProperties`**

```java
// transfer-service/src/main/java/com/showcase/transfer/service/CompensationProperties.java
package com.showcase.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transfer.compensation")
public record CompensationProperties(Duration sweepInterval, Duration pendingStaleAfter) {

    // See AccountClientProperties for why these are defaulted here rather than trusted to
    // always be set: Boot's binder skips a null Duration silently.
    public CompensationProperties {
        sweepInterval = (sweepInterval != null) ? sweepInterval : Duration.ofSeconds(15);
        pendingStaleAfter = (pendingStaleAfter != null) ? pendingStaleAfter : Duration.ofSeconds(120);
    }
}
```

```yaml
# transfer-service/src/main/resources/application.yml -- append at the end
transfer:
  compensation:
    sweep-interval: 15s
    pending-stale-after: 120s
```

- [ ] **Step 7: Enable scheduling**

```java
// transfer-service/src/main/java/com/showcase/transfer/TransferServiceApplication.java
package com.showcase.transfer;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@OpenAPIDefinition(
        info = @Info(
                title = "Transfer Service API",
                version = "v1",
                description = "Orchestrates the transfer saga across Account Service."
        )
)
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TransferServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}
```

- [ ] **Step 8: Write the failing `CompensationSchedulerTest`**

```java
// transfer-service/src/test/java/com/showcase/transfer/service/CompensationSchedulerTest.java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompensationSchedulerTest {

    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

    private CompensationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CompensationScheduler(transferRepository, accountClient,
                new CompensationProperties(Duration.ofSeconds(15), Duration.ofSeconds(120)));
    }

    private Transfer strandedTransfer() {
        Transfer transfer = new Transfer(FROM, TO, AMOUNT);
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        return transfer;
    }

    @Test
    void reconciledSuccessMarksTheTransferCompleted() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        // credit(...) succeeds by default (void mock, no stubbing needed) -- the destination
        // had actually already received the money.

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        verify(accountClient, never()).credit(eq(FROM), any(), any());
        verify(transferRepository).save(transfer);
    }

    @Test
    void definitiveRejectionThenSuccessfulReversalMarksCompensated() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        // The source credit-back succeeds (void mock, no stubbing needed).

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        verify(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");
    }

    @Test
    void definitiveRejectionThenReversalAlsoRejectedMarksCompensationFailed() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM))
                .when(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        assertThat(transfer.getFailureReason()).contains("manual review required");
    }

    @Test
    void stillUnavailableLeavesTheTransferAwaitingTheNextSweep() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void destinationRejectedButSourceReversalStillUnavailableLeavesAwaitingTheNextSweep() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(transferRepository, never()).save(any());
    }
}
```

- [ ] **Step 9: Run the test to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=CompensationSchedulerTest`
Expected: FAIL to compile — `CompensationScheduler` does not exist yet.

- [ ] **Step 10: Implement `CompensationScheduler`**

```java
// transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Automatically resolves the ambiguity Phase 2 deliberately left open: COMPENSATION_REQUIRED
 * transfers (drainCompensationRequired, this task) and stale PENDING transfers
 * (sweepStalePending, the next task) are periodically reconciled against Account Service by
 * replaying the ambiguous call with its original idempotency key rather than guessing -- see
 * docs/phase-3-resilience-compensation-idempotency.md's Design Decisions.
 *
 * <p>Registered via {@link SchedulingConfigurer} rather than
 * {@code @Scheduled(fixedDelayString = ...)}: {@code @Scheduled}'s string form parses with
 * {@link java.time.Duration#parse}, which requires strict ISO-8601 ("PT15S"), not the "15s"
 * shorthand this project's application.yml files use everywhere else. Registering the interval
 * from {@link CompensationProperties} as milliseconds sidesteps that mismatch entirely.
 */
@Component
public class CompensationScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CompensationScheduler.class);

    private final TransferRepository transferRepository;
    private final AccountClient accountClient;
    private final CompensationProperties properties;

    public CompensationScheduler(TransferRepository transferRepository, AccountClient accountClient,
                                  CompensationProperties properties) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::drainCompensationRequired, properties.sweepInterval().toMillis());
    }

    // Package-private so CompensationSchedulerTest can invoke it directly, without going
    // through the scheduler registration machinery.
    void drainCompensationRequired() {
        List<Transfer> stranded = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED);
        for (Transfer transfer : stranded) {
            reconcileCredit(transfer);
        }
    }

    private void reconcileCredit(Transfer transfer) {
        try {
            accountClient.credit(transfer.getToAccountId(), transfer.getAmount(), transfer.getId() + ":credit");
            // The credit actually landed -- Transfer just did not know it yet. Nothing to
            // reverse; this transfer genuinely completed.
            transfer.markCompleted();
            transferRepository.save(transfer);
            log.info("Transfer {} reconciled as COMPLETED: the credit had already landed", transfer.getId());
        } catch (AccountRejectedException definitivelyRejected) {
            compensateSource(transfer, definitivelyRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still cannot reconcile the credit leg, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
        }
    }

    private void compensateSource(Transfer transfer, String rejectionDetail) {
        try {
            accountClient.credit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getId() + ":compensate");
            transfer.markCompensated();
            transferRepository.save(transfer);
            log.info("Transfer {} COMPENSATED: source credited back after destination definitively rejected [{}]",
                    transfer.getId(), rejectionDetail);
        } catch (AccountRejectedException sourceAlsoRejected) {
            transfer.markCompensationFailed(
                    "Destination rejected [%s], and crediting the source back also failed [%s] -- manual review required"
                            .formatted(rejectionDetail, sourceAlsoRejected.getDetail()));
            transferRepository.save(transfer);
            log.error("Transfer {} COMPENSATION_FAILED: manual review required. Destination [{}], source credit-back [{}]",
                    transfer.getId(), rejectionDetail, sourceAlsoRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} destination rejected [{}] but crediting the source back is still unavailable, "
                            + "will retry next sweep: {}",
                    transfer.getId(), rejectionDetail, stillUnavailable.getMessage());
        }
    }
}
```

- [ ] **Step 11: Run the test to verify it passes**

Run: `./mvnw -pl transfer-service test -Dtest=CompensationSchedulerTest`
Expected: PASS, 5 tests.

- [ ] **Step 12: Run the whole Transfer test suite**

Run: `./mvnw -pl transfer-service test`
Expected: PASS, all tests.

- [ ] **Step 13: Commit**

```bash
git add transfer-service/src/main transfer-service/src/test
git commit -m "feat(transfer): COMPENSATED/COMPENSATION_FAILED states and the compensation-required sweep"
```

---

### Task 5: `CompensationScheduler.sweepStalePending`

**Files:**
- Modify: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferRepository.java`
- Modify (full rewrite): `transfer-service/src/test/java/com/showcase/transfer/domain/TransferRepositoryTest.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java`
- Modify (full rewrite): `transfer-service/src/test/java/com/showcase/transfer/service/CompensationSchedulerTest.java`

**Interfaces:**
- Consumes: `Transfer.markCompensationRequired(...)`, `Transfer.markFailed(...)` (existing, from Phase 2), `CompensationProperties.pendingStaleAfter()` (Task 4).
- Produces: `TransferRepository.findByStatusAndCreatedAtBefore(TransferStatus, Instant)`.

- [ ] **Step 1: Add the repository query**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferRepository.java
package com.showcase.transfer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findByStatus(TransferStatus status);

    List<Transfer> findByStatusAndCreatedAtBefore(TransferStatus status, Instant cutoff);
}
```

- [ ] **Step 2: Test it**

```java
// transfer-service/src/test/java/com/showcase/transfer/domain/TransferRepositoryTest.java
package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class TransferRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void savesAndReloadsATransfer() {
        Transfer saved = transferRepository.save(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00")));

        Optional<Transfer> found = transferRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(found.get().getAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void persistsTheFailureCodeAsAString() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        Transfer saved = transferRepository.saveAndFlush(transfer);

        Transfer reloaded = transferRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(reloaded.getFailureReason()).isEqualTo("not enough money");
        assertThat(reloaded.getSettledAt()).isNotNull();
    }

    @Test
    void findsTransfersByStatus() {
        Transfer stranded = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        stranded.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        transferRepository.saveAndFlush(stranded);

        List<Transfer> found = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED);

        assertThat(found).extracting(Transfer::getId).contains(stranded.getId());
    }

    @Test
    void findsStalePendingTransfersOlderThanTheCutoff() {
        Transfer stale = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        ReflectionTestUtils.setField(stale, "createdAt", Instant.now().minus(Duration.ofMinutes(10)));
        transferRepository.saveAndFlush(stale);
        Transfer recent = transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00")));

        List<Transfer> found = transferRepository.findByStatusAndCreatedAtBefore(
                TransferStatus.PENDING, Instant.now().minus(Duration.ofSeconds(120)));

        assertThat(found).extracting(Transfer::getId).contains(stale.getId());
        assertThat(found).extracting(Transfer::getId).doesNotContain(recent.getId());
    }
}
```

- [ ] **Step 3: Run the test**

Run: `./mvnw -pl transfer-service test -Dtest=TransferRepositoryTest`
Expected: PASS, 4 tests.

- [ ] **Step 4: Add `sweepStalePending` and register it**

```java
// transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Automatically resolves the ambiguity Phase 2 deliberately left open: COMPENSATION_REQUIRED
 * transfers (drainCompensationRequired) and stale PENDING transfers (sweepStalePending) are
 * periodically reconciled against Account Service by replaying the ambiguous call with its
 * original idempotency key rather than guessing -- see
 * docs/phase-3-resilience-compensation-idempotency.md's Design Decisions.
 *
 * <p>Registered via {@link SchedulingConfigurer} rather than
 * {@code @Scheduled(fixedDelayString = ...)}: {@code @Scheduled}'s string form parses with
 * {@link java.time.Duration#parse}, which requires strict ISO-8601 ("PT15S"), not the "15s"
 * shorthand this project's application.yml files use everywhere else. Registering the interval
 * from {@link CompensationProperties} as milliseconds sidesteps that mismatch entirely.
 */
@Component
public class CompensationScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CompensationScheduler.class);

    private final TransferRepository transferRepository;
    private final AccountClient accountClient;
    private final CompensationProperties properties;

    public CompensationScheduler(TransferRepository transferRepository, AccountClient accountClient,
                                  CompensationProperties properties) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        long intervalMillis = properties.sweepInterval().toMillis();
        registrar.addFixedDelayTask(this::drainCompensationRequired, intervalMillis);
        registrar.addFixedDelayTask(this::sweepStalePending, intervalMillis);
    }

    // Package-private so CompensationSchedulerTest can invoke it directly, without going
    // through the scheduler registration machinery.
    void drainCompensationRequired() {
        List<Transfer> stranded = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED);
        for (Transfer transfer : stranded) {
            reconcileCredit(transfer);
        }
    }

    void sweepStalePending() {
        Instant cutoff = Instant.now().minus(properties.pendingStaleAfter());
        List<Transfer> stale = transferRepository.findByStatusAndCreatedAtBefore(TransferStatus.PENDING, cutoff);
        for (Transfer transfer : stale) {
            reconcileDebit(transfer);
        }
    }

    private void reconcileCredit(Transfer transfer) {
        try {
            accountClient.credit(transfer.getToAccountId(), transfer.getAmount(), transfer.getId() + ":credit");
            // The credit actually landed -- Transfer just did not know it yet. Nothing to
            // reverse; this transfer genuinely completed.
            transfer.markCompleted();
            transferRepository.save(transfer);
            log.info("Transfer {} reconciled as COMPLETED: the credit had already landed", transfer.getId());
        } catch (AccountRejectedException definitivelyRejected) {
            compensateSource(transfer, definitivelyRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still cannot reconcile the credit leg, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
        }
    }

    private void compensateSource(Transfer transfer, String rejectionDetail) {
        try {
            accountClient.credit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getId() + ":compensate");
            transfer.markCompensated();
            transferRepository.save(transfer);
            log.info("Transfer {} COMPENSATED: source credited back after destination definitively rejected [{}]",
                    transfer.getId(), rejectionDetail);
        } catch (AccountRejectedException sourceAlsoRejected) {
            transfer.markCompensationFailed(
                    "Destination rejected [%s], and crediting the source back also failed [%s] -- manual review required"
                            .formatted(rejectionDetail, sourceAlsoRejected.getDetail()));
            transferRepository.save(transfer);
            log.error("Transfer {} COMPENSATION_FAILED: manual review required. Destination [{}], source credit-back [{}]",
                    transfer.getId(), rejectionDetail, sourceAlsoRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} destination rejected [{}] but crediting the source back is still unavailable, "
                            + "will retry next sweep: {}",
                    transfer.getId(), rejectionDetail, stillUnavailable.getMessage());
        }
    }

    /**
     * Resolves only the debit leg. It never independently re-checks the credit leg: if the
     * debit is confirmed landed, the row is promoted to COMPENSATION_REQUIRED via the same
     * markCompensationRequired() the live saga's own strand() uses, and reconcileCredit()
     * resolves it on a later pass. One consequence worth remembering, not a bug: a stale
     * PENDING row where BOTH legs actually landed (only the final save failed) still passes
     * through COMPENSATION_REQUIRED for one extra sweep before self-correcting to COMPLETED.
     */
    private void reconcileDebit(Transfer transfer) {
        try {
            accountClient.debit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getId() + ":debit");
            transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR,
                    "Recovered from a stale PENDING row: the debit leg is confirmed landed, the credit leg is unresolved");
            transferRepository.save(transfer);
            log.info("Transfer {} promoted from stale PENDING to COMPENSATION_REQUIRED: debit confirmed landed",
                    transfer.getId());
        } catch (AccountRejectedException definitivelyRejected) {
            transfer.markFailed(TransferFailureCode.fromAccountCode(definitivelyRejected.getCode()),
                    definitivelyRejected.getDetail());
            transferRepository.save(transfer);
            log.info("Transfer {} recovered from stale PENDING as FAILED: debit never landed [{}]",
                    transfer.getId(), definitivelyRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still stale PENDING, debit leg still unresolved, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
        }
    }
}
```

- [ ] **Step 5: Add the three new branches to `CompensationSchedulerTest`**

```java
// transfer-service/src/test/java/com/showcase/transfer/service/CompensationSchedulerTest.java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompensationSchedulerTest {

    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

    private CompensationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CompensationScheduler(transferRepository, accountClient,
                new CompensationProperties(Duration.ofSeconds(15), Duration.ofSeconds(120)));
    }

    private Transfer strandedTransfer() {
        Transfer transfer = new Transfer(FROM, TO, AMOUNT);
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        return transfer;
    }

    private Transfer stalePendingTransfer() {
        Transfer transfer = new Transfer(FROM, TO, AMOUNT);
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        return transfer;
    }

    @Test
    void reconciledSuccessMarksTheTransferCompleted() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        // credit(...) succeeds by default (void mock, no stubbing needed) -- the destination
        // had actually already received the money.

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        verify(accountClient, never()).credit(eq(FROM), any(), any());
        verify(transferRepository).save(transfer);
    }

    @Test
    void definitiveRejectionThenSuccessfulReversalMarksCompensated() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        // The source credit-back succeeds (void mock, no stubbing needed).

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        verify(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");
    }

    @Test
    void definitiveRejectionThenReversalAlsoRejectedMarksCompensationFailed() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM))
                .when(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        assertThat(transfer.getFailureReason()).contains("manual review required");
    }

    @Test
    void stillUnavailableLeavesTheTransferAwaitingTheNextSweep() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void destinationRejectedButSourceReversalStillUnavailableLeavesAwaitingTheNextSweep() {
        Transfer transfer = strandedTransfer();
        when(transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED)).thenReturn(List.of(transfer));
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT, TRANSFER_ID + ":credit");
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void staleDebitRejectedMarksTheTransferFailed() {
        Transfer transfer = stalePendingTransfer();
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any()))
                .thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM))
                .when(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");

        scheduler.sweepStalePending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void staleDebitConfirmedLandedPromotesToCompensationRequired() {
        Transfer transfer = stalePendingTransfer();
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any()))
                .thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);
        // debit(...) succeeds by default (void mock, no stubbing needed).

        scheduler.sweepStalePending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        verify(accountClient, never()).credit(any(), any(), any());
    }

    @Test
    void staleDebitStillAmbiguousStaysPending() {
        Transfer transfer = stalePendingTransfer();
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any()))
                .thenReturn(List.of(transfer));
        doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).debit(FROM, AMOUNT, TRANSFER_ID + ":debit");

        scheduler.sweepStalePending();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        verify(transferRepository, never()).save(any());
    }
}
```

- [ ] **Step 6: Run the whole Transfer test suite**

Run: `./mvnw -pl transfer-service test`
Expected: PASS, all tests.

- [ ] **Step 7: Commit**

```bash
git add transfer-service/src/main transfer-service/src/test
git commit -m "feat(transfer): sweep and recover stale PENDING transfers"
```

---

### Task 6: README and roadmap

**Files:**
- Modify: `README.md`
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: nothing new — documents the finished behavior of Tasks 1-5.

- [ ] **Step 1: Update the README's debit/credit examples and error notes**

```markdown
<!-- README.md: replace the "Debit it" / "Credit it" curl examples -->
    # Debit it (Idempotency-Key is required -- a retry with the same key is a no-op, not a
    # second debit)
    curl -X POST http://localhost:8081/accounts/<id>/debit \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: demo-debit-1" \
      -d '{"amount": 40.00}'

    # Credit it
    curl -X POST http://localhost:8081/accounts/<id>/credit \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: demo-credit-1" \
      -d '{"amount": 15.00}'
```

```markdown
<!-- README.md: replace the "Errors are RFC 7807..." block -->
Errors are RFC 7807 problem documents with a stable `code`:

    # Insufficient funds                  -> 422 INSUFFICIENT_FUNDS, no money moves
    # Unknown account                     -> 422 ACCOUNT_NOT_FOUND, no money moves
    # Missing Idempotency-Key header      -> 400 VALIDATION_FAILED (debit/credit only)
    # Idempotency key reused with         -> 409 IDEMPOTENCY_KEY_CONFLICT (should never
    #   different parameters                 happen in normal operation)
    # Account down during pre-validation  -> after Retry/CircuitBreaker exhaust their
    #                                         attempts, 503 ACCOUNT_SERVICE_UNAVAILABLE,
    #                                         no money moves
    # Account down during the debit       -> after Retry/CircuitBreaker exhaust their
    #                                         attempts, 503 ACCOUNT_SERVICE_UNAVAILABLE,
    #                                         outcome UNKNOWN: recorded FAILED, but the
    #                                         debit may have committed -- resolved
    #                                         automatically by the stale-PENDING sweep below
```

```markdown
<!-- README.md: replace the "Known gap: COMPENSATION_REQUIRED" section -->
### Automatic compensation and idempotency

Transfer Service wraps every call into Account Service in a CircuitBreaker + Retry
(Resilience4j), and every debit/credit carries a deterministic `Idempotency-Key`
(`"{transferId}:debit"`, `"{transferId}:credit"`) so a retry can never double-move money.

If the debit succeeds and the credit then fails, the money is momentarily stranded at the
source and recorded `COMPENSATION_REQUIRED`. A background sweep (`transfer.compensation.sweep-interval`,
default 15s) resolves it automatically, by replaying the ambiguous call rather than guessing:

- If the destination credit had actually already landed (a lost response, not a lost
  request) — the transfer is marked `COMPLETED`. Nothing to reverse.
- If the destination definitively rejects it, the source is credited back and the transfer
  is marked `COMPENSATED`.
- If crediting the source back also definitively fails, the transfer is marked
  `COMPENSATION_FAILED` — a manual-review terminal state, logged at ERROR.

A separate sweep (`transfer.compensation.pending-stale-after`, default 120s) recovers
transfers stuck at `PENDING` — e.g. the process crashed mid-saga — the same way, starting
from the debit leg.

    curl "http://localhost:8082/transfers?status=COMPENSATION_REQUIRED"
    curl "http://localhost:8082/transfers?status=COMPENSATED"
    curl "http://localhost:8082/transfers?status=COMPENSATION_FAILED"
```

- [ ] **Step 2: Update the roadmap**

```markdown
<!-- docs/roadmap.md: replace row 3 of the phase table -->
| 3 | [Resilience4j + Compensation + Idempotency](phase-3-resilience-compensation-idempotency.md) | ✅ Done | CircuitBreaker/Retry on Transfer → Account; `AccountOperation` as the idempotency ledger backing required `Idempotency-Key` on debit/credit; `CompensationScheduler` auto-drains `COMPENSATION_REQUIRED` and stale `PENDING` via idempotent replay, resolving to `COMPLETED`, `COMPENSATED`, or the manual-review `COMPENSATION_FAILED` |
```

```markdown
<!-- docs/roadmap.md: update the deferred-items list -->
- Flyway vs. `ddl-auto` for schema management — revisit before Phase 4 or later deploys to
  three service schemas (Account + Transfer + the outbox). Currently using `ddl-auto: update`.
```

(Remove the "Idempotency keys for debit/credit — Phase 3 item" line entirely — it's done, not deferred.)

- [ ] **Step 3: Commit**

```bash
git add README.md docs/roadmap.md
git commit -m "docs: document automatic compensation, idempotency keys, and update the roadmap"
```

---

## Verification Checklist

Before calling this phase done, confirm each of these by running the command and reading the output — not by assuming:

- [ ] `./mvnw -B test` passes for both modules (this is what CI runs)
- [ ] `docker compose down -v && docker compose up --build -d` brings all three containers to `(healthy)`
- [ ] A debit/credit with no `Idempotency-Key` header returns `400` / `VALIDATION_FAILED`
- [ ] The same debit repeated with the same `Idempotency-Key` does not change the balance a second time (`curl` twice, compare `GET /accounts/{id}` before and after)
- [ ] Reusing an `Idempotency-Key` with a different amount returns `409` / `IDEMPOTENCY_KEY_CONFLICT`
- [ ] With `account-service` stopped, a transfer still returns `503` / `ACCOUNT_SERVICE_UNAVAILABLE` — check the timing: it should take noticeably longer than Phase 2's single-attempt timeout (Retry is now attempting up to 3 times) but still resolve, not hang indefinitely
- [ ] Force a `COMPENSATION_REQUIRED` transfer (stop `account-service` mid-credit, or add a temporary fault), then restart `account-service` and confirm the transfer resolves to `COMPLETED` or `COMPENSATED` within one `sweep-interval` without manual intervention — `curl "http://localhost:8082/transfers?status=COMPENSATION_REQUIRED"` should go back to empty
- [ ] `GET /transfers` lists every status, including the two new ones (`COMPENSATED`, `COMPENSATION_FAILED`) when applicable
- [ ] Both Swagger UIs still load: `http://localhost:8081/swagger-ui.html` and `http://localhost:8082/swagger-ui.html`
- [ ] `grep -rn "resilience4j" transfer-service/pom.xml` shows the pinned version actually started successfully (re-check `docker compose up` logs for `transfer-service` — no `NoClassDefFoundError`/`BeanCreationException` on startup, the same class of failure the springdoc pin already warned about once)
