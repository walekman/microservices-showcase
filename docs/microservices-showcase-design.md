# Banking Microservices Showcase — Design

**Status:** Approved for planning
**Purpose:** Demonstrating modern (2024-2025 era) Java microservices practice: synchronous + asynchronous inter-service communication, relational persistence with JPA, observability, resilience, and containerized local deployment.

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
| **Fraud** | Rule-based risk check on a transfer (amount/velocity thresholds) | stateless |
| **Notification** | Consumes transfer-outcome events; logs a "notification sent" | stateless |

Each stateful service owns its data exclusively — no service queries another's database directly (database-per-service).

## 3. Tech Stack

- **Language/runtime:** Java 21, Spring Boot 3.x
- **Web:** Spring MVC (blocking, not WebFlux) with **virtual threads enabled** (`spring.threads.virtual.enabled=true`) — gets Java 21's concurrency model with plain sequential blocking code, no reactive programming model. Rationale: Transfer Service's saga makes a sequential chain of blocking downstream calls per request; virtual threads let that scale to many concurrent in-flight transfers without exhausting a platform-thread pool, and without rewriting the orchestration as reactive/async code. Out of scope: explicit thread-pinning verification/diagnostics tooling — the flag is enabled and left at that.
- **Persistence:** Spring Data JPA + PostgreSQL, one logical database per stateful service (Account, Transfer), provisioned as separate databases inside a single Postgres container via init script.
- **Messaging:** Apache Kafka, **KRaft mode** (no Zookeeper).
- **Auth:** Keycloak (OAuth2/OIDC), JWT validated at the Gateway and by each resource service via Spring Security Resource Server.
- **Resilience:** Resilience4j — CircuitBreaker, Retry, TimeLimiter on synchronous inter-service calls (Transfer → Account, Transfer → Fraud).
- **Error contract:** every service returns RFC 7807 `application/problem+json` on error,
  carrying a stable machine-readable `code` property alongside the standard `type`,
  `title`, `status` and `detail` fields. Consumers branch on `code`, never on the prose in
  `detail`, and never on the `type` URI (which is an identifier, not a dereferenceable
  URL). Un-enumerated 4xx statuses carry `REQUEST_REJECTED`, un-enumerated 5xx carry
  `INTERNAL_ERROR`, so the property is never absent. The handler and its codes are
  duplicated per service rather than shared through a common module: a shared DTO jar
  turns every contract change into a lockstep redeploy of every service.
- **Observability:** Micrometer Tracing bridged to OpenTelemetry → OTel Collector → Jaeger (or Grafana Tempo); Micrometer + Prometheus (`/actuator/prometheus`) scraped by Prometheus, visualized in Grafana; structured JSON logs with `traceId`/`spanId` auto-injected via MDC. No centralized log aggregation (ELK/Loki) — out of scope.
- **Deployment (local):** Docker Compose — single `docker compose up` brings up all services, Postgres, Kafka, Keycloak, and the observability stack.

## 4. Core Flow — The Transfer Saga

`POST /transfers {fromAccountId, toAccountId, amount}` via the Gateway (JWT validated) into Transfer Service.

