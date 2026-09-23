# Transactional Outbox + Kafka + Notification Implementation Phase

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Transfer Service a transactional outbox that records every terminal transfer outcome, a scheduled poller that publishes those outcomes to Kafka (KRaft mode), and a new stateless Notification Service that consumes them and logs a "notification sent" — closing the loop the design doc calls out as the project's asynchronous-eventing demonstration.

**Architecture:** A single new choke-point service, `TransferSaveService`, becomes the only place `transfer-service` persists a `Transfer` after a status transition; it derives whether an outbox row is needed from the status alone (`OutboxEventType.forStatus`), so neither `TransferService` (the live saga) nor `CompensationScheduler` (the async sweep) has to separately reason about which of the six call sites across those two classes are "event-worthy." A second scheduled poller, `OutboxPublisher`, drains unpublished rows to two Kafka topics (`transfer.completed`, `transfer.failed`) using the exact `SchedulingConfigurer` + `@ConfigurationProperties` pattern `CompensationScheduler` already established. A new `notification-service` module consumes both topics and logs.

**Tech Stack (additions):** `spring-kafka` (version inherited from the `spring-boot-starter-parent` 3.3.4 BOM — no explicit pin, unlike resilience4j/springdoc, but verify the resolved version once via `mvn dependency:tree` in Task 3), `org.testcontainers:kafka` (version inherited from the root POM's pinned `testcontainers.version`), `apache/kafka` Docker image (KRaft mode, no Zookeeper) for Compose.

**Spec:** [docs/microservices-showcase-design.md](microservices-showcase-design.md) §2, §3, §4, §5, §6, §7. Design decisions for this phase were brainstormed with the user on 2026-09-15; see this doc's Design Decisions section for what was decided and why.

## Global Constraints

- Java 21 floor; Spring Boot 3.3.4. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads enabled. No reactive types — this includes the Kafka producer/consumer code: use `spring-kafka`'s blocking `KafkaTemplate`/`@KafkaListener`, not `ReactiveKafkaProducerTemplate`. (spec §3)
- `ddl-auto: update` stays; Flyway is **re-deferred**, explicitly, for the third time (Phases 1–3 also deferred it) — this was a deliberate decision this session, not an oversight. The outbox table lives inside Transfer's existing database, so no init-script change is needed.
- Integration tests use Testcontainers against real infrastructure — Postgres as before, and now real Kafka too (`org.testcontainers:kafka`), never embedded/mocked brokers, consistent with spec §6 ("Testcontainers, real Postgres/Kafka — not H2/mocks").
- Lombok for entity boilerplate: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic. (CLAUDE.md)
- No Kubernetes/service mesh; local deployment is Docker Compose only, single instance per service — this is why `OutboxPublisher`, like `CompensationScheduler`, needs no distributed locking or leader election.
- Never commit directly to `master`; work happens on a `feature/phase-4-*` branch, one PR per task, stop after each. Subagent review is manual, on request — not automatic. (CLAUDE.md)
- springdoc-openapi stays pinned to `2.6.0`; resilience4j stays pinned to `2.4.0`. (docs/roadmap.md) *(Superseded in Phase 8: this pin held only for Boot 3.3.x; Boot 3.5.16 needs springdoc 2.8.17 — see `CLAUDE.md`, "Executing implementation phases".)*
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review. Always name the model explicitly. (CLAUDE.md)

## Scope Boundary

**In scope:** the outbox table + `TransferSaveService` choke point in `transfer-service`; converting every terminal-state save in `TransferService`/`CompensationScheduler` to go through it; the Kafka producer config and `OutboxPublisher` poller; the outbox-backlog gauge; the new `notification-service` module (Kafka consumer only, no persistence); Docker Compose wiring for Kafka (`apache/kafka`, KRaft) and the new service; README/roadmap updates.

**Explicitly out of scope** — each is a named follow-up, not an oversight:

| Deferred | Why not now | Lands in |
|---|---|---|
| Flyway migrations | Re-deferred by explicit user decision this session, third time running | its own phase |
| Fraud Service (the saga's third call) | Additive once Fraud exists | Phase 5 |
| Auth / JWT | No security on any service yet | Phase 6 |
| Trace-context propagation over Kafka headers | No OTel Collector / Micrometer Tracing bridge exists yet to propagate | Phase 7 |
| `transfers completed/failed/fraud-rejected` business-metric counters | Only the outbox-backlog gauge is added now (directly named in spec §5); full business metrics land with the Prometheus/Grafana wiring | Phase 7 |
| Automated end-to-end saga test module | Cross-service test infrastructure, not this phase's subsystem | Phase 7 |
| Spring Cloud Contract | Same reason | Phase 7 |

## Design Decisions Worth Knowing Before You Start

**Why a single choke-point service instead of per-call-site outbox writes.** The design doc (written before Phase 3) only named one outbox-write site: the live saga's `COMPLETED` branch. Phase 3 added `COMPENSATED`/`COMPENSATION_FAILED`, reachable only from `CompensationScheduler`, not the saga. Counting `TransferService.fail()`/the completed branch and `CompensationScheduler`'s four terminal-transition branches, there are **six** places a `Transfer` reaches a terminal state across two classes. Asking each one to independently decide "does this status need an outbox event" is exactly the kind of distributed reasoning that caused Phase 2/3's review findings (a missed edge case in one of several similar-looking branches). `TransferSaveService.save()` collapses this to one lookup table, `OutboxEventType.forStatus()`; every call site just calls `transferSaveService.save(transfer)` after any `mark*()` call, terminal or not, and the status alone decides whether a row gets written.

**Why this doesn't reopen "the saga must not be `@Transactional`."** `TransferService.execute()` has no `@Transactional` because a DB transaction can't span the HTTP calls into Account (see `docs/phase-2-transfer-service-saga.md`'s Design Decisions). `TransferSaveService.save()` is `@Transactional`, but it wraps exactly one repository save plus (at most) one insert — no HTTP calls, no network round-trip, nothing that a transaction spanning it would hold open. It is the same shape as `TransferRepository.save()` itself, which is already implicitly transactional via Spring Data. If a reviewer flags this as a regression of the non-transactional-saga rule, point them here: the saga orchestrator (`TransferService.execute()`) is still not `@Transactional`; only the narrow persistence step is.

**Why every `mark*()`-then-save call site converts, not just the "obviously terminal" ones.** It would be tempting to only convert `markCompleted()`/`markFailed()`/`markCompensated()`/`markCompensationFailed()` call sites and leave `markCompensationRequired()`'s save calls (in `TransferService.strand()` and `CompensationScheduler.reconcileDebit()`'s "promoted" branch) untouched, since `COMPENSATION_REQUIRED` never produces an event. Convert those too. `OutboxEventType.forStatus(COMPENSATION_REQUIRED)` returns `null`, so `TransferSaveService.save()` writes no row for them — but routing them through the same method means nobody has to remember which of eight call sites are the exceptions. One save() method every terminal-or-not status write goes through, one place that decides.

**At-least-once delivery is accepted, not a bug.** A crash between `kafkaTemplate.send(...)` succeeding and `OutboxEvent.markPublished()` committing re-publishes that row on the next poll tick. Notification Service only logs, so a duplicate log line is the entire blast radius — document this the way Phase 3 named its own known gaps (e.g. the `ACCOUNT_SERVICE_UNAVAILABLE`-on-debit reconciliation gap) instead of treating it as implicit.

## File Structure

**Transfer Service (modified/new):**

| File | Responsibility |
|---|---|
| `domain/OutboxEventType.java` (new) | Two-value enum + `forStatus(TransferStatus)` lookup — the single terminal/non-terminal decision point |
| `domain/OutboxEvent.java` (new) | Entity: transfer id, event type, JSON payload, created/published timestamps |
| `domain/OutboxEventRepository.java` (new) | `findByPublishedAtIsNullOrderByCreatedAtAsc(Limit)`, `countByPublishedAtIsNull()` |
| `service/TransferSaveService.java` (new) | The choke point: `@Transactional Transfer save(Transfer)` |
| `service/TransferService.java` (modified) | Constructor takes `TransferSaveService`; three `transferRepository.save(transfer)` calls become `transferSaveService.save(transfer)` |
| `service/CompensationScheduler.java` (modified) | Constructor takes `TransferSaveService`; five `transferRepository.save(transfer)` calls become `transferSaveService.save(transfer)` |
| `service/OutboxPublisherProperties.java` (new) | `@ConfigurationProperties("transfer.outbox")`: poll interval, batch size, publish timeout, topic names |
| `service/OutboxPublisher.java` (new) | `SchedulingConfigurer` poller + outbox-backlog gauge |
| `config/KafkaProducerConfig.java` (new) | Explicit `ProducerFactory<String,String>`/`KafkaTemplate<String,String>` beans |
| `pom.xml` (modified) | `spring-kafka`, `spring-kafka-test`, `org.testcontainers:kafka` |
| `application.yml` (modified) | `spring.kafka.*`, `transfer.outbox.*` |

**Notification Service (new module `notification-service`, port 8083, package `com.showcase.notification`):**

| File | Responsibility |
|---|---|
| `pom.xml` | Web + Actuator + spring-kafka, no JPA/Postgres — stateless per spec §2 |
| `NotificationServiceApplication.java` | Boot entry point |
| `NotificationListener.java` | `@KafkaListener` on both topics, logs "notification sent" |
| `application.yml` | Server port 8083, `spring.kafka.consumer.*`, topic names |
| `Dockerfile` | Same two-stage pattern as the other two services |

**Infrastructure (modified):** root `pom.xml` (add module), `account-service/Dockerfile` + `transfer-service/Dockerfile` (add the third module's pom COPY line), `docker-compose.yml`, `.env.example`/`.env` (none needed — Kafka needs no password), `README.md`, `docs/roadmap.md`.

---
### Task 1: Outbox domain model

**Files:**
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEventType.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEvent.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEventRepository.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventTypeTest.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventTest.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventRepositoryTest.java`

**Interfaces:**
- Consumes: `TransferStatus` (existing, unchanged).
- Produces: `OutboxEventType.forStatus(TransferStatus)` returning `TRANSFER_COMPLETED`/`TRANSFER_FAILED`/`null`; `OutboxEvent(UUID transferId, OutboxEventType eventType, String payload)` constructor + `markPublished()`; `OutboxEventRepository` with `findByPublishedAtIsNullOrderByCreatedAtAsc(Limit)` and `countByPublishedAtIsNull()`. Task 2's `TransferSaveService` and Task 3's `OutboxPublisher` use all of these.

- [ ] **Step 1: Write the failing tests**

```java
// transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventTypeTest.java
package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTypeTest {

    @Test
    void completedMapsToTransferCompleted() {
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPLETED)).isEqualTo(OutboxEventType.TRANSFER_COMPLETED);
    }

    @Test
    void failedCompensatedAndCompensationFailedAllMapToTransferFailed() {
        assertThat(OutboxEventType.forStatus(TransferStatus.FAILED)).isEqualTo(OutboxEventType.TRANSFER_FAILED);
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPENSATED)).isEqualTo(OutboxEventType.TRANSFER_FAILED);
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPENSATION_FAILED)).isEqualTo(OutboxEventType.TRANSFER_FAILED);
    }

    @Test
    void nonTerminalStatusesMapToNoEvent() {
        assertThat(OutboxEventType.forStatus(TransferStatus.PENDING)).isNull();
        assertThat(OutboxEventType.forStatus(TransferStatus.COMPENSATION_REQUIRED)).isNull();
    }
}
```

```java
// transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventTest.java
package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void newEventIsUnpublished() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}");

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSFER_COMPLETED);
        assertThat(event.getPayload()).isEqualTo("{}");
    }

    @Test
    void markPublishedSetsTimestamp() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}");

        event.markPublished();

        assertThat(event.getPublishedAt()).isNotNull();
    }
}
```

```java
// transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventRepositoryTest.java
package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class OutboxEventRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void findsOnlyUnpublishedEvents() {
        OutboxEvent unpublished = outboxEventRepository.saveAndFlush(
                new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{\"a\":1}"));
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, "{\"b\":2}");
        published.markPublished();
        outboxEventRepository.saveAndFlush(published);

        List<OutboxEvent> found = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(10));

        assertThat(found).extracting(OutboxEvent::getId).containsExactly(unpublished.getId());
    }

    @Test
    void countsOnlyUnpublishedEvents() {
        outboxEventRepository.saveAndFlush(new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}"));
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, "{}");
        published.markPublished();
        outboxEventRepository.saveAndFlush(published);

        assertThat(outboxEventRepository.countByPublishedAtIsNull()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl transfer-service test -Dtest=OutboxEventTypeTest,OutboxEventTest,OutboxEventRepositoryTest`
Expected: FAIL — compilation error, none of `OutboxEventType`/`OutboxEvent`/`OutboxEventRepository` exist yet.

- [ ] **Step 3: Create the enum, entity, and repository**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEventType.java
package com.showcase.transfer.domain;

/** The two events Notification Service consumes. */
public enum OutboxEventType {
    TRANSFER_COMPLETED,
    TRANSFER_FAILED;

    /**
     * Maps a Transfer's status onto the event TransferSaveService writes, or null for a
     * non-terminal status. FAILED, COMPENSATED, and COMPENSATION_FAILED all resolve to
     * TRANSFER_FAILED -- the payload's own status field is what lets a consumer tell them
     * apart; see docs/phase-4-outbox-kafka-notification.md's Design Decisions.
     */
    public static OutboxEventType forStatus(TransferStatus status) {
        return switch (status) {
            case COMPLETED -> TRANSFER_COMPLETED;
            case FAILED, COMPENSATED, COMPENSATION_FAILED -> TRANSFER_FAILED;
            case PENDING, COMPENSATION_REQUIRED -> null;
        };
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEvent.java
package com.showcase.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// Index on (publishedAt, createdAt): OutboxPublisher's poll query
// (findByPublishedAtIsNullOrderByCreatedAtAsc) runs every poll-interval (5s default) and
// filters on publishedAt IS NULL then sorts by createdAt -- without this index it's a full
// table scan every tick, on a table that only ever grows (nothing prunes published rows;
// see docs/roadmap.md's deferred-items list). countByPublishedAtIsNull() (the backlog
// gauge, scraped on every metrics poll) benefits from the same index. Added during the
// Phase 4 final review -- see docs/roadmap.md.
@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_unpublished", columnList = "publishedAt, createdAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private OutboxEventType eventType;

    // Plain TEXT, not @Lob: Hibernate maps @Lob String on Postgres to the native Large
    // Object (oid) type by default, which requires an active transaction to read. The
    // real OutboxPublisher polls without a surrounding transaction (matching
    // CompensationScheduler's pattern), so an @Lob payload would throw
    // "Large Objects may not be used in auto-commit mode" on every publish attempt --
    // found live during Task 3's own verification (docs/phase-4-outbox-kafka-notification.md's
    // implementation), masked in this task's own tests only because @DataJpaTest is
    // transactional by default.
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    public OutboxEvent(UUID transferId, OutboxEventType eventType, String payload) {
        this.transferId = transferId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEventRepository.java
package com.showcase.transfer.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc(Limit limit);

    long countByPublishedAtIsNull();
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl transfer-service test -Dtest=OutboxEventTypeTest,OutboxEventTest,OutboxEventRepositoryTest`
Expected: PASS, 7 tests. Docker must be running for the Testcontainers Postgres.

- [ ] **Step 5: Commit**

```bash
git add transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEventType.java transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEvent.java transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEventRepository.java transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventTypeTest.java transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventTest.java transfer-service/src/test/java/com/showcase/transfer/domain/OutboxEventRepositoryTest.java
git commit -m "feat(transfer): outbox event domain model"
```

---
### Task 2: `TransferSaveService` and call-site conversion

**Files:**
- Create: `transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java`

**Interfaces:**
- Consumes: `TransferRepository`, `OutboxEventRepository`, `OutboxEventType.forStatus`, `Transfer` getters (from Task 1 and existing code), a Spring-managed `ObjectMapper` (auto-configured, has `JavaTimeModule` registered — required to serialize `Instant settledAt`).
- Produces: `TransferSaveService.save(Transfer)` returning the saved `Transfer`, used by `TransferService` and `CompensationScheduler` in place of `transferRepository.save(transfer)`.

- [ ] **Step 1: Write the failing test**

```java
// transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java
package com.showcase.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferSaveServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private TransferSaveService service;

    @BeforeEach
    void setUp() {
        // A plain ObjectMapper does not know how to serialize java.time.Instant without this
        // module -- Spring's auto-configured bean has it registered already, but a
        // hand-built one in a unit test needs it explicitly, or toPayload() throws.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new TransferSaveService(transferRepository, outboxEventRepository, objectMapper);
    }

    @Test
    void writesAnOutboxRowForACompletedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository).save(any());
    }

    @Test
    void writesNoOutboxRowForATransientStatus() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void writesAnOutboxRowForEachTerminalStatus() {
        Transfer completed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        completed.markCompleted();
        Transfer failed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        failed.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferRepository.save(completed)).thenReturn(completed);
        when(transferRepository.save(failed)).thenReturn(failed);

        service.save(completed);
        service.save(failed);

        verify(outboxEventRepository, org.mockito.Mockito.times(2)).save(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=TransferSaveServiceTest`
Expected: FAIL — compilation error, `TransferSaveService` does not exist.

- [ ] **Step 3: Create `TransferSaveService`**

```java
// transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java
package com.showcase.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The only place a Transfer is persisted after a mark*() status transition. Centralizing
 * it here means no call site (TransferService, CompensationScheduler) has to know which
 * statuses are "outbox-worthy" -- that decision lives in one place,
 * OutboxEventType.forStatus(). The @Transactional here is narrow: one repository save
 * plus, at most, one insert -- no HTTP calls inside it -- so it does not reopen
 * TransferService's "the saga itself must not be @Transactional" rule; see
 * docs/phase-2-transfer-service-saga.md's Design Decisions and this phase's own.
 */
@Service
public class TransferSaveService {

    private final TransferRepository transferRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public TransferSaveService(TransferRepository transferRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  ObjectMapper objectMapper) {
        this.transferRepository = transferRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Transfer save(Transfer transfer) {
        Transfer saved = transferRepository.save(transfer);
        OutboxEventType eventType = OutboxEventType.forStatus(saved.getStatus());
        if (eventType != null) {
            outboxEventRepository.save(new OutboxEvent(saved.getId(), eventType, toPayload(saved)));
        }
        return saved;
    }

    private String toPayload(Transfer transfer) {
        try {
            return objectMapper.writeValueAsString(new TransferEventPayload(
                    transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                    transfer.getAmount(), transfer.getStatus(), transfer.getFailureCode(),
                    transfer.getFailureReason(), transfer.getSettledAt()));
        } catch (JsonProcessingException impossible) {
            // Every field here is a UUID/BigDecimal/enum/String/Instant -- Jackson has no
            // way to fail serializing this record once JavaTimeModule is registered (it is,
            // on the Spring-managed ObjectMapper this class is given). Wrapped rather than
            // declared throws so a save() nobody expects to fail doesn't force a checked
            // exception on every caller for a failure mode that cannot happen.
            throw new IllegalStateException(
                    "Failed to serialize outbox payload for transfer " + transfer.getId(), impossible);
        }
    }

    private record TransferEventPayload(UUID transferId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                                         TransferStatus status, TransferFailureCode failureCode,
                                         String failureReason, Instant settledAt) {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl transfer-service test -Dtest=TransferSaveServiceTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Convert `TransferService`'s call sites**

Add the constructor dependency and replace its three `transferRepository.save(transfer)` calls. `transferRepository` itself stays — `execute()`'s initial `transferRepository.save(new Transfer(...))` (creating the `PENDING` row) is **not** converted, only the terminal-state saves are.

```java
// transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java
// 1. Add the field and constructor parameter:
    private final TransferSaveService transferSaveService;

    public TransferService(TransferRepository transferRepository, AccountClient accountClient,
                            TransferSaveService transferSaveService) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.transferSaveService = transferSaveService;
    }

