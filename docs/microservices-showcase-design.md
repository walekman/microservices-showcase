# Banking Microservices Showcase — Design

**Status:** Implemented through Phase 14 — see `docs/roadmap.md`. This document describes the system as built; where it and a phase doc disagree, fix this one.
**Purpose:** Demonstrating current Java microservices practice: synchronous + asynchronous inter-service communication, relational persistence with JPA, observability, resilience, and containerized local deployment.

## 1. Overview

A small banking/ledger system: users hold accounts with balances, and money moves between accounts via a **transfer** operation. The transfer flow is the centerpiece — it's a genuine distributed transaction across services, orchestrated as a saga with compensation, giving a concrete, demoable story for both synchronous resilience patterns and asynchronous eventing, rather than either in isolation.

Non-goals: this is not a production banking system. No real payment rails, no double-entry general ledger accounting, no k8s/service mesh. Multi-currency is limited to one fixed currency per account and a rate locked onto each cross-currency transfer (§4). Scope is deliberately bounded to what's needed to demonstrate the target patterns credibly.

## 2. Services

| Service | Responsibility | State |
|---|---|---|
| **API Gateway** | Single entry point; routes requests to services; validates JWT | stateless |
| **Auth** | Issues/validates JWT via OAuth2/OIDC | Keycloak (containerized, pre-configured realm) |
| **Account** | Owns accounts & balances; debit/credit with optimistic locking; each account held in one currency, fixed at creation | PostgreSQL (own database) |
| **Transfer** | Orchestrates the transfer saga; owns transfer/ledger history + transactional outbox | PostgreSQL (own database) |
| **Fraud** | Screens each account in a transfer against a blocklist it stores (no amount/velocity rules); `fraud-admin` maintains it through `PUT`/`DELETE /fraud/blocklist/{accountId}`, which has no Gateway route | PostgreSQL (own database) |
| **FX** | Exchange rates for cross-currency transfers, from the Frankfurter/ECB feed behind a Redis cache (fresh TTL, last-known fallback, cross-instance single-flight lock) | stateless (Redis is a cache, not a store) |
| **Notification** | Consumes transfer-outcome events; stores one notification per transfer (a redelivery is skipped) and logs a "notification sent"; dead-letters events it cannot read or accept | PostgreSQL (own database) |
| **Bank UI** | Static browser single-page app for customer self-service (signup/onboarding, balance, transfers, history, quick-transfer); calls the Gateway directly from the browser | stateless (no build tooling — plain HTML/CSS/JS served by nginx) |

Each stateful service owns its data exclusively — no service queries another's database directly (database-per-service).

## 3. Tech Stack

- **Language/runtime:** Java 21, Spring Boot 3.x
- **Web:** Spring MVC (blocking, not WebFlux) with **virtual threads enabled** (`spring.threads.virtual.enabled=true`) — gets Java 21's concurrency model with plain sequential blocking code, no reactive programming model. Rationale: Transfer Service's saga makes a sequential chain of blocking downstream calls per request; virtual threads let that scale to many concurrent in-flight transfers without exhausting a platform-thread pool, and without rewriting the orchestration as reactive/async code. Out of scope: explicit thread-pinning verification/diagnostics tooling — the flag is enabled and left at that.
- **Persistence:** Spring Data JPA + PostgreSQL, one logical database per stateful service (Account, Transfer, Notification, Fraud), provisioned as separate databases inside a single Postgres container via init script.
- **Messaging:** Apache Kafka, **KRaft mode** (no Zookeeper).
- **Cache:** Redis (single node, no persistence), used only by FX Service in front of its external rate provider. An optimisation, never a dependency: with Redis down, FX Service calls the provider directly. See `docs/phase-12-fx-rates-redis-cache.md`.
- **Auth:** Keycloak (OAuth2/OIDC), JWT validated at the Gateway and by each resource service via Spring Security Resource Server.
- **Resilience:** Resilience4j — CircuitBreaker + Retry on synchronous inter-service calls (Transfer → Account, Transfer → Fraud, Transfer → FX). Call timeouts come from the `RestClient`'s connect/read timeouts, not a Resilience4j TimeLimiter.
- **Error contract:** every service returns RFC 7807 `application/problem+json` on error,
  carrying a stable machine-readable `code` property alongside the standard `type`,
  `title`, `status` and `detail` fields. Consumers branch on `code`, never on the prose in
  `detail`, and never on the `type` URI (which is an identifier, not a dereferenceable
  URL). Un-enumerated 4xx statuses carry `REQUEST_REJECTED`, un-enumerated 5xx carry
  `INTERNAL_ERROR`, so the property is never absent. The format, those generic codes and
  the handling of request-shape errors (validation, malformed input, the catch-all) are
  shared through the platform module (below). Each service's own codes and handlers stay
  in that service.