**Happy path:**
1. Transfer Service creates a `Transfer` record, status `PENDING`, in its own DB.
2. **Sync call** → Fraud Service: screen the source account (before the debit — see docs/phase-5-fraud-service.md's Design Decisions for why the check runs twice, not once, between debit and credit). Same resilience wrapping. A block fails the transfer clean, no money moved.
3. **Sync call** → Account Service: debit the source account (optimistic locking on balance; rejects on insufficient funds). Wrapped in Resilience4j CircuitBreaker + Retry (retry only on transient errors, never on business rejections) + TimeLimiter.
3b. **Sync call** → Fraud Service: screen the destination account (before the credit). A block strands the transfer for compensation — the deliberate trigger for the compensation path below.
4. Destination clears → **sync call** → Account Service: credit the destination account.
5. Transfer Service marks the `Transfer` `COMPLETED` and writes an outbox row **in the same local DB transaction** (transactional outbox — guarantees the event and the DB state are consistent).
6. A scheduled **outbox publisher** (polling, not Debezium — keeps deployment footprint manageable) reads unpublished outbox rows, publishes `TransferCompleted`/`TransferFailed` to Kafka, marks them published.
7. Notification Service consumes the Kafka event, logs a "notification sent."

> **Settled in Phase 4** (see `docs/phase-4-outbox-kafka-notification.md`): two Kafka topics,
> `transfer.completed` and `transfer.failed` — one per event type, not one combined topic.
> `TransferCompleted`/`TransferFailed` above are the two outbox event *types*, not the only two
> `Transfer` statuses that reach them: `FAILED`, and the two compensation-outcome statuses Phase
> 3 added (`COMPENSATED`, `COMPENSATION_FAILED`), all publish to `transfer.failed`, distinguished
> by a `status` field in the JSON payload (which also carries `transferId`, `fromAccountId`,
> `toAccountId`, `amount`, `failureCode`, `failureReason`, `settledAt`). Plain JSON over the
> Kafka topics — no Avro, no schema registry, consistent with "keeps deployment footprint
> manageable."

**Failure & compensation paths:**
- **Source account blocked (pre-debit fraud screen):** transfer is marked `FAILED`. Nothing moved, nothing to compensate.
- **Destination account blocked (pre-credit fraud screen):** the source has already been debited, so the transfer is marked `COMPENSATION_REQUIRED` and CompensationScheduler credits the source back automatically, marking the transfer `COMPENSATED`. Emit `TransferFailed` via the same outbox mechanism either way (for observability/notification).
- **Compensation call itself fails** (the credit-back to the source account fails after Destination was blocked or after another rejection): this is the one case that can't simply retry-and-move-on. Mark the `Transfer` `COMPENSATION_FAILED` and route it to a manual-review/dead-letter path rather than silently leaving the ledger inconsistent. This path exists specifically to demonstrate the realistic edge case of saga design, not to be resolved automatically.
- **Downstream Account/Fraud unavailable after retries exhausted:** circuit breaker is open → Transfer Service fails fast (no hang), returns a clear error (e.g. `503`), and the local `Transfer` DB transaction rolls back if the debit was never confirmed — no dangling partial state.

## 5. Observability

- **Tracing:** a single transfer request produces one continuous trace spanning Gateway → Transfer → Account → Fraud → (async hop via Kafka headers) → Notification. Micrometer auto-propagates W3C trace context across both REST calls and Kafka messages, so the trace crosses the sync/async boundary — the concrete proof of the "both sync and async" design goal.
- **Metrics:** standard JVM/HTTP metrics plus business metrics: transfers completed/failed/fraud-rejected counters, outbox-backlog gauge. Resilience4j's Micrometer integration exposes circuit-breaker state transitions as metrics for free — visible on a Grafana panel during a fault-injection test.
- **Logging:** structured JSON, `traceId`/`spanId` auto-injected via MDC for correlation to traces/metrics. No centralized aggregation; `docker compose logs` is sufficient for the demo.
- **Health:** Actuator health/readiness endpoints per service, wired into Docker Compose healthchecks.

## 6. Testing Strategy

- **Unit tests** (JUnit 5 + Mockito): business logic in isolation — saga step sequencing in Transfer Service (mocked Account/Fraud clients), fraud rule evaluation, resilience config behavior.
- **Integration tests per service** (Testcontainers, real Postgres/Kafka — not H2/mocks): validates JPA optimistic locking, the outbox table + polling publisher, and Kafka producer/consumer wiring against the real infra they actually run against.
- **Cross-service contract tests** (Spring Cloud Contract): Transfer's synchronous dependency on Account's and Fraud's APIs is covered by consumer-driven contracts, so a breaking API change fails in the *producing* service's own build.
- **End-to-end saga tests:** a dedicated test module boots the real services via Testcontainers/Docker Compose and drives full flows through the Gateway — happy path, fraud-rejection-with-compensation, and forced-Account-unavailable (proving circuit breaker/retry behavior end-to-end).
- **Fault injection:** WireMock (or Toxiproxy) in front of Account/Fraud in targeted tests, simulating timeouts/5xxs to assert the circuit breaker opens and the transfer fails cleanly.

## 7. Deployment / Local Dev

Single `docker compose up`:
- 6 application services
- One Postgres container, separate database per stateful service (Account, Transfer) via init script
- Kafka in KRaft mode (no Zookeeper)
- Keycloak with a pre-loaded realm/client (import file, not manual setup)
- OTel Collector, Prometheus, Grafana (provisioned dashboards), Jaeger/Tempo

A seed script creates demo accounts with starting balances so a transfer can be triggered immediately after startup. README covers: architecture diagram, run instructions, example requests, and where to observe the result (Grafana dashboard, trace UI).

## 8. Open Items for Implementation Planning

None blocking — design is complete for this scope. Implementation planning should sequence service build-out (the roadmap's actual order was Account → Transfer w/ saga → Resilience/Compensation → outbox/Kafka/Notification → Fraud → Gateway/Auth → observability wiring, folding Notification in earlier than this section's original guess) and decide REST API contracts between services and the Resilience4j threshold values. Kafka topic names/event schemas were decided in Phase 4 — see §4's note above and `docs/phase-4-outbox-kafka-notification.md`.