// 2. In execute()'s happy path, after transfer.markCompleted():
//    replace  return transferRepository.save(transfer);
//    with     return transferSaveService.save(transfer);

// 3. In fail(transfer, code, reason), after transfer.markFailed(...):
//    replace  return transferRepository.save(transfer);
//    with     return transferSaveService.save(transfer);

// 4. In strand(transfer, code, reason), after transfer.markCompensationRequired(...):
//    replace  return transferRepository.save(transfer);
//    with     return transferSaveService.save(transfer);
```

- [ ] **Step 6: Convert `CompensationScheduler`'s call sites**

Same pattern — add the constructor dependency, then replace all five `transferRepository.save(transfer)` calls (`reconcileCredit`'s success branch, `compensateSource`'s two branches, `reconcileDebit`'s two branches — including the "promoted to COMPENSATION_REQUIRED" branch, which will simply write no outbox row).

```java
// transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java
// 1. Add the field and constructor parameter:
    private final TransferSaveService transferSaveService;

    public CompensationScheduler(TransferRepository transferRepository, AccountClient accountClient,
                                  CompensationProperties properties, TransferSaveService transferSaveService) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.properties = properties;
        this.transferSaveService = transferSaveService;
    }

// 2. reconcileCredit(), after transfer.markCompleted():
//    replace  transferRepository.save(transfer);
//    with     transferSaveService.save(transfer);

