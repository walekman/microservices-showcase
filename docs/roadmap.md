# Implementation Roadmap

Tracks the build-out sequence from `docs/microservices-showcase-design.md` §8
("Account → Transfer w/ saga → Fraud → Notification → Gateway/Auth →
observability wiring → Docker Compose integration"), broken into discrete
plans. Each plan gets its own `docs/plan-N-<name>.md` (see "Documentation
conventions" in `CLAUDE.md`) once it's brainstormed and written — this
file is the index, not a substitute for the plan docs themselves.

Scope and boundaries for a not-yet-written plan below are a rough forecast
from the design doc, not a commitment — each plan's actual scope is decided
when it's brainstormed, and may reshape later rows.

| # | Plan | Status | Scope (forecast) |
|---|------|--------|-------------------|
| 1 | [Foundation + Account Service](plan-1-foundation-account-service.md) | ✅ Done | Project scaffolding, Account entity/repo with optimistic locking, REST API, Docker Compose + Postgres |
| 2 | Transfer Service + the Saga | Not started | Transfer entity/ledger, orchestrated saga calling Account (sync, Resilience4j-wrapped), transactional outbox + polling publisher, compensation on failure |
| 3 | Fraud Service | Not started | Stateless rule-based risk check (amount/velocity thresholds), wired into the saga as Transfer's second sync call |
| 4 | Notification Service | Not started | Kafka consumer for `TransferCompleted`/`TransferFailed`, logs "notification sent" — closes the async leg of the saga |
| 5 | API Gateway + Auth | Not started | Keycloak (pre-configured realm), JWT validation at the Gateway and via Spring Security Resource Server in each service |
| 6 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; final `docker compose up` bringing up all 6 services + infra together |

## Deferred items (not their own plan — folded into whichever plan touches that area, or reassessed later)

Carried over from Plan 1's final review, not yet assigned:
- ~~CI workflow (build/test on push)~~ — done: `.github/workflows/ci.yml` runs `./mvnw -B test` on every PR into `master` and on push to `master` (GitHub-hosted Ubuntu runner, Docker preinstalled for Testcontainers).
- Actuator + container healthchecks — natural fit for Plan 2, since Transfer's `depends_on: account-service` needs it.
- `ErrorResponse` wire-contract decision (stable `code` field vs. RFC 7807 `ProblemDetail`) — Transfer Service's error handling depends on this; decide during Plan 2 brainstorming rather than let Account's ad hoc shape become the de facto standard by default.
- Flyway vs. `ddl-auto` for schema management — revisit before a second service's schema exists.
- Idempotency keys for debit/credit — relevant once Transfer's saga can retry a sync call into Account.
- Swagger UI (springdoc-openapi) — makes each service's REST API callable straight from a browser. Add per-service as each one is built (`springdoc-openapi-starter-webmvc-ui`, pinned to the last 2.x release compatible with the service's Spring Boot version — 3.x springdoc needs Boot 3.4+). Quick, low-risk follow-up; not tied to any one plan.
