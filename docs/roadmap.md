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

| # | Plan | Status | Scope |
|---|------|--------|-------|
| 1 | [Foundation + Account Service](plan-1-foundation-account-service.md) | ✅ Done | Project scaffolding, Account entity/repo with optimistic locking, REST API, Docker Compose + Postgres |
| 2 | [Transfer Service + Synchronous Saga](plan-2-transfer-service-saga.md) | ✅ Done | Transfer entity/ledger, sync saga (pre-validate → debit → credit) over RestClient, RFC 7807 ProblemDetail on both services, Actuator + Compose healthchecks. No resilience, no compensation, no Kafka |
| 3 | Resilience4j + Compensation + Idempotency | Not started | CircuitBreaker/Retry/TimeLimiter on Transfer → Account; compensating credit-back that drains `COMPENSATION_REQUIRED`, with `COMPENSATION_FAILED` as the manual-review terminal state; idempotency keys on debit/credit so a retry cannot double-move money |
| 4 | Transactional outbox + Kafka + Notification | Not started | Outbox table written in the same local transaction as the transfer's terminal state, scheduled polling publisher, Kafka in KRaft mode, Notification Service consuming `TransferCompleted`/`TransferFailed` |
| 5 | Fraud Service | Not started | Stateless rule-based risk check (amount/velocity thresholds), wired into the saga as Transfer's second sync call, with fraud rejection driving compensation |
| 6 | API Gateway + Auth | Not started | Keycloak (pre-configured realm), JWT validation at the Gateway and via Spring Security Resource Server in each service |
| 7 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; Spring Cloud Contract tests; end-to-end saga test module; final `docker compose up` bringing up all services + infra |

## Deferred items (not their own plan — folded into whichever plan touches that area, or reassessed later)

Carried over from Plan 1's final review, resolved or reassigned:
- ~~CI workflow (build/test on push)~~ — done: `.github/workflows/ci.yml` runs `./mvnw -B test` on every PR into `master` and on push to `master` (GitHub-hosted Ubuntu runner, Docker preinstalled for Testcontainers).
- ~~Actuator + container healthchecks~~ — done: resolved in Plan 2. Actuator `/actuator/health` endpoint wired into Docker Compose healthchecks on both Account and Transfer services.
- ~~`ErrorResponse` wire-contract decision (stable `code` field vs. RFC 7807 `ProblemDetail`)~~ — done: resolved in Plan 2. All services return RFC 7807 `application/problem+json` with a stable `code` property, duplicated per service rather than shared through a common module.
- ~~Swagger UI (springdoc-openapi)~~ — done for Account Service: `springdoc-openapi-starter-webmvc-ui` 2.6.0 (pinned in root `pom.xml`'s `springdoc-openapi.version` property — this is the version that actually matches Spring Boot 3.3.4/Spring Framework 6.1.x; newer 2.7.x+ lines target Spring Framework 6.2/Boot 3.4+ and fail to start against 3.3.4 with a `NoClassDefFoundError` on `LiteWebJarsResourceResolver`), browsable at `/swagger-ui.html`. Add to each future service the same way as it's built — re-verify the pinned version against that service's actual Spring Boot version each time; don't assume the same 2.6.0 pin still applies once a service moves to a newer Boot version.
- Flyway vs. `ddl-auto` for schema management — revisit before Plan 3 or later deploys to two service schemas (Account + Transfer). Currently using `ddl-auto: validate`.
- Idempotency keys for debit/credit — Plan 3 item; relevant once Transfer's saga can retry a sync call into Account via Resilience4j.