// 3. compensateSource(), after transfer.markCompensated():
//    replace  transferRepository.save(transfer);
//    with     transferSaveService.save(transfer);

// 4. compensateSource()'s catch(AccountRejectedException), after transfer.markCompensationFailed(...):
//    replace  transferRepository.save(transfer);
//    with     transferSaveService.save(transfer);

// 5. reconcileDebit(), after transfer.markCompensationRequired(...) (the "promoted" branch):
//    replace  transferRepository.save(transfer);
//    with     transferSaveService.save(transfer);

// 6. reconcileDebit()'s catch(AccountRejectedException), after transfer.markFailed(...):
//    replace  transferRepository.save(transfer);
//    with     transferSaveService.save(transfer);
```

`transferRepository` stays a field on both classes — `TransferService` still reads through it (`getTransfer`, `listTransfers`), and `CompensationScheduler` still queries through it (`findByStatus`, `findByStatusAndCreatedAtBefore`). Only the post-`mark*()` **writes** move to `transferSaveService.save(...)`.

- [ ] **Step 7: Update the existing tests that construct these classes**

`TransferServiceTest`/`TransferServiceIT` (if any) and `CompensationSchedulerTest`/`CompensationSchedulerIT` construct these classes directly — search for `new TransferService(` and `new CompensationScheduler(` across `transfer-service/src/test` and add a mocked or real `TransferSaveService` argument to each. Where a test currently asserts on `transferRepository.save(...)` being called with a particular status, keep that assertion (it's still true) but do not assume `transferSaveService` interactions unless the test is specifically about outbox behavior.

- [ ] **Step 8: Run the whole module test suite**

Run: `./mvnw -pl transfer-service test`
Expected: PASS, all tests, including the ones fixed in Step 7.

- [ ] **Step 9: Commit**

```bash
git add transfer-service/src/main/java/com/showcase/transfer/service transfer-service/src/test/java/com/showcase/transfer/service
git commit -m "feat(transfer): TransferSaveService as the single terminal-state persistence choke point"
```

---
### Task 3: Kafka producer + `OutboxPublisher`

**Files:**
- Modify: `transfer-service/pom.xml`
- Modify: `transfer-service/src/main/resources/application.yml`
- Create: `transfer-service/src/main/java/com/showcase/transfer/config/KafkaProducerConfig.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisherProperties.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisher.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherIT.java`

**Interfaces:**
- Consumes: `OutboxEventRepository` (Task 1), `KafkaTemplate<String, String>` (this task's own config).
- Produces: `OutboxPublisher.publishPending()` (package-private, invoked by the schedule and directly by the IT), the `transfer.outbox.backlog` gauge.

- [ ] **Step 1: Add dependencies**

```xml
<!-- transfer-service/pom.xml: add inside <dependencies> -->
<dependency>
  <groupId>org.springframework.kafka</groupId>
  <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.kafka</groupId>
  <artifactId>spring-kafka-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>kafka</artifactId>
  <scope>test</scope>
</dependency>
```

Run `./mvnw -pl transfer-service dependency:tree -Dincludes=org.springframework.kafka:spring-kafka` once and confirm a version resolves (it's managed by the `spring-boot-starter-parent` BOM, so no explicit `<version>` is expected here — if resolution fails, the Boot 3.3.4 BOM does not manage it and a version must be added to the root POM's `<properties>` the same way `resilience4j.version` was).

- [ ] **Step 2: Add Kafka config to `application.yml`**

```yaml
# transfer-service/src/main/resources/application.yml: add at top level
spring:
  # OutboxPublisher and CompensationScheduler's two sweeps are all SchedulingConfigurer
  # tasks, and Spring Boot defaults task scheduling to a single-thread pool when nothing
  # sets this -- meaning all three tasks serialize on one thread ("scheduling-1"). One
  # thread per scheduled task (publishPending, drainCompensationRequired,
  # sweepStalePending) so a slow/blocked tick on one cannot starve the others.
  task:
    scheduling:
      pool:
        size: 3
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      properties:
        # KafkaProducer.send() blocks the CALLING thread synchronously (inside its
        # metadata-wait), before it even returns a Future to call .get() on, for up to
        # max.block.ms when the broker is unreachable -- the kafka-clients default is 60s.
        # OutboxPublisherProperties.publishTimeout only bounds the .get() call on the
        # Future send() eventually returns; it does nothing about this earlier blocking
        # wait. Bounding max.block.ms here makes a down broker fail fast instead of
        # occupying a scheduling thread for up to 500 rows * 60s per poll tick.
        max.block.ms: 5000
        # request.timeout.ms must be lowered too: KafkaProducer validates
        # delivery.timeout.ms >= linger.ms + request.timeout.ms at construction time and
        # THROWS (fails the producer bean's own creation, not just one send) if that doesn't
        # hold -- the kafka-clients default for request.timeout.ms is 30000ms, comfortably
        # larger than a delivery.timeout.ms of 10000ms on its own. 5000ms here (linger.ms
        # defaults to 0) keeps 10000 >= 0 + 5000 valid with room to spare.
        request.timeout.ms: 5000
        # Bounds how long an already-sent-but-unacked record can sit in-flight before the
        # Future returned by send() completes exceptionally.
        delivery.timeout.ms: 10000

transfer:
  outbox:
    poll-interval: 5s
    batch-size: 500
    publish-timeout: 5s
    topics:
      completed: transfer.completed
      failed: transfer.failed
```

(Note: `spring.kafka`/`spring.task` are new top-level keys alongside the existing `spring.application`/`spring.threads`/`spring.datasource`/`spring.jpa` block — merge into the existing `spring:` block, don't duplicate the key. Same for adding `transfer.outbox` alongside the existing `transfer.compensation` block.)

**Update, Phase 4 final review:** the `producer.properties.max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms` and the top-level `spring.task.scheduling.pool.size` above were both added post-merge — the original Task 3 pass shipped without them, leaving a Kafka outage able to block the single shared scheduling thread for up to ~8 hours (500 rows × the kafka-clients default `max.block.ms` of 60s) and stall `CompensationScheduler`'s money-safety sweeps along with it. See `OutboxPublisher.java`'s own comments for how a broker-unreachable failure is distinguished from a row-specific one now that `publishPending()` stops the rest of a batch on the former. `request.timeout.ms` was not in the original fix plan — it was added only after actually running `OutboxPublisherIT` against it: `delivery.timeout.ms: 10000` alone made the producer bean fail to construct at all (`ConfigException: delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms`, since the kafka-clients default `request.timeout.ms` is 30000ms), which is the kind of thing "verify by checking the resulting producer's config" is worth doing for, not just reasoning about in the abstract.

- [ ] **Step 3: Write the explicit Kafka producer config**

Declared explicitly (not left to Spring Boot's autoconfigured `KafkaTemplate<Object, Object>`) so `OutboxPublisher` can depend on the concrete `KafkaTemplate<String, String>` it actually uses.

```java
// transfer-service/src/main/java/com/showcase/transfer/config/KafkaProducerConfig.java
package com.showcase.transfer.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
```

- [ ] **Step 4: Write `OutboxPublisherProperties`**

Same self-defaulting pattern as `CompensationProperties` — Boot's binder silently skips unset values, so defaults live in the compact constructor, not relied on from `application.yml` alone.

```java
// transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisherProperties.java
package com.showcase.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transfer.outbox")
public record OutboxPublisherProperties(Duration pollInterval, Integer batchSize, Duration publishTimeout,
                                         Topics topics) {

    public OutboxPublisherProperties {
        pollInterval = (pollInterval != null) ? pollInterval : Duration.ofSeconds(5);
        batchSize = (batchSize != null) ? batchSize : 500;
        publishTimeout = (publishTimeout != null) ? publishTimeout : Duration.ofSeconds(5);
        topics = (topics != null) ? topics : new Topics("transfer.completed", "transfer.failed");
    }

    public record Topics(String completed, String failed) {
    }
}
```

- [ ] **Step 5: Write `OutboxPublisher`**

```java
// transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisher.java
package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.NetworkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Polls OutboxEventRepository for unpublished rows and publishes each to Kafka, mirroring
 * CompensationScheduler's SchedulingConfigurer + Limit-bounded-batch + per-row-try/catch
 * shape exactly. Single-instance deployment (docker compose, no k8s) means no distributed
 * locking is needed here, same reasoning as CompensationScheduler.
 */
@Component
public class OutboxPublisher implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxPublisherProperties properties;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
                            OutboxPublisherProperties properties, MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        meterRegistry.gauge("transfer.outbox.backlog", outboxEventRepository,
                repository -> (double) repository.countByPublishedAtIsNull());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::publishPending, properties.pollInterval().toMillis());
    }

    // Package-private so OutboxPublisherIT/OutboxPublisherTest can invoke it directly,
    // without going through the scheduler registration machinery -- same reasoning as
    // CompensationScheduler.
    void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(
                Limit.of(properties.batchSize()));
        for (int i = 0; i < pending.size(); i++) {
            OutboxEvent event = pending.get(i);
            try {
                publish(event);
            } catch (BrokerUnavailableException brokerDown) {
                // A broker-connectivity failure is not row-specific: every other row in this
                // tick's batch would fail the exact same way, each burning up to
                // publish-timeout/max.block.ms against a broker that is still down --
                // up to 500 rows * ~5-60s apiece for no benefit. Stop this tick here instead;
                // every row in `pending`, including this one, is still unpublished, so the
                // next poll-interval tick retries the whole backlog once the broker recovers.
                // Nothing is lost, only deferred. See publish()'s own comment for how this is
                // told apart from a row-specific failure.
                log.error("Outbox publish tick aborted: Kafka broker unreachable after {}, "
                                + "deferring the remaining {} row(s) in this batch to the next tick",
                        event.getId(), pending.size() - i, brokerDown.getCause());
                break;
            } catch (RuntimeException unexpected) {
                // One row's failure must not block the rest of this batch. The row is still
                // unpublished, so the next tick retries it -- same reasoning as
                // CompensationScheduler's loops.
                log.error("Outbox event {} failed to publish, will retry next tick", event.getId(), unexpected);
            }
        }
    }

    private void publish(OutboxEvent event) {
        String topic = topicFor(event.getEventType());
        try {
            kafkaTemplate.send(topic, event.getTransferId().toString(), event.getPayload())
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished();
            outboxEventRepository.save(event);
            log.info("Outbox event {} published to {}", event.getId(), topic);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.error("Outbox event {} publish interrupted, will retry next tick", event.getId(), interrupted);
        } catch (org.apache.kafka.common.errors.TimeoutException | java.util.concurrent.TimeoutException brokerTimeout) {
            // Two distinct timeouts, both meaning "this producer cannot currently talk to
            // Kafka", not "this row is bad":
            //  - org.apache.kafka.common.errors.TimeoutException is thrown SYNCHRONOUSLY by
            //    kafkaTemplate.send(...) itself, on the calling (scheduling) thread, before a
            //    Future is even returned to call .get() on -- this is the path taken when the
            //    producer cannot fetch topic metadata from the broker within max.block.ms
            //    (application.yml), i.e. the broker is unreachable.
            //  - java.util.concurrent.TimeoutException is our own .get(publishTimeout) bound
            //    expiring while a Future returned by send() is still pending -- e.g. the
            //    broker accepted the connection but never acked within delivery.timeout.ms.
            // Every other row in this batch would hit the same wall, so this is escalated to
            // BrokerUnavailableException rather than being treated as this row's problem.
            throw new BrokerUnavailableException(brokerTimeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof org.apache.kafka.common.errors.TimeoutException
                    || cause instanceof NetworkException
                    || cause instanceof DisconnectException) {
                // Same broker-unreachable family as above, just surfaced asynchronously
                // through the Future instead of thrown synchronously by send() -- e.g. the
                // in-flight request expired (delivery.timeout.ms) or the connection dropped
                // mid-send. Still not row-specific.
                throw new BrokerUnavailableException(cause);
            }
            // Anything else reaching here is specific to this row/record (e.g. a broker-side
            // rejection of this particular record) rather than broker connectivity -- log and
            // let the caller move on to the next row, same as before.
            log.error("Outbox event {} failed to publish to {}, will retry next tick", event.getId(), topic, failed);
        }
    }

    private String topicFor(OutboxEventType eventType) {
        return switch (eventType) {
            case TRANSFER_COMPLETED -> properties.topics().completed();
            case TRANSFER_FAILED -> properties.topics().failed();
        };
    }

    /**
     * Internal signal that publishPending()'s current batch should stop early because Kafka
     * itself -- not this one row -- is unreachable. Never escapes this class: publish() throws
     * it, publishPending() catches it and breaks the loop. Deliberately a RuntimeException
     * subtype caught BEFORE the existing generic {@code catch (RuntimeException unexpected)},
     * not a checked exception, so publish()'s signature doesn't have to change.
     */
    private static final class BrokerUnavailableException extends RuntimeException {
        BrokerUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
```

**Update, Phase 4 final review:** the shape above (`BrokerUnavailableException`, the index-based loop, the split of `org.apache.kafka.common.errors.TimeoutException`/`NetworkException`/`DisconnectException` from other `ExecutionException` causes) replaced the original Task 3 version, whose `catch (TimeoutException | ExecutionException failed)` only ever caught the two exceptions `.get()` itself can throw — it never caught the *synchronous* `org.apache.kafka.common.errors.TimeoutException` that `kafkaTemplate.send(...)` throws directly when the producer can't reach the broker within `max.block.ms`. That uncaught exception fell through to `publishPending()`'s generic `catch (RuntimeException unexpected)`, which logged it and moved on to the next row — meaning a fully-down broker was retried once per row, all 500 of them, every tick. See `OutboxPublisherTest.java` for the regression tests.

- [ ] **Step 6: Write the failing integration test**

```java
// transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherIT.java
package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OutboxPublisherIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // @ServiceConnection on the Kafka container threw ConnectionDetailsNotFoundException
    // on Spring Boot 3.3.4 -- found live during Task 3's implementation. Wire the bootstrap
    // address manually instead; Spring Boot's own Kafka autoconfiguration takes it from there.
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxPublisherProperties outboxPublisherProperties;

    @Test
    void publishesAnUnpublishedEventAndMarksItPublished() {
        UUID transferId = UUID.randomUUID();
        String payload = "{\"status\":\"COMPLETED\"}";
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                new OutboxEvent(transferId, OutboxEventType.TRANSFER_COMPLETED, payload));

        outboxPublisher.publishPending();

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNotNull();

        // Only asserting publishedAt is non-null does not prove anything about WHERE the
        // message went or what it carried: because the test broker auto-creates topics,
        // topicFor() routing to the wrong topic string would still leave this test green.
        // Attach a real consumer to the topic OutboxPublisherProperties says TRANSFER_COMPLETED
        // routes to, and prove the key/value contract directly -- this is the one thing nothing
        // else in the suite protects.
        String topic = outboxPublisherProperties.topics().completed();
        var consumerProps = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), "outbox-publisher-it", "true");
        // consumerProps() defaults to IntegerDeserializer for the key -- OutboxPublisher's
        // producer key is the transfer id as a String (see topicFor()/publish()), so this
        // must be overridden or the consumer throws deserializing the first record.
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(10));

            assertThat(record.key()).isEqualTo(transferId.toString());
            assertThat(record.value()).isEqualTo(payload);
        }
    }

    @Test
    void leavesAnAlreadyPublishedEventAlone() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, "{}");
        event.markPublished();
        outboxEventRepository.saveAndFlush(event);
        // Reload rather than using the in-memory instance's timestamp: without a surrounding
        // transaction (deliberately -- see OutboxPublisher's own javadoc), this test's second
        // read is a genuinely separate persistence context, so the in-memory Instant.now() and
        // the database's stored microsecond-precision value are not the same object.
        var publishedAtBefore = outboxEventRepository.findById(event.getId()).orElseThrow().getPublishedAt();

        outboxPublisher.publishPending();

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isEqualTo(publishedAtBefore);
    }
}
```

**Update, Phase 4 final review:** the real-consumer assertion in `publishesAnUnpublishedEventAndMarksItPublished` was added post-merge. The original Task 3 version only asserted `getPublishedAt()` was non-null, which the test broker's topic auto-creation meant would stay green even if `topicFor()` routed to the wrong topic string entirely -- nothing previously proved the right topic, key, or payload actually received the message.

**This test intentionally has no class-level `@Transactional`.** `OutboxPublisher` polls without a surrounding transaction in production (matching `CompensationScheduler`'s pattern) — wrapping the test in one would exercise a different code path than the real scheduler and would have hidden the `payload` LOB bug above instead of catching it. If a future edit adds `@Transactional` here to silence a similar-looking error, treat that as a regression, not a fix: find out what production code path it's masking first.

- [ ] **Step 7: Run it to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=OutboxPublisherIT`
Expected: FAIL — compilation error until Steps 3–5 exist; once they do, this is the test that proves the whole chain (poll → send → mark published) actually works against real Postgres and real Kafka.

