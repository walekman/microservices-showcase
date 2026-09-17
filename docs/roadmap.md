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
| 5 | Fraud Service | ✅ Done | Fraud Service (stateless account-blocklist screen, no DB); FraudClient wired into the saga as two sync calls (source before debit, destination before credit); CompensationScheduler's stale-PENDING and COMPENSATION_REQUIRED sweeps both gate on their matching fraud check before acting |
| 6 | [API Gateway](phase-6-api-gateway.md) | ✅ Done | Routing-only `gateway-service` (Spring Cloud Gateway Server MVC, blocking — no WebFlux) in front of Transfer Service's full API and Account Service's three client-safe paths (`POST /accounts`, `GET /accounts`, `GET /accounts/{id}`); Account's `debit`/`credit` and all of Fraud/Notification stay unreachable through the Gateway by construction (no route exists), not by a filter. No JWT/auth in this phase. |
| 7 | [Auth (Keycloak/JWT)](phase-7-auth-keycloak-jwt.md) | ✅ Done | Keycloak (pre-configured `showcase` realm) + JWT validation (Spring Security OAuth2 Resource Server) independently on Gateway/Account/Transfer/Fraud; four capability-scoped realm roles (`transfer-executor`, `account-reader`, `account-editor`, `fraud-checker`) plus a `customer` composite for real users; Transfer Service relays the calling user's token on the live saga path and falls back to its own `transfer-service` client-credentials token for `CompensationScheduler`'s background sweep. Per-account ownership deliberately deferred — see Phase 7b. |
| 7b | [Account Ownership Authorization](phase-7b-account-ownership-authorization.md) | ✅ Done | `Account.ownerId`/`Transfer.initiatorId` bound to the caller's JWT subject; ownership enforcement on `debit` and the single-item reads (`GET /accounts/{id}`, `GET /transfers/{id}`), with a machine-identity exemption for `CompensationScheduler`'s reconciliation replay; new existence-only `GET /accounts/exists/{id}` for Transfer's pre-validate step; `account-admin`/`transfer-admin` roles (held by a new `admin` demo user) gating the two list-all endpoints. Direct-`credit` money-minting stays deliberately deferred — see the phase doc's Design Decisions. |
| 8 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; Spring Cloud Contract tests; end-to-end saga test module; final `docker compose up` bringing up all services + infra |

Deferred items and findings carried over between phases live in each phase's own doc now (its
"Deferred / Known Gaps", "Scope Boundary", or "Final Review" section — see `docs/phase-1-foundation-account-service.md`,
`docs/phase-4-outbox-kafka-notification.md`, and `docs/phase-7-auth-keycloak-jwt.md`), not here —
this file stays an index, per the note at the top.
