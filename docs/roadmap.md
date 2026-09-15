# Implementation Roadmap

Tracks the build-out sequence from `docs/microservices-showcase-design.md` §8
("Account → Transfer w/ saga → Fraud → Notification → Gateway/Auth →
observability wiring → Docker Compose integration"), broken into discrete
phases. Each phase gets its own `docs/phase-N-<name>.md` (see "Documentation
conventions" in `CLAUDE.md`) once it's brainstormed and written — this
file is the index, not a substitute for the phase docs themselves.

Scope and boundaries for a not-yet-written phase below are a rough forecast
from the design doc, not a commitment — each phase's actual scope is decided
when it's brainstormed, and may reshape later rows.

| # | Phase | Status | Scope |
|---|------|--------|-------|
| 1 | [Foundation + Account Service](phase-1-foundation-account-service.md) | ✅ Done | Project scaffolding, Account entity/repo with optimistic locking, REST API, Docker Compose + Postgres |
| 2 | [Transfer Service + Synchronous Saga](phase-2-transfer-service-saga.md) | ✅ Done | Transfer entity/ledger, sync saga (pre-validate → debit → credit) over RestClient, RFC 7807 ProblemDetail on both services, Actuator + Compose healthchecks. No resilience, no compensation, no Kafka |
| 3 | [Resilience4j + Compensation + Idempotency](phase-3-resilience-compensation-idempotency.md) | ✅ Done | CircuitBreaker/Retry on Transfer → Account; `AccountOperation` as the idempotency ledger backing required `Idempotency-Key` on debit/credit; `CompensationScheduler` auto-drains `COMPENSATION_REQUIRED` and stale `PENDING` via idempotent replay, resolving to `COMPLETED`, `COMPENSATED`, or the manual-review `COMPENSATION_FAILED` |
| 4 | [Transactional outbox + Kafka + Notification](phase-4-outbox-kafka-notification.md) | ✅ Done | Outbox table + TransferSaveService choke point in Transfer Service; OutboxPublisher polling publisher to `transfer.completed`/`transfer.failed` (Kafka, KRaft mode via `apache/kafka`); Notification Service (stateless) consuming both and logging |
| 5 | Fraud Service | Not started | Stateless rule-based risk check (amount/velocity thresholds), wired into the saga as Transfer's second sync call, with fraud rejection driving compensation |
| 6 | API Gateway + Auth | Not started | Keycloak (pre-configured realm), JWT validation at the Gateway and via Spring Security Resource Server in each service |
| 7 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; Spring Cloud Contract tests; end-to-end saga test module; final `docker compose up` bringing up all services + infra |

## Deferred items (not their own phase — folded into whichever phase touches that area, or reassessed later)

Carried over from Phase 1's final review, not yet resolved:
- Flyway vs. `ddl-auto` for schema management — re-deferred again in Phase 4 (the outbox table
  was added to Transfer's existing schema via `ddl-auto: update`, no migration tooling
  introduced). Revisit before Phase 5 or later phases add more schema surface.

Carried over from Phase 4's final review (Critical and Important findings were fixed before
closing the phase; these Minor ones were not, deliberately):
- `KafkaProducerConfig`'s `@EnableConfigurationProperties(OutboxPublisherProperties.class)` is
  redundant with `TransferServiceApplication`'s existing `@ConfigurationPropertiesScan` — harmless,
  but undocumented and mislocated (an outbox concern registered from the Kafka producer config
  class). Remove once confirmed nothing relies on it.
- Kafka's advertised listener (`PLAINTEXT://kafka:9092`) is only resolvable from inside the
  Compose network — a host-side client (e.g. `kafka-console-consumer` for manual debugging)
  cannot connect even though port 9092 is published. Add a second `PLAINTEXT_HOST` listener
  advertised as `localhost:29092` if host-side debugging access is wanted.
- `transfer.outbox.backlog` (the Micrometer gauge `OutboxPublisher` registers) is unobservable
  until Phase 7 wires Prometheus — `management.endpoints.web.exposure` currently exposes only
  `health`. Also currently untested either way.
- `OutboxPublisherIT`/`NotificationListenerIT` run against `confluentinc/cp-kafka:7.7.1`
  (Testcontainers) while Compose ships `apache/kafka:3.8.0` — both are KRaft-mode Kafka, but
  tests don't exercise the exact image that actually ships. Consider Testcontainers'
  `org.testcontainers.kafka.KafkaContainer` against `apache/kafka` instead.
- No explicit `NewTopic` beans for `transfer.completed`/`transfer.failed` — partition count and
  replication factor are whatever the broker's auto-create default produces. Fine for a
  single-broker demo; worth making explicit if partition count ever matters.
- `OutboxEventType.forStatus()` maps `COMPENSATION_FAILED` (debit landed, credit and the
  reversal both failed — manual review, balance is actually down) onto the same
  `TRANSFER_FAILED` event as a clean `FAILED`/`COMPENSATED` outcome. Harmless today since
  Notification only logs and the payload's own `status`/`failureReason` fields still carry the
  distinction — but if a later phase makes notifications customer-facing, this mapping would
  tell a customer "your transfer failed" when the real statement is "your money moved and the
  reversal needs manual review." Revisit the event-type mapping before that happens.
- `docs/microservices-showcase-design.md` §4 still names the pre-Phase-3 `COMPENSATED`/
  `COMPENSATION_FAILED`-unaware `TransferCompleted`/`TransferFailed` event pair informally and
  §8 still says topic names are undecided — Phase 4 settled both (`transfer.completed`/
  `transfer.failed` topics, the `TransferEventPayload` shape with a `status` field
  distinguishing sub-cases), but the design doc's own prose wasn't touched to reflect it beyond
  what this fix wave updated. Give it a full pass alongside Phase 5's design doc updates.
- `outbox_events` has no pruning: nothing deletes a row once it's published, so the table grows
  with total transfer history forever. The final review's index fix (`idx_outbox_unpublished`
  on `publishedAt, createdAt`) keeps the poll query and the backlog gauge cheap regardless of
  table size, but disk growth itself is untouched — deliberately out of scope for the review-fix
  pass. Revisit with a retention policy (e.g. delete published rows older than N days) once
  Flyway lands and can carry the migration.
