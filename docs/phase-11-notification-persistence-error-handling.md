# Notification Persistence + Kafka Consumer Error Handling (Phase 11)

**Goal:** Notification Service keeps a durable record of every transfer outcome it notifies, one
row per transfer, in its own Postgres database, instead of only logging it. Having a real side
effect gives the consumer real failure modes, and this phase handles each one deliberately rather
than relying on Spring Kafka's defaults. A transient failure (the database is unreachable) is
retried in place until it succeeds, and nothing is lost or reordered. A permanent failure (a
payload that cannot be read, an event that cannot be a real outcome, a second outcome that
contradicts the first) is moved to a dead-letter topic on the first attempt, so the partition
keeps flowing. An identical redelivery, which Transfer's at-least-once outbox produces by design,
is acknowledged as a success and not stored twice.

**Architecture:** A `notification` database (a third database in the existing Postgres
container, same init-script pattern as `account`/`transfer`) holds one `notifications` table.
The consumer reads JSON into a `TransferEvent` record through `ErrorHandlingDeserializer`, and
`NotificationService` stores each event idempotently. `KafkaErrorHandlingConfig` supplies one
`DefaultErrorHandler` with two outcomes: blocking retry with capped exponential backoff and no
attempt limit for an allowlist of database-unavailable exceptions, and a
`DeadLetterPublishingRecoverer` to `<topic>-dlt` for everything else. `DeadLetterListener` reads
both DLTs as raw bytes, logs each record at ERROR, and counts it.

**Tech Stack:** New in `notification-service`: `spring-boot-starter-data-jpa`, `postgresql`,
Lombok, plus Testcontainers `postgresql` and Awaitility for tests. All are versions Boot's BOM
already manages or the root `pom.xml` already pins. No change to Transfer Service or to the
topics' producer side.

**Spec:** This document, brainstormed with the user on 2026-09-24. It supersedes the "stateless"
Notification in `docs/phase-4-outbox-kafka-notification.md` and in §2 of the design doc (now
updated).

## Global Constraints

- Java 21; Spring Boot 3.5.16 (Spring Kafka 3.3.x). Use `C:\dev\openjdk-21.0.2` as `JAVA_HOME`. (CLAUDE.md)
- Built as one "one shot" branch and PR (`feature/phase-11-notification-persistence-error-handling`),
  on the user's explicit instruction for this phase, not one PR per task.
- Do not change the producer. Transfer's payload, topic names, keys (the `transferId`), and
  single-partition topics (`KafkaTopicConfig`) are inputs to this phase, not things it adjusts.
- Schema evolves through `ddl-auto: update`, as in every other service. There is no
  Flyway/Liquibase (`open-items.md` §1).

## Behaviour

Every outcome a record can have, and where it is decided:

| Situation | Decided in | Handling |
|---|---|---|
| New transfer outcome | `NotificationService` | Row inserted, offset committed. `notification_events_total{outcome="stored"}` |
| Same `transferId`, same `status` (outbox redelivery) | `NotificationService` | Nothing written, offset committed, INFO "Duplicate delivery skipped". `notification_events_total{outcome="duplicate"}` |
| Same `transferId`, different `status` | `NotificationService` throws `ConflictingEventException` | Not retryable. Dead-lettered on attempt 1; the stored row is kept |
| Payload is not JSON, or `status` is a value Transfer never publishes (e.g. `PENDING`) | `ErrorHandlingDeserializer`, inside `poll()` | `DeserializationException`, not retryable. Dead-lettered on attempt 1 with the **original bytes** |
| JSON without `transferId`/`status`, or a null value (tombstone) | `NotificationService` throws `InvalidEventException` | Not retryable. Dead-lettered on attempt 1 |
| Postgres unreachable (no connection for a new transaction, connection lost mid-statement) | error handler's retryable allowlist | Retried in place: 1s, 2s, 4s … capped at 30s, **no attempt limit**. The partition waits; nothing behind the record is processed out of order. Drains when Postgres returns. `notification_delivery_failures_total{exception=…}` per failed attempt |
| Any other exception (a bug) | error handler default | Not retryable. Dead-lettered on attempt 1 |

