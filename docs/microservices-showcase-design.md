# Banking Microservices Showcase — Design

**Status:** Implemented through Phase 9 — see `docs/roadmap.md`. This document describes the system as built; where it and a phase doc disagree, fix this one.
**Purpose:** Demonstrating current Java microservices practice: synchronous + asynchronous inter-service communication, relational persistence with JPA, observability, resilience, and containerized local deployment.

## 1. Overview

A small banking/ledger system: users hold accounts with balances, and money moves between accounts via a **transfer** operation. The transfer flow is the centerpiece — it's a genuine distributed transaction across services, orchestrated as a saga with compensation, giving a concrete, demoable story for both synchronous resilience patterns and asynchronous eventing, rather than either in isolation.

Non-goals: this is not a production banking system. No real payment rails, no double-entry general ledger accounting, no multi-currency, no k8s/service mesh, no centralized log aggregation. Scope is deliberately bounded to what's needed to demonstrate the target patterns credibly.

## 2. Services

| Service | Responsibility | State |
|---|---|---|
| **API Gateway** | Single entry point; routes requests to services; validates JWT | stateless |
| **Auth** | Issues/validates JWT via OAuth2/OIDC | Keycloak (containerized, pre-configured realm) |
| **Account** | Owns accounts & balances; debit/credit with optimistic locking | PostgreSQL (own database) |
| **Transfer** | Orchestrates the transfer saga; owns transfer/ledger history + transactional outbox | PostgreSQL (own database) |
| **Fraud** | Screens each account in a transfer against a configured blocklist (no amount/velocity rules) | stateless (no database) |
| **Notification** | Consumes transfer-outcome events; logs a "notification sent" | stateless |
| **Bank UI** | Static browser single-page app for customer self-service (signup/onboarding, balance, transfers, history, quick-transfer); calls the Gateway directly from the browser | stateless (no build tooling — plain HTML/CSS/JS served by nginx) |

Each stateful service owns its data exclusively — no service queries another's database directly (database-per-service).

## 3. Tech Stack

- **Language/runtime:** Java 21, Spring Boot 3.x
- **Web:** Spring MVC (blocking, not WebFlux) with **virtual threads enabled** (`spring.threads.virtual.enabled=true`) — gets Java 21's concurrency model with plain sequential blocking code, no reactive programming model. Rationale: Transfer Service's saga makes a sequential chain of blocking downstream calls per request; virtual threads let that scale to many concurrent in-flight transfers without exhausting a platform-thread pool, and without rewriting the orchestration as reactive/async code. Out of scope: explicit thread-pinning verification/diagnostics tooling — the flag is enabled and left at that.
- **Persistence:** Spring Data JPA + PostgreSQL, one logical database per stateful service (Account, Transfer), provisioned as separate databases inside a single Postgres container via init script.
- **Messaging:** Apache Kafka, **KRaft mode** (no Zookeeper).
- **Auth:** Keycloak (OAuth2/OIDC), JWT validated at the Gateway and by each resource service via Spring Security Resource Server.
- **Resilience:** Resilience4j — CircuitBreaker + Retry on synchronous inter-service calls (Transfer → Account, Transfer → Fraud). Call timeouts come from the `RestClient`'s connect/read timeouts, not a Resilience4j TimeLimiter.
- **Error contract:** every service returns RFC 7807 `application/problem+json` on error,
  carrying a stable machine-readable `code` property alongside the standard `type`,
  `title`, `status` and `detail` fields. Consumers branch on `code`, never on the prose in
  `detail`, and never on the `type` URI (which is an identifier, not a dereferenceable
  URL). Un-enumerated 4xx statuses carry `REQUEST_REJECTED`, un-enumerated 5xx carry
  `INTERNAL_ERROR`, so the property is never absent. The handler and its codes are
  duplicated per service rather than shared through a common module: a shared DTO jar
  turns every contract change into a lockstep redeploy of every service.
- **Observability:** Micrometer Tracing bridged to OpenTelemetry → OTel Collector → Grafana Tempo; Micrometer + Prometheus (`/actuator/prometheus`) scraped by Prometheus, visualized in Grafana; structured JSON logs with `traceId`/`spanId` auto-injected via MDC. No centralized log aggregation (ELK/Loki) — out of scope.
- **Deployment (local):** Docker Compose — single `docker compose up` brings up all services, Postgres, Kafka, Keycloak, and the observability stack.

## 4. Core Flow — The Transfer Saga

`POST /transfers {fromAccountId, toAccountId, amount}` via the Gateway (JWT validated) into Transfer Service, with a required `Idempotency-Key` header.