- [ ] **Step 8: Run it to verify it passes**

Run: `./mvnw -pl transfer-service test -Dtest=OutboxPublisherIT`
Expected: PASS, 2 tests. Docker must be running; the Kafka container takes longer to start than Postgres's, so this test is slower than the module's other ITs — that's expected, not a hang.

- [ ] **Step 9: Run the whole module test suite**

Run: `./mvnw -pl transfer-service test`
Expected: PASS, all tests.

- [ ] **Step 10: Commit**

```bash
git add transfer-service/pom.xml transfer-service/src/main/resources/application.yml transfer-service/src/main/java/com/showcase/transfer/config transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisherProperties.java transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisher.java transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherIT.java
git commit -m "feat(transfer): Kafka producer and OutboxPublisher polling publisher"
```

---
### Task 4: `notification-service` module

**Files:**
- Modify: `pom.xml` (root reactor — add the module)
- Modify: `account-service/Dockerfile`, `transfer-service/Dockerfile` (add the third module's pom COPY line)
- Create: `notification-service/pom.xml`
- Create: `notification-service/src/main/java/com/showcase/notification/NotificationServiceApplication.java`
- Create: `notification-service/src/main/java/com/showcase/notification/NotificationListener.java`
- Create: `notification-service/src/main/resources/application.yml`
- Create: `notification-service/Dockerfile`
- Test: `notification-service/src/test/java/com/showcase/notification/NotificationListenerIT.java`

**Interfaces:**
- Consumes: `transfer.completed`/`transfer.failed` Kafka topics (Task 3's producer targets these).
- Produces: log lines `"Notification sent: transfer completed {payload}"` / `"Notification sent: transfer failed {payload}"`.

- [ ] **Step 1: Register the module and fix the sibling Dockerfiles**

Adding a third module to the reactor breaks the existing two Dockerfiles the same way adding `transfer-service` broke `account-service`'s in Phase 2 — Maven has to parse every module listed in the aggregator POM to build the reactor graph, even with `-pl <module>`.

```xml
<!-- pom.xml: replace the <modules> block -->
<modules>
  <module>account-service</module>
  <module>transfer-service</module>
  <module>notification-service</module>
</modules>
```

```dockerfile
# account-service/Dockerfile and transfer-service/Dockerfile: in the build stage, after the
# existing "COPY transfer-service/pom.xml transfer-service/pom.xml" line, add:
COPY notification-service/pom.xml notification-service/pom.xml
```

- [ ] **Step 2: Create the module POM**

```xml
<!-- notification-service/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.showcase</groupId>
    <artifactId>microservices-showcase</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>notification-service</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>kafka</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/*Test.java</include>
            <include>**/*Tests.java</include>
            <include>**/*IT.java</include>
            <include>**/*ITTest.java</include>
          </includes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

No `postgresql`/`spring-boot-starter-data-jpa` — Notification is stateless per spec §2, no database.

- [ ] **Step 3: Create the application class and configuration**

```java
// notification-service/src/main/java/com/showcase/notification/NotificationServiceApplication.java
package com.showcase.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
```

```yaml
# notification-service/src/main/resources/application.yml
server:
  port: 8083

spring:
  application:
    name: notification-service
  threads:
    virtual:
      enabled: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: notification-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true

notification:
  topics:
    completed: transfer.completed
    failed: transfer.failed
```

- [ ] **Step 4: Write the failing integration test**

```java
// notification-service/src/test/java/com/showcase/notification/NotificationListenerIT.java
package com.showcase.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class NotificationListenerIT {

    // @ServiceConnection on the Kafka container threw ConnectionDetailsNotFoundException
    // on Spring Boot 3.3.4 -- same finding as transfer-service's OutboxPublisherIT. Wire the
    // bootstrap address manually instead.
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Test
    void logsNotificationSentOnTransferCompleted(CapturedOutput output) throws InterruptedException {
        kafkaTemplate.send("transfer.completed", "t-1", "{\"transferId\":\"t-1\",\"status\":\"COMPLETED\"}");

        awaitLogLine(output, "Notification sent: transfer completed");
    }

    @Test
    void logsNotificationSentOnTransferFailed(CapturedOutput output) throws InterruptedException {
        kafkaTemplate.send("transfer.failed", "t-2", "{\"transferId\":\"t-2\",\"status\":\"FAILED\"}");

        awaitLogLine(output, "Notification sent: transfer failed");
    }

    private void awaitLogLine(CapturedOutput output, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!output.getOut().contains(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(output).contains(expected);
    }
}
```

`notification-service` has no producer config of its own — Spring Boot's autoconfigured `KafkaTemplate<Object, Object>` is fine for a test that only needs to send raw strings onto a topic, unlike `transfer-service`'s own production code which needed the concrete `String,String` type.

- [ ] **Step 5: Run it to verify it fails**

Run: `./mvnw -pl notification-service test`
Expected: FAIL — compilation error, `NotificationListener` does not exist (and `com.showcase.notification` package is otherwise empty).

- [ ] **Step 6: Write the listener**

```java
// notification-service/src/main/java/com/showcase/notification/NotificationListener.java
package com.showcase.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Stateless per spec §2: consumes both transfer-outcome topics and logs. No persistence. */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    @KafkaListener(topics = "${notification.topics.completed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferCompleted(String payload) {
        log.info("Notification sent: transfer completed {}", payload);
    }

    @KafkaListener(topics = "${notification.topics.failed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferFailed(String payload) {
        log.info("Notification sent: transfer failed {}", payload);
    }
}
```

- [ ] **Step 7: Run it to verify it passes**

Run: `./mvnw -pl notification-service test`
Expected: PASS, 2 tests. The Kafka container startup makes this slower than a typical unit test module — expected.

- [ ] **Step 8: Write the Dockerfile**

```dockerfile
# notification-service/Dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
RUN chmod +x mvnw && ./mvnw -pl notification-service -am dependency:go-offline -B
COPY notification-service/src notification-service/src
RUN ./mvnw -pl notification-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/notification-service/target/notification-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 9: Verify the full reactor still builds**

Run: `./mvnw test`
Expected: PASS across all three modules — this is the check that the Task 1 root-POM edit and both Dockerfile edits didn't break `account-service`'s or `transfer-service`'s own builds.

- [ ] **Step 10: Commit**

```bash
git add pom.xml account-service/Dockerfile transfer-service/Dockerfile notification-service
git commit -m "feat(notification): add notification-service module consuming transfer outcome events"
```

---
### Task 5: Docker Compose, README, roadmap

**Files:**
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `docs/roadmap.md`

**Interfaces:** none — infrastructure and documentation only.

- [ ] **Step 1: Add the Kafka service**

```yaml
# docker-compose.yml: add a new top-level service
  kafka:
    image: apache/kafka:3.8.0
    container_name: showcase-kafka
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
    ports:
      - "9092:9092"
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092"]
      interval: 10s
      timeout: 10s
      retries: 10
      start_period: 30s
```

**This env-var set is a starting point, not a guarantee** — verify it against a real `docker compose up` in Step 4 below and fix anything that doesn't come up healthy before moving on; KRaft single-node bootstrap configuration for `apache/kafka` has changed across recent image versions and is worth confirming live.

- [ ] **Step 2: Wire `transfer-service` and add `notification-service`**

```yaml
# docker-compose.yml: under services.transfer-service.environment, add:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
# and under services.transfer-service.depends_on, add:
      kafka:
        condition: service_healthy

# docker-compose.yml: add a new top-level service
  notification-service:
    build:
      context: .
      dockerfile: notification-service/Dockerfile
    container_name: showcase-notification-service
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    ports:
      - "8083:8083"
    depends_on:
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8083/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 40s
```

- [ ] **Step 3: Update the README**

Add Notification Service to the architecture/services list (mirroring how Account and Transfer are documented — port 8083, stateless, consumes `transfer.completed`/`transfer.failed`), and add a short "Kafka" section to run instructions: how to tail `docker compose logs notification-service` to see a "notification sent" line after driving a transfer.

- [ ] **Step 4: Verify against real containers**

```bash
docker compose up --build -d
docker compose ps
```
Expected: `showcase-kafka` and `showcase-notification-service` both reach `(healthy)`, alongside the three already-working services. If `kafka` does not become healthy, inspect `docker compose logs kafka` and adjust Step 1's env vars — do not proceed to the rest of this step until it's genuinely healthy, not just "started."

```bash
curl -X POST http://localhost:8082/transfers \
  -H "Content-Type: application/json" \
  -d '{"fromAccountId":"<seed account id>","toAccountId":"<seed account id>","amount":"10.00"}'
```
Expected: `201`, transfer `COMPLETED`. Then:
```bash
docker compose logs notification-service | grep "Notification sent"
```
Expected: one line, `Notification sent: transfer completed {...}` containing the transfer's id and `"status":"COMPLETED"`.

Then force a failure (e.g. an amount exceeding the seed balance) and confirm `docker compose logs notification-service` shows `Notification sent: transfer failed {...}`.

```bash
docker compose down
docker compose up --build -d
```
Expected: Kafka comes up clean on a fresh start too — a stale `CLUSTER_ID` or leftover log directory from a previous run is a known KRaft footgun; if the second `up` fails where the first succeeded, `docker compose down -v` to clear the named volume (Kafka's own data isn't currently declared as a named volume in Step 1 — decide during verification whether it needs one for the demo to survive a plain `docker compose down`, or whether "notification service starts fresh each time" is acceptable for this project's scope).

- [ ] **Step 5: Update the roadmap**

```markdown
<!-- docs/roadmap.md: update Phase 4's row -->
| 4 | [Transactional outbox + Kafka + Notification](phase-4-outbox-kafka-notification.md) | ✅ Done | Outbox table + TransferSaveService choke point in Transfer Service; OutboxPublisher polling publisher to `transfer.completed`/`transfer.failed` (Kafka, KRaft mode via `apache/kafka`); Notification Service (stateless) consuming both and logging |
```

```markdown
<!-- docs/roadmap.md: the Flyway deferred item already exists; update its text to record this phase's re-deferral decision -->
- Flyway vs. `ddl-auto` for schema management — re-deferred again in Phase 4 (the outbox table
  was added to Transfer's existing schema via `ddl-auto: update`, no migration tooling
  introduced). Revisit before Phase 5 or later phases add more schema surface.
```

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml README.md docs/roadmap.md
git commit -m "feat: wire Kafka and notification-service into Docker Compose"
```

---

## Final Review

Before considering this phase done, dispatch a whole-branch review using a more capable model than the per-task implementer/reviewer subagents (per CLAUDE.md's subagent model policy) — Phase 3's equivalent review caught a money-creation bug six per-task reviews had missed. Give it an explicit checklist:

- Every `transferRepository.save(transfer)` call that follows a `mark*()` call in `TransferService` and `CompensationScheduler` has been converted to `transferSaveService.save(transfer)` — none missed. (Grep both files for `transferRepository.save(transfer)`; only the initial `execute()` creation-save should remain.)
- `TransferSaveService.save()` is `@Transactional`; `TransferService.execute()` is still not.
- `OutboxEventType.forStatus()` covers all six `TransferStatus` values (a missing `case` is a compile error thanks to the exhaustive `switch`, but confirm the mapping matches the Design Decisions section, not just that it compiles).
- `OutboxPublisher.publishPending()` isolates each row's failure in its own try/catch, matching `CompensationScheduler`'s loops.
- Both new Dockerfiles (`account-service`, `transfer-service`) got the `notification-service` pom COPY line; the new `notification-service/Dockerfile` copies all three.
- `docker compose up --build` was actually run and verified healthy end-to-end (Step 4 of Task 5), not assumed from the compose file alone.

## Final Review — Minor Findings (Deferred, Not Fixed)

Critical and Important findings from the final review were fixed before closing this phase. These Minor ones were deliberately left as-is:

- `KafkaProducerConfig`'s `@EnableConfigurationProperties(OutboxPublisherProperties.class)` is redundant with `TransferServiceApplication`'s existing `@ConfigurationPropertiesScan` — harmless, but undocumented and mislocated (an outbox concern registered from the Kafka producer config class). Remove once confirmed nothing relies on it. **Resolved post-Phase 9:** removed; `OutboxPublisherProperties` is still bound through the `@ConfigurationPropertiesScan`.
- Kafka's advertised listener (`PLAINTEXT://kafka:9092`) is only resolvable from inside the Compose network — a host-side client (e.g. `kafka-console-consumer` for manual debugging) cannot connect even though port 9092 is published. Add a second `PLAINTEXT_HOST` listener advertised as `localhost:29092` if host-side debugging access is wanted. **Resolved:** `docker-compose.yml` now has a `HOST` listener advertised as `localhost:29092` (published on host port 29092) for host-side clients.
- `transfer.outbox.backlog` (the Micrometer gauge `OutboxPublisher` registers) is unobservable until Prometheus is wired up (Phase 8) — `management.endpoints.web.exposure` currently exposes only `health`. Also currently untested either way.
- `OutboxPublisherIT`/`NotificationListenerIT` run against `confluentinc/cp-kafka:7.7.1` (Testcontainers) while Compose ships `apache/kafka:3.8.0` — both are KRaft-mode Kafka, but tests don't exercise the exact image that actually ships. Consider Testcontainers' `org.testcontainers.kafka.KafkaContainer` against `apache/kafka` instead. **Resolved post-Phase 9:** every Kafka IT (these two plus `BuildInfoIT` and `TracingBridgeIT`) now runs `KafkaContainer` against `apache/kafka:3.8.0`, the image Compose ships; the code blocks above are synced to match.
- No explicit `NewTopic` beans for `transfer.completed`/`transfer.failed` — partition count and replication factor are whatever the broker's auto-create default produces. Fine for a single-broker demo; worth making explicit if partition count ever matters. **Resolved post-Phase 9:** `KafkaTopicConfig` declares both topics (one partition, one replica), and `OutboxPublisherIT` runs its broker with auto-create off to prove it.
- `OutboxEventType.forStatus()` maps `COMPENSATION_FAILED` (debit landed, credit and the reversal both failed — manual review, balance is actually down) onto the same `TRANSFER_FAILED` event as a clean `FAILED`/`COMPENSATED` outcome. Harmless today since Notification only logs and the payload's own `status`/`failureReason` fields still carry the distinction — but if a later phase makes notifications customer-facing, this mapping would tell a customer "your transfer failed" when the real statement is "your money moved and the reversal needs manual review." Revisit the event-type mapping before that happens. **Decided post-Phase 9: not planned** — notifications stay log-only in this demo (see `open-items.md` §4).
- `outbox_events` has no pruning: nothing deletes a row once it's published, so the table grows with total transfer history forever. The final review's index fix (`idx_outbox_unpublished` on `publishedAt, createdAt`) keeps the poll query and the backlog gauge cheap regardless of table size, but disk growth itself is untouched — deliberately out of scope for the review-fix pass. Revisit with a retention policy (e.g. delete published rows older than N days) once Flyway lands and can carry the migration. **Decided post-Phase 9: not planned** — irrelevant at demo scale (see `open-items.md` §4). Pruning would not need Flyway anyway: it deletes rows and changes no schema.