Each dead-lettered record then reaches `DeadLetterListener`: ERROR log (original
topic/partition/offset, reason, exception message, payload as text) and
`notification_dead_lettered_total{topic, reason}`.

## Design Decisions Worth Knowing Before You Start

**Why retryability is an allowlist.** Spring Kafka's `DefaultErrorHandler` retries every exception
except a short built-in list of fatal ones (deserialization, conversion, `ClassCastException`,
…), and by default only 9 times. That default is safe *because* the attempts are bounded. This
phase makes transient retries unbounded, since a database outage must never turn a good event
into a dead-lettered one. With unbounded retries, the default classification would let any
unanticipated exception, such as an NPE from a bug, block its partition forever.
`KafkaErrorHandlingConfig` therefore calls `defaultFalse()` and adds back only the
database-unavailable shapes: `CannotCreateTransactionException`,
`DataAccessResourceFailureException`, `TransientDataAccessException`,
`RecoverableDataAccessException`. The classifier checks the whole cause chain, so the
`ListenerExecutionFailedException` wrapper around a listener's exception does not hide them. The
price of the allowlist is that an unexpected but transient exception type gets dead-lettered
rather than retried. That is recoverable (replay the DLT after adding the type), whereas a
stalled partition blocks every event behind it.

**Why blocking retry and not retry topics (`@RetryableTopic`) for a database outage.** Retry
topics move a failing record to `<topic>-retry-N` and let the main topic carry on. That suits a
failure specific to one record, such as a downstream call rejecting one payload, while others
succeed. When the database is down, every record fails the same way. Moving each one to a retry
topic would only reorder a transfer's events and fill the retry topics, without letting anything
else succeed. Blocking in place keeps order and makes a Postgres outage visible as consumer lag,
which is what it is.

**Why the backoff is capped at 30s.** The default `DefaultBackOffHandler` waits out each backoff
by sleeping on the consumer thread, so no `poll()` happens during the wait. As long as each
backoff plus the failed attempt stays well under `max.poll.interval.ms` (5 min by default), the
broker does not consider the consumer dead and does not rebalance. 30s leaves wide margin.
`spring.datasource.hikari.connection-timeout: 2000` and pgjdbc `socketTimeout: 10` bound the
attempt itself. Without them, Hikari's 30s default would make every attempt hang for 30s, and
pgjdbc's infinite socket timeout could hang a statement that was in flight when the database went
away. `ConsumerErrorHandlingIT`'s outage test (Postgres `docker pause`d) observed an attempt
roughly every 5s, with the connection-level failure rather than the backoff setting the pace.

**Why `ErrorHandlingDeserializer`.** Deserialization runs inside `KafkaConsumer.poll()`, before any
listener or error handler. A bare `JsonDeserializer` that throws there fails the same record on
every poll, forever: the classic poison pill. `ErrorHandlingDeserializer` catches the failure and
hands the container a `DeserializationException` that carries the original bytes. The error
handler then treats it like any other non-retryable failure, and the recoverer publishes those
original bytes to the DLT unchanged.

**Why `status` is an enum and an unknown one is a deserialization failure.** `TransferEvent.status`
is Notification's own `TransferStatus` with only the four terminal values Transfer publishes
(`OutboxEventType.forStatus`). A `PENDING` or misspelt status therefore fails in Jackson and is
dead-lettered with its raw bytes, rather than being stored as a string nobody validated.
`failureCode` stays a `String` on purpose. A new failure code added in Transfer describes a
perfectly real outcome, and must not make that event unreadable here.

**Why a duplicate is a success, and only a *conflicting* duplicate is an error.**
`OutboxPublisher` sends to Kafka and only then marks the outbox row published. A crash between
the two re-sends the same event on the next tick. That is the documented at-least-once behaviour
of the outbox (README, "Kafka and Notification Service"), so an identical second event has to be
acknowledged, not dead-lettered. A second event with a *different* status is not a redelivery. A
transfer's published status is terminal (`COMPLETED`, `FAILED`, `COMPENSATED`,
`COMPENSATION_FAILED` never change), so a disagreement means a producer defect. It is
dead-lettered and the first row is kept as it was.