**Caller idempotency:** the key is stored on the `Transfer`, unique per initiator (`(initiator_id, idempotency_key)`). A repeat of a key the caller already used starts no new saga. It returns the earlier transfer's outcome (`201` if it completed, the same problem if it failed), `409 TRANSFER_IN_PROGRESS` with the `transferId` while that transfer is still `PENDING`, or `409 IDEMPOTENCY_KEY_CONFLICT` if the key was used for a different from/to/amount. Two concurrent requests with one key are settled by the unique constraint: the loser becomes a replay of the winner. The Bank UI keeps one key per intended transfer, so a double-click or a retry after a lost response cannot move the money twice.

**Happy path:**
1. Transfer Service creates a `Transfer` record, status `PENDING`, in its own DB, then pre-validates that both accounts exist.
2. **Sync call** → Fraud Service: screen the source account (before the debit — see `docs/phase-5-fraud-service.md`'s Design Decisions for why the check runs twice). A block fails the transfer clean, no money moved.
3. **Sync call** → Account Service: debit the source account (optimistic locking on balance; rejects on insufficient funds, and rejects unless the relayed JWT's subject owns the account), carrying an `Idempotency-Key` of `<transferId>:debit`.
4. **Sync call** → Fraud Service: screen the destination account (before the credit). A block strands the transfer for compensation — the deliberate trigger for the compensation path below.
5. **Sync call** → Account Service: credit the destination account (`Idempotency-Key` `<transferId>:credit`).
6. Transfer Service marks the `Transfer` `COMPLETED` and writes an outbox row **in the same local DB transaction** (transactional outbox — guarantees the event and the DB state are consistent).
7. A scheduled **outbox publisher** (polling, not Debezium — keeps deployment footprint manageable) reads unpublished outbox rows, publishes `TransferCompleted`/`TransferFailed` to Kafka, marks them published.
8. Notification Service consumes the Kafka event, logs a "notification sent."

Every downstream call (steps 2–5) is wrapped in Resilience4j CircuitBreaker + Retry — retry only on transient errors, never on business rejections.

> **Settled in Phase 4** (see `docs/phase-4-outbox-kafka-notification.md`): two Kafka topics,
> `transfer.completed` and `transfer.failed` — one per event type, not one combined topic.
> `TransferCompleted`/`TransferFailed` above are the two outbox event *types*, not the only two
> `Transfer` statuses that reach them: `FAILED`, and the two compensation-outcome statuses Phase
> 3 added (`COMPENSATED`, `COMPENSATION_FAILED`), all publish to `transfer.failed`, distinguished
> by a `status` field in the JSON payload (which also carries `transferId`, `fromAccountId`,
> `toAccountId`, `amount`, `failureCode`, `failureReason`, `settledAt`). Plain JSON over the
> Kafka topics — no Avro, no schema registry, consistent with "keeps deployment footprint
> manageable."
> Transfer, as the producer, declares both topics (`KafkaTopicConfig`: one partition, one replica)
> and creates them at startup, so they do not depend on the broker auto-creating them.

**Failure & compensation paths:**

The governing rule: a call that fails with `ACCOUNT_SERVICE_UNAVAILABLE` (a timeout or a 5xx, after retries) has an **unknown** outcome, not a failed one — a read timeout cannot be told apart from a request that never arrived. Nothing is ever credited back on a guess; the compensator reconciles by replaying the original idempotency key against Account, whose `AccountOperation` ledger returns the original result if the operation already committed. See `docs/phase-2-transfer-service-saga.md` (deferral table) and `docs/phase-3-resilience-compensation-idempotency.md` (Design Decisions).

- **Rejected before or at the debit** (missing account, caller not the owner, source blocked by Fraud, insufficient funds, Fraud unreachable on the source check): transfer is marked `FAILED`. Nothing moved, nothing to compensate.
- **Destination blocked, or the credit fails, after a confirmed debit:** the transfer is marked `COMPENSATION_REQUIRED`. `CompensationScheduler` (a periodic sweep) re-screens the destination and replays the credit with its original key. If the credit lands, the transfer is `COMPLETED`. If the destination is still blocked or Account definitively rejects the credit, the scheduler credits the source back and marks the transfer `COMPENSATED`. An unreachable service leaves the row for the next sweep.
- **Transfer left `PENDING`** (e.g. the process died mid-saga): a stale-`PENDING` sweep replays the debit key to learn whether the debit landed — `FAILED` if it did not, promoted to `COMPENSATION_REQUIRED` (then resolved as above) if it did. If the source has been blocklisted by then, the row stays `PENDING` and is retried every sweep until the block is lifted: replaying the debit key would perform the debit if it never landed, and marking it `FAILED` would guess that it did not. Settling such a row without lifting the block would need a read-only (or void-if-absent) operation lookup on Account, which is deliberately not planned (`open-items.md` §4).
- **The credit-back itself is rejected:** the transfer is marked `COMPENSATION_FAILED` — the manual-review terminal state. It is the one outcome the system deliberately does not resolve on its own.
- **Account/Fraud unavailable after retries exhausted:** the circuit breaker opens and Transfer Service fails fast (`503`, no hang). On the debit leg the outcome is unknown, so the transfer is **not settled**: it stays `PENDING`, and the caller gets `503 ACCOUNT_SERVICE_UNAVAILABLE` with the `transferId` and `transferStatus: PENDING`. The stale-`PENDING` sweep then replays the debit key and resolves it as above, so the transfer can still complete. Recording it `FAILED` would be terminal, and wrong for good whenever the debit had committed.

Every terminal state emits a `TransferCompleted` or `TransferFailed` event through the outbox.

## 5. Observability

- **Tracing:** a single transfer request produces one continuous trace spanning Gateway → Transfer → Fraud/Account (in saga order) → (async hop via Kafka headers) → Notification. Micrometer auto-propagates W3C trace context across both REST calls and Kafka messages, so the trace crosses the sync/async boundary — the concrete proof of the "both sync and async" design goal.
- **Metrics:** standard JVM/HTTP metrics plus business metrics: transfers completed/failed/fraud-rejected counters, outbox-backlog gauge. Resilience4j's Micrometer integration exposes circuit-breaker state transitions as metrics for free — visible on a Grafana panel during a fault-injection test.
- **Logging:** structured JSON, `traceId`/`spanId` auto-injected via MDC for correlation to traces/metrics. No centralized aggregation; `docker compose logs` is sufficient for the demo.
- **Health:** Actuator health/readiness endpoints per service, wired into Docker Compose healthchecks.
- **Versioning:** each service's build version is its pom `<version>` and is exposed four ways: `GET /actuator/info` (unauthenticated: `build.version` plus the git commit), a constant-1 `application_info{version,commit}` gauge (the Prometheus "info metric" convention) driving a provisioned Grafana Service Versions dashboard, the `service.version` OpenTelemetry resource attribute on every span, and an OCI `revision` label on each image. This is build versioning only; API-contract versioning (OpenAPI `info.version`, currently `v1`) is separate and unchanged.

## 6. Testing Strategy

- **Unit tests** (JUnit 5 + Mockito): business logic in isolation — saga step sequencing in Transfer Service (mocked Account/Fraud clients), fraud rule evaluation, resilience config behavior.
- **Integration tests per service** (Testcontainers, real Postgres/Kafka — not H2/mocks): validates JPA optimistic locking, the outbox table + polling publisher, and Kafka producer/consumer wiring against the real infra they actually run against.
- **End-to-end saga tests** (Phase 10, not yet built): a dedicated test module boots the real services via Testcontainers/Docker Compose and drives full flows through the Gateway — happy path, fraud-rejection-with-compensation, and forced-Account-unavailable (proving circuit breaker/retry behavior end-to-end).
- **Fault injection:** Spring's `MockRestServiceServer` stands in for Account/Fraud in Transfer Service's client tests, simulating timeouts/5xxs to assert that retry and the circuit breaker behave and that the transfer fails cleanly. Fault injection against the real services is planned for the end-to-end tests (Phase 10).

## 7. Deployment / Local Dev

Single `docker compose up`:
- 5 Spring Boot services (Gateway, Account, Transfer, Fraud, Notification), plus the Bank UI (`web-ui`, `nginx:alpine` serving static assets, no build step, host port 8090) — see `docs/phase-9-bank-ui.md`
- One Postgres container, separate database per stateful service (Account, Transfer) via init script
- Kafka in KRaft mode (no Zookeeper)
- Keycloak with a pre-loaded realm/client (import file, not manual setup), including the Bank UI's custom login/registration theme
- OTel Collector, Prometheus, Grafana (provisioned dashboards), Grafana Tempo

There is no seed script. Keycloak's realm import provides the demo users; bank accounts are created by self-registration through the Bank UI (one account per user, seeded with a 1000.00 balance) or with `POST /accounts`. README covers: architecture diagram, run instructions, example requests, and where to observe the result (Grafana dashboard, trace UI).

## 8. Open Items

None at the design level. Build order, per-phase scope and the one remaining phase (end-to-end saga tests) are tracked in `docs/roadmap.md`; deferred findings live in each phase doc's own deferral/known-gaps section. The actual build order differed from this document's original guess — see the roadmap.
