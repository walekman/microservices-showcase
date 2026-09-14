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
| 4 | Transactional outbox + Kafka + Notification | Not started | Outbox table written in the same local transaction as the transfer's terminal state, scheduled polling publisher, Kafka in KRaft mode, Notification Service consuming `TransferCompleted`/`TransferFailed` |
| 5 | Fraud Service | Not started | Stateless rule-based risk check (amount/velocity thresholds), wired into the saga as Transfer's second sync call, with fraud rejection driving compensation |
| 6 | API Gateway + Auth | Not started | Keycloak (pre-configured realm), JWT validation at the Gateway and via Spring Security Resource Server in each service |
| 7 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; Spring Cloud Contract tests; end-to-end saga test module; final `docker compose up` bringing up all services + infra |

## Deferred items (not their own phase — folded into whichever phase touches that area, or reassessed later)

Carried over from Phase 1's final review, not yet resolved:
- Flyway vs. `ddl-auto` for schema management — revisit before Phase 4 or later deploys to
  three service schemas (Account + Transfer + the outbox). Currently using `ddl-auto: update`.