**Why `transferId` is a unique column and not the `@Id`.** With an assigned `@Id`, Spring Data's
`save()` on an existing key is a `merge`, which silently overwrites the stored row. That would
turn the conflicting-duplicate case into an overwrite nobody sees. `Notification` has a
generated `UUID id` and a `unique` `transferId`. `NotificationService` looks up by `transferId`
first, and the constraint is only a backstop. The lookup-then-insert is not racy: the record key
is the `transferId`, so every event for one transfer arrives on one partition and is processed by
one consumer thread at a time.

**Why the DLTs are `<topic>-dlt`.** Spring Kafka 3.3's `DeadLetterPublishingRecoverer` defaults
to a `-dlt` suffix (older versions and older docs use `.DLT`). The phase uses the default rather
than a custom destination resolver. `KafkaErrorHandlingConfig` declares
`transfer.completed-dlt` and `transfer.failed-dlt` with one partition each. The recoverer writes
to the same partition number as the source record, so each DLT needs at least as many partitions
as its source topic, and Transfer's `KafkaTopicConfig` creates those with one.

**Why the dead-letter producer is not a bean.** It needs a value serializer that writes both raw
`byte[]` (a deserialization failure, republished verbatim) and a `TransferEvent` (every other
failure), so it uses a `DelegatingByTypeSerializer`. Declaring our own `KafkaTemplate` bean
would make Boot back off its auto-configured one. `KafkaErrorHandlingConfig` builds the
template privately, from the same `spring.kafka.producer` properties.

**Why `DeadLetterListener` reads raw bytes in its own consumer group.** A DLT holds exactly the
records that could not be read or processed, so the listener that observes it must not be able
to fail on one. It overrides the application-wide JSON deserializer with
`ByteArrayDeserializer`, and its group (`notification-service-dlt`) tracks DLT offsets separately
from the main consumer.

## Data Model

