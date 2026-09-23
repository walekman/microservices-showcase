# Implementation Roadmap

Tracks the build-out of the system in `docs/microservices-showcase-design.md`,
broken into discrete phases. The table below is the order the phases were
actually built in, which differs from the design doc's original guess. Each
phase gets its own `docs/phase-N-<name>.md` (see "Documentation
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
| 5 | [Fraud Service](phase-5-fraud-service.md) | ✅ Done | Fraud Service (stateless account-blocklist screen, no DB); FraudClient wired into the saga as two sync calls (source before debit, destination before credit); CompensationScheduler's stale-PENDING and COMPENSATION_REQUIRED sweeps both gate on their matching fraud check before acting |
| 6 | [API Gateway](phase-6-api-gateway.md) | ✅ Done | Routing-only `gateway-service` (Spring Cloud Gateway Server MVC, blocking — no WebFlux) in front of Transfer Service's full API and Account Service's three client-safe paths (`POST /accounts`, `GET /accounts`, `GET /accounts/{id}`); Account's `debit`/`credit` and all of Fraud/Notification stay unreachable through the Gateway by construction (no route exists), not by a filter. No JWT/auth in this phase. |
| 7 | [Auth (Keycloak/JWT)](phase-7-auth-keycloak-jwt.md) | ✅ Done | Keycloak (pre-configured `showcase` realm) + JWT validation (Spring Security OAuth2 Resource Server) independently on Gateway/Account/Transfer/Fraud; four capability-scoped realm roles (`transfer-executor`, `account-reader`, `account-editor`, `fraud-checker`) plus a `customer` composite for real users; Transfer Service relays the calling user's token on the live saga path and falls back to its own `transfer-service` client-credentials token for `CompensationScheduler`'s background sweep. Per-account ownership deliberately deferred — see Phase 7b. |
| 7b | [Account Ownership Authorization](phase-7b-account-ownership-authorization.md) | ✅ Done | `Account.ownerId`/`Transfer.initiatorId` bound to the caller's JWT subject; ownership enforcement on `debit` and the single-item reads (`GET /accounts/{id}`, `GET /transfers/{id}`), with a machine-identity exemption for `CompensationScheduler`'s reconciliation replay; new existence-only `GET /accounts/exists/{id}` for Transfer's pre-validate step; `account-admin`/`transfer-admin` roles (held by a new `admin` demo user) gating the two list-all endpoints. Direct-`credit` money-minting stays deliberately deferred — see the phase doc's Design Decisions. |
| 8 | [Observability](phase-8-observability.md) | ✅ Done | Micrometer Tracing → OTel Collector → Grafana Tempo across the sync+async hop; Prometheus metrics (business counters/gauges + Resilience4j) with provisioned Grafana dashboards; native Boot 3.5 structured JSON logging; Boot bumped 3.3.8 → 3.5.16 project-wide. Full `docker compose up` already brings up every service (done incidentally in phases 5–7) — this phase adds the observability stack on top of it. Spring Cloud Contract tests dropped from the roadmap entirely; end-to-end saga tests split out — see Phase 10. |
| 8b | [Version Visibility](phase-8b-version-visibility.md) | ✅ Done | Boot `build-info` plus an unauthenticated `/actuator/info` (build version + git commit, the commit passed as a Docker build arg) on all five services; a constant-1 `application_info{version,commit}` Prometheus info metric and the OTel `service.version` resource attribute; a provisioned Grafana "Service Versions" dashboard (running builds, version-skew stats, build history). Build version only — API `/v1` versioning, outbox event `schemaVersion`, and release automation (including a CI version override) are deliberately out of scope (see the phase doc's Scope Boundary). |
| 9 | [Bank UI](phase-9-bank-ui.md) | ✅ Done | Static HTML/CSS/JS single-page app (no build tooling) served by nginx, calling the Gateway via OAuth2 Authorization Code + PKCE against Keycloak's existing `showcase-ui` client (hosted login/registration pages, custom-themed); self-registration provisions exactly one account per user (seed balance 1000.00, server-enforced one-account-per-owner rule); dashboard, new transfer, transfer history, and a quick-transfer list derived from history. New `GET /accounts/mine`, `GET /accounts/{id}/summary`, `GET /transfers/mine` endpoints |
| 10 | End-to-End Saga Tests | Not started | Dedicated test module, Testcontainers/Docker Compose-driven, real services through the Gateway — happy path, fraud-rejection-with-compensation, forced-Account-unavailable (circuit breaker/retry proof), including fault injection against the real services. To be written as `docs/phase-10-end-to-end-saga-tests.md`. Split out of the original Phase 8 forecast during Phase 8 brainstorming (see `docs/phase-8-observability.md`) |

Deferred items and findings carried over between phases live in each phase's own doc (its
"Deferred / Known Gaps", "Scope Boundary", "Design Decisions", or "Final Review" section), not
here — this file stays an index, per the note at the top.

Every item still open across those docs — gaps, planned work, tech debt, and what was
deliberately ruled out — is collected in [`open-items.md`](open-items.md).

## Investigations

Standalone write-ups that aren't tied to a single phase:

- [`AccountControllerIT` concurrency-test CI flakiness](investigation-account-controller-it-concurrency-flake.md)
  — the same-idempotency-key concurrency test is `@Disabled` pending a rewrite.