- **Shared code — infrastructure, never contracts:** one `platform` module, built and
  versioned with the services, holds the cross-cutting infrastructure every service would
  otherwise copy: the request log line, JWT decoding and Keycloak role mapping, the
  build-info metric, and the generic error handling above. It holds nothing that is part of
  a service's contract: no DTOs, event payloads or domain types, no service-specific error
  codes, endpoint paths, roles or authorization rules. Contracts stay duplicated per
  service on purpose: a shared contract jar turns every contract change into a lockstep
  redeploy of every service. Each service can replace any platform piece with its own. See
  `docs/phase-14-shared-platform-module.md`.
- **Observability:** Micrometer Tracing bridged to OpenTelemetry → OTel Collector → Grafana Tempo; Micrometer + Prometheus (`/actuator/prometheus`) scraped by Prometheus, visualized in Grafana; structured JSON logs with `traceId`/`spanId` auto-injected via MDC, collected from the six services' container stdout by Grafana Alloy into Grafana Loki and linked both ways with Tempo traces.
- **Deployment (local):** Docker Compose — single `docker compose up` brings up all services, Postgres, Kafka, Keycloak, and the observability stack.

## 4. Core Flow — The Transfer Saga

`POST /transfers {fromAccountId, toAccountId, amount}` via the Gateway (JWT validated) into Transfer Service, with a required `Idempotency-Key` header.

**Caller idempotency:** the key is stored on the `Transfer`, unique per initiator (`(initiator_id, idempotency_key)`). A repeat of a key the caller already used starts no new saga. It returns the earlier transfer's outcome (`201` if it completed, the same problem if it failed), `409 TRANSFER_IN_PROGRESS` with the `transferId` while that transfer is still `PENDING`, or `409 IDEMPOTENCY_KEY_CONFLICT` if the key was used for a different from/to/amount. Two concurrent requests with one key are settled by the unique constraint: the loser becomes a replay of the winner. The Bank UI keeps one key per intended transfer, so a double-click or a retry after a lost response cannot move the money twice.