`notifications` (Notification Service's own `notification` database):

| Column | Notes |
|---|---|
| `id` | `UUID`, generated primary key |
| `transfer_id` | `UUID`, `NOT NULL`, **unique** |
| `status` | `COMPLETED` / `FAILED` / `COMPENSATED` / `COMPENSATION_FAILED` |
| `from_account_id`, `to_account_id`, `amount`, `failure_code`, `failure_reason`, `settled_at` | Copied from the event. `failure_reason` is 512 wide, matching Transfer's column |
| `kafka_topic`, `kafka_partition`, `kafka_offset` | Where the event was read from, to trace a row back to its record |
| `received_at` | When Notification stored it |

## Files

- `notification-service/pom.xml`: the dependencies above.
- `notification-service/src/main/resources/application.yml`: datasource (`DB_*` env, same shape as
  Account/Transfer) with the Hikari/pgjdbc timeouts, `ddl-auto: update`,
  `ErrorHandlingDeserializer` → `JsonDeserializer` consumer config, `notification.retry.*`.
- `event/TransferEvent`, `event/TransferStatus`: the consumed payload.
- `domain/Notification`, `domain/NotificationRepository`.
- `service/NotificationService`, `NotificationOutcome`, `InvalidEventException`,
  `ConflictingEventException`.
- `NotificationListener`: delegates to the service, counts outcomes, keeps the
  `Notification sent: transfer completed/failed …` log line.
- `config/KafkaErrorHandlingConfig`, `config/NotificationRetryProperties`: DLT topics, error
  handler, retry listener, dead-letter template.
- `DeadLetterListener`.
- `docker/postgres/init-db.sh`, `docker-compose.yml`, `.env.example`: `notification` database and
  `notification_service` user; Notification now depends on a healthy Postgres.

## Testing

All ITs run Testcontainers Kafka **and** Postgres per class (a shared Kafka across cached Spring
contexts would let a stale context's consumer, in the same group, take the records).

- `NotificationPersistenceIT`: a full Transfer-shaped payload is stored field for field,
  including the Kafka coordinates. An identical event sent twice leaves one row, counts one
  `duplicate`, and dead-letters nothing.
- `ConsumerErrorHandlingIT` (backoff shortened to 200ms/1s via properties), one test per
  Behaviour row:
  - `"this is not json"`: in `transfer.completed-dlt` byte for byte, with
    `DLT_EXCEPTION_FQCN = DeserializationException`, and the valid record sent right behind it
    on the same partition **is** stored, so there is no poison-pill loop.
  - `status: PENDING`: dead-lettered as a `DeserializationException`, not stored.
  - no `transferId`: dead-lettered with cause `InvalidEventException` after exactly one failed
    delivery.
  - `COMPLETED` then `FAILED` for one transfer: the second is dead-lettered with cause
    `ConflictingEventException`, and the row stays `COMPLETED`.
  - Postgres `docker pause`d: at least 3 failed deliveries, nothing dead-lettered. After unpause,
    the row is stored and still nothing is dead-lettered.
- `NotificationListenerIT`, `TracingBridgeIT`, `BuildInfoIT`: unchanged behaviour, now with a
  Postgres container and valid (UUID) payloads.

## Running It Locally

The `notification` database is created by the Postgres init script, which only runs on an empty
data directory. On an existing stack:

    docker compose down -v
    docker compose up -d --build

(`.env` needs `NOTIFICATION_DB_PASSWORD`; copy it from `.env.example`.) To keep an existing
volume's data instead, create the database by hand with the statements in
`docker/postgres/init-db.sh`'s `notification` blocks (`docker exec -i showcase-postgres psql -U postgres`).

From Git Bash, prefix the `docker exec … /opt/kafka/bin/…` commands below with
`MSYS_NO_PATHCONV=1`, or Git Bash rewrites the container path into a Windows one.

**Stored notifications:**

    docker exec -it showcase-postgres psql -U postgres -d notification -c "select transfer_id, status, amount, kafka_offset from notifications"

**A poison pill.** Produce a line that is not JSON:

    docker exec -it showcase-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic transfer.completed
    > oops

`docker compose logs notification-service` shows one `Delivery attempt 1 … failed:
JsonParseException`, then `Dead-lettered …` and the `DeadLetterListener` ERROR line. The parked
record, with Spring's exception headers:

    docker exec -it showcase-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic transfer.completed-dlt --from-beginning --property print.headers=true

**A database outage.** Stop Postgres (this also takes Account and Transfer down, so produce the
event by hand):

    docker stop showcase-postgres
    docker exec -it showcase-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic transfer.completed
    > {"transferId":"6f1c1f5e-8a44-4b8e-9d7c-0d2f3c1b9a10","status":"COMPLETED","amount":1.00}

The log shows `Delivery attempt 1, 2, 3 …` with the gaps growing towards 30s, and nothing
dead-lettered. The failure's most specific cause is `java.net.UnknownHostException: postgres`
(a stopped container leaves Docker's DNS), wrapped in `CannotCreateTransactionException`, which
is on the allowlist. `docker start showcase-postgres` and the next attempt stores the row. This
was verified live on the Compose stack while building the phase: six attempts over ~40s, then
stored, with `notification_dead_lettered_total` unchanged.

**Metrics:**

    curl -s localhost:8083/actuator/prometheus | grep notification_

## Scope Boundary

**In scope:** the `notification` database and `notifications` table; JSON consumption through
`ErrorHandlingDeserializer`; idempotent storage with conflict detection; the classified
`DefaultErrorHandler` (unbounded capped backoff for database unavailability, DLT for everything
else); the DLT observer and the three metrics; compose/init-script wiring; the design doc,
README, roadmap and open-items updates.

**Explicitly out of scope** (listed in `open-items.md`):

- **Replaying DLT records.** They stay parked; re-publishing to the source topic after a fix is a
  manual Kafka CLI step today. A replay tool or admin endpoint would need auth scope Notification
  does not have (it has no HTTP API).
- **Retry topics / `@RetryableTopic`,** and stopping or pausing the container after N failures
  (`CommonContainerStoppingErrorHandler`, a health-driven pause). Neither fits the one transient
  failure this service has (see Design Decisions); both are the natural next step if Notification
  gains a per-record downstream call, such as a real email/SMS provider.
- **A Grafana panel for the new metrics.** They are scraped by Prometheus already; no dashboard
  shows them.
- **Readiness reflecting the database.** Notification's health endpoint reports the DB (Boot's
  `db` indicator is part of `/actuator/health`), but readiness/liveness groups are unchanged.
- **A `schemaVersion` on the payload.** Still not planned: Notification now parses the payload,
  but producer and consumer ship together from one repo (`open-items.md` §2).