**Happy path:**
1. Transfer Service pre-validates that both accounts exist, learning each one's currency. If the currencies differ it asks FX Service for a rate (see **Rate locking** below). It then inserts the `Transfer` record, status `PENDING`, with the conversion already on it. A failure at this step inserts the record as `FAILED` instead, and nothing moves.
2. **Sync call** → Fraud Service: screen the source account (before the debit — see `docs/phase-5-fraud-service.md`'s Design Decisions for why the check runs twice). A block fails the transfer clean, no money moved.
3. **Sync call** → Account Service: debit the source account (optimistic locking on balance; rejects on insufficient funds, and rejects unless the relayed JWT's subject owns the account), carrying an `Idempotency-Key` of `<transferId>:debit`.
4. **Sync call** → Fraud Service: screen the destination account (before the credit). A block strands the transfer for compensation — the deliberate trigger for the compensation path below.
5. **Sync call** → Account Service: credit the destination account (`Idempotency-Key` `<transferId>:credit`). Made as Transfer's own machine identity, not with the relayed customer token: credit has no ownership check (the destination belongs to someone else), so it requires `account-crediter`, which only that identity holds. It credits the locked `creditAmount`, in the destination's currency.
6. Transfer Service marks the `Transfer` `COMPLETED` and writes an outbox row **in the same local DB transaction** (transactional outbox — guarantees the event and the DB state are consistent).
7. A scheduled **outbox publisher** (polling, not Debezium — keeps deployment footprint manageable) reads unpublished outbox rows, publishes `TransferCompleted`/`TransferFailed` to Kafka, marks them published.
8. Notification Service consumes the Kafka event, stores it once (an identical redelivery from the at-least-once outbox is acknowledged and skipped), and logs a "notification sent." A database outage is retried in place, without limit and in order; an event that cannot be read or cannot be a real outcome goes to a dead-letter topic (`<topic>-dlt`) on the first attempt — see `docs/phase-11-notification-persistence-error-handling.md`.

**Rate locking:** a transfer's amount is always in the source account's currency. The rate, its publication date and the resulting `creditAmount` (HALF_EVEN, 2 places) are fixed on the row by the same insert that creates it, so no `PENDING` row ever lacks them. Every leg and every compensator replay sends exactly those values and never asks FX again: a re-priced replay would send a new amount under an idempotency key Account has already recorded. Account rejects a debit or credit labelled with the wrong currency (`422 CURRENCY_MISMATCH`), but only for an operation it has not already applied. See `docs/phase-12-fx-rates-redis-cache.md`.

Every downstream call (steps 1–5) is wrapped in Resilience4j CircuitBreaker + Retry — retry only on transient errors, never on business rejections.

> **Settled in Phase 4** (see `docs/phase-4-outbox-kafka-notification.md`): two Kafka topics,
> `transfer.completed` and `transfer.failed` — one per event type, not one combined topic.
> `TransferCompleted`/`TransferFailed` above are the two outbox event *types*, not the only two
> `Transfer` statuses that reach them: `FAILED`, and the two compensation-outcome statuses Phase
> 3 added (`COMPENSATED`, `COMPENSATION_FAILED`), all publish to `transfer.failed`, distinguished
> by a `status` field in the JSON payload (which also carries `transferId`, `fromAccountId`,
> `toAccountId`, `amount`, `failureCode`, `failureReason`, `settledAt`, and since Phase 12
> `sourceCurrency`, `destinationCurrency`, `rate`, `creditAmount`). Plain JSON over the
> Kafka topics — no Avro, no schema registry, consistent with "keeps deployment footprint
> manageable."
> Transfer, as the producer, declares both topics (`KafkaTopicConfig`: one partition, one replica)
> and creates them at startup, so they do not depend on the broker auto-creating them.

**Failure & compensation paths:**

The governing rule: a call that fails with `ACCOUNT_SERVICE_UNAVAILABLE` (a timeout or a 5xx, after retries) has an **unknown** outcome, not a failed one — a read timeout cannot be told apart from a request that never arrived. Nothing is ever credited back on a guess; the compensator reconciles by replaying the original idempotency key against Account, whose `AccountOperation` ledger returns the original result if the operation already committed. See `docs/phase-2-transfer-service-saga.md` (deferral table) and `docs/phase-3-resilience-compensation-idempotency.md` (Design Decisions).

- **Rejected before or at the debit** (missing account, caller not the owner, no exchange rate available (`FX_SERVICE_UNAVAILABLE`), an amount that converts to nothing (`AMOUNT_TOO_SMALL`), source blocked by Fraud, insufficient funds, Fraud unreachable on the source check): transfer is marked `FAILED`. Nothing moved, nothing to compensate.
- **Destination blocked, or the credit fails, after a confirmed debit:** the transfer is marked `COMPENSATION_REQUIRED`. `CompensationScheduler` (a periodic sweep) re-screens the destination and replays the credit with its original key. If the credit lands, the transfer is `COMPLETED`. If the destination is still blocked or Account definitively rejects the credit, the scheduler credits the source back and marks the transfer `COMPENSATED`. An unreachable service leaves the row for the next sweep.
- **Transfer left `PENDING`** (e.g. the process died mid-saga): a stale-`PENDING` sweep replays the debit key to learn whether the debit landed — `FAILED` if it did not, promoted to `COMPENSATION_REQUIRED` (then resolved as above) if it did. If the source has been blocklisted by then, the row stays `PENDING` and is retried every sweep until the block is lifted: replaying the debit key would perform the debit if it never landed, and marking it `FAILED` would guess that it did not. Settling such a row without lifting the block would need a read-only (or void-if-absent) operation lookup on Account, which is deliberately not planned (`open-items.md` §3).
- **The credit-back itself is rejected:** the transfer is marked `COMPENSATION_FAILED` — the manual-review terminal state. It is the one outcome the system deliberately does not resolve on its own.
- **Account/Fraud unavailable after retries exhausted:** the circuit breaker opens and Transfer Service fails fast (`503`, no hang). On the debit leg the outcome is unknown, so the transfer is **not settled**: it stays `PENDING`, and the caller gets `503 ACCOUNT_SERVICE_UNAVAILABLE` with the `transferId` and `transferStatus: PENDING`. The stale-`PENDING` sweep then replays the debit key and resolves it as above, so the transfer can still complete. Recording it `FAILED` would be terminal, and wrong for good whenever the debit had committed.

Every terminal state emits a `TransferCompleted` or `TransferFailed` event through the outbox.

**Reading transfers.** `GET /transfers/mine` lists both directions for the caller, each item carrying `direction` (`OUTGOING`/`INCOMING`): every transfer they started, in any status, plus the `COMPLETED` transfers into any account they own. A recipient never sees a sender's failed or unsettled attempt, nor its failure reason. Transfer does not store who owns a destination account (see `docs/phase-7b-account-ownership-authorization.md`), so it learns the caller's accounts from Account's `GET /accounts/mine` on each read, with the caller's relayed token; if Account is unavailable the list answers `503` rather than silently dropping the incoming half.

## 5. Observability

- **Tracing:** a single transfer request produces one continuous trace spanning Gateway → Transfer → Account/FX/Fraud (in saga order) → (async hop via Kafka headers) → Notification. Micrometer auto-propagates W3C trace context across both REST calls and Kafka messages, so the trace crosses the sync/async boundary — the concrete proof of the "both sync and async" design goal. FX Service goes one level deeper: `@Observed` on `FxRateController`, `FxRateService`, `RateCache` and `RateProvider` makes their public methods spans (with every argument as an `args.*` span attribute, never a metric tag), and Lettuce's `MicrometerTracing` (`TracingConfig`) adds a span per Redis command with its key, so a `/fx/rates` trace shows the cache-aside path (hit, or miss → lock → provider → put → unlock). Fraud Service traces its database calls with `datasource-micrometer-spring-boot` (Boot does not instrument the `DataSource`): a span per JDBC connection (checkout to release) and one per query carrying its SQL without bound parameter values, both under the calling span.
- **Metrics:** standard JVM/HTTP metrics plus business metrics: transfers completed/failed/fraud-rejected counters, outbox-backlog gauge. Resilience4j's Micrometer integration exposes circuit-breaker state transitions as metrics for free — visible on a Grafana panel during a fault-injection test.
- **Logging:** structured JSON, `traceId`/`spanId` auto-injected via MDC. Grafana Alloy tails the six Spring services' container stdout through the Docker socket and pushes it to Grafana Loki (single binary, 3-day retention), labelled only `service` and `level`; every other JSON field, `traceId` included, stays in the line and is filtered with `| json`. In Grafana, a Tempo span's "Related logs" runs that query for its trace, and a log line's `traceId` links back to the trace; a provisioned Service Logs dashboard filters by service, level and text. Each HTTP-facing service (all but Notification) logs one line per request (`POST /transfers 201 184ms`, no query string, headers or bodies, `/actuator/**` skipped; 5xx at `WARN`), so every hop of a request shows up in its trace's logs. Anonymous Grafana visitors are Editors, because the trace↔logs links open in Explore, which Viewers can't use. Kafka, Keycloak, Postgres and the other infrastructure containers are not collected. Built in `docs/phase-13-log-aggregation-loki.md`.
- **Health:** Actuator health/readiness endpoints per service, wired into Docker Compose healthchecks.
- **Versioning:** each service's build version is its pom `<version>` and is exposed four ways: `GET /actuator/info` (unauthenticated: `build.version` plus the git commit), a constant-1 `application_info{version,commit}` gauge (the Prometheus "info metric" convention) driving a provisioned Grafana Service Versions dashboard, the `service.version` OpenTelemetry resource attribute on every span, and an OCI `revision` label on each image. This is build versioning only; API-contract versioning (OpenAPI `info.version`, currently `v1`) is separate and unchanged.

## 6. Testing Strategy

- **Unit tests** (JUnit 5 + Mockito): business logic in isolation — saga step sequencing in Transfer Service (mocked Account/Fraud clients), fraud rule evaluation, resilience config behavior.
- **Integration tests per service** (Testcontainers, real Postgres/Kafka — not H2/mocks): validates JPA optimistic locking, the outbox table + polling publisher, and Kafka producer/consumer wiring against the real infra they actually run against.
- **End-to-end saga tests** (Phase 10): the `e2e-tests` module, built only under `-Pe2e` and run locally (not in CI or `./mvnw test`), starts its own copy of the whole stack (`docker-compose.yml` plus `docker-compose.e2e.yml`, under a random project name with no fixed container names or host ports) and drives six scenarios through the Gateway as fresh Keycloak users, asserting on both balances: happy path with an idempotent repeat, cross-currency at the locked rate, blocked source (clean failure), blocked destination (debited, then compensated), Account unreachable (retry, circuit breaker opens, then recovers), and a lost debit response (left `PENDING`, settled exactly once by the stale-`PENDING` sweep).
- **Fault injection:** in Transfer Service's client tests, Spring's `MockRestServiceServer` stands in for Account/Fraud/FX, simulating timeouts/5xxs to assert that retry and the circuit breaker behave. Against the real services, the end-to-end stack routes every Transfer → Account call through a WireMock proxy that forwards by default and can reset every connection or hold back debit responses past Transfer's read timeout; a second WireMock stands in for the FX rate provider.

## 7. Deployment / Local Dev

Single `docker compose up`:
- 6 Spring Boot services (Gateway, Account, Transfer, Fraud, FX, Notification), plus the Bank UI (`web-ui`, `nginx:alpine` serving static assets, no build step, host port 8090) — see `docs/phase-9-bank-ui.md`
- One Postgres container, separate database per stateful service (Account, Transfer, Notification, Fraud) via init script
- Kafka in KRaft mode (no Zookeeper)
- Redis (single node, persistence off), FX Service's rate cache
- Keycloak with a pre-loaded realm/client (import file, not manual setup), including the Bank UI's custom login/registration theme
- OTel Collector, Prometheus, Grafana (provisioned dashboards), Grafana Tempo, Grafana Loki fed by Grafana Alloy (Loki and Alloy are not in the E2E stack)

There is no seed script. Keycloak's realm import provides the demo users; bank accounts are created by self-registration through the Bank UI (one account per user, seeded with a 1000.00 balance) or with `POST /accounts`. README covers: architecture diagram, run instructions, example requests, and where to observe the result (Grafana dashboard, trace UI).

## 8. Open Items

None at the design level. Build order and per-phase scope are tracked in `docs/roadmap.md`; deferred findings live in each phase doc's own deferral/known-gaps section. The actual build order differed from this document's original guess — see the roadmap.
