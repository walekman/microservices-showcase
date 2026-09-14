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

Carried over from Phase 1's final review, resolved or reassigned:
- ~~CI workflow (build/test on push)~~ — done: `.github/workflows/ci.yml` runs `./mvnw -B test` on every PR into `master` and on push to `master` (GitHub-hosted Ubuntu runner, Docker preinstalled for Testcontainers).
- ~~Actuator + container healthchecks~~ — done: resolved in Phase 2. Actuator `/actuator/health` endpoint wired into Docker Compose healthchecks on both Account and Transfer services.
- ~~`ErrorResponse` wire-contract decision (stable `code` field vs. RFC 7807 `ProblemDetail`)~~ — done: resolved in Phase 2. All services return RFC 7807 `application/problem+json` with a stable `code` property, duplicated per service rather than shared through a common module.
- ~~Swagger UI (springdoc-openapi)~~ — done for Account and Transfer Services: `springdoc-openapi-starter-webmvc-ui` 2.6.0 (pinned in root `pom.xml`'s `springdoc-openapi.version` property — this is the version that actually matches Spring Boot 3.3.4/Spring Framework 6.1.x; newer 2.7.x+ lines target Spring Framework 6.2/Boot 3.4+ and fail to start against 3.3.4 with a `NoClassDefFoundError` on `LiteWebJarsResourceResolver`), browsable at `/swagger-ui.html` on each service port. Add to each future service the same way as it's built — re-verify the pinned version against that service's actual Spring Boot version each time; don't assume the same 2.6.0 pin still applies once a service moves to a newer Boot version.
- Flyway vs. `ddl-auto` for schema management — revisit before Phase 4 or later deploys to
  three service schemas (Account + Transfer + the outbox). Currently using `ddl-auto: update`.

Carried over from Phase 3's final review (an Opus subagent whole-branch pass, per
`CLAUDE.md`'s policy of using a more capable model for this gate), resolved on
`feature/phase-3-review-findings`:
- ~~Critical — a transient 409 is read as a permanent rejection.~~ — done:
  `AccountClient.rejected()` maps `CONCURRENT_MODIFICATION` to
  `AccountServiceUnavailableException` instead of `AccountRejectedException`, so both the
  live saga's `@Retry` and `CompensationScheduler`'s next sweep retry it instead of treating
  a routine optimistic-lock conflict as a definitive rejection. `IDEMPOTENCY_KEY_CONFLICT`
  (a genuine, permanent 409) is unaffected.
- ~~High — the concurrent-duplicate-debit race's safety is unproven.~~ — done: a live
  10-way same-`Idempotency-Key` concurrent-debit test against the real endpoint
  (`AccountControllerIT`) proves at least one loser gets 500 and the balance reflects
  exactly one debit, verifying the Hibernate flush-ordering assumption live instead of
  leaving it stated but untested.
- ~~High — the idempotent-replay path re-reads the account, not just the ledger.~~ — done
  (documented, not changed): confirmed still unreachable (no delete/archival capability
  exists anywhere in the app), so left as-is with an inline comment and a pinning regression
  test (`AccountServiceTest`) as the tripwire for whichever future phase adds one.
- ~~High — `AccountClientFallbackIT` only covers `getAccount`'s Resilience4j fallback.~~ —
  done: extended to assert `debit`/`credit`'s `debitCreditFallback` passes a definitive
  rejection through the real AOP proxy unchanged too; verified live by temporarily
  reintroducing the Task 6 bug and confirming the new assertions catch it.
- ~~Medium — `markCompleted()` from `COMPENSATION_REQUIRED` leaves stale `failureCode`/
  `failureReason`.~~ — done: both fields are now cleared in `markCompleted()`.
- ~~Medium — Resilience4j threshold tuning.~~ — done: `sliding-window-size`/
  `minimum-number-of-calls` scaled by the retry multiplier (10→30, 5→15) to restore the
  original "5 failed logical calls out of a window of 10" intent now that each logical call
  can cost up to 3 circuit-breaker-recorded attempts.
- ~~Medium — test coverage gaps that could hide a real-wiring bug the way the fallback bug
  was hidden.~~ — done: `AccountClientResilienceConfigMatchesYamlIT` cross-checks the
  hand-copied Retry/CircuitBreaker config against the real Spring-bound configuration (fails
  if they drift, verified live); `CompensationSchedulerIT` runs the real, Spring-managed
  `CompensationScheduler` bean against a real Postgres-backed `TransferRepository`, proving
  the real `save()`/`@Version` path end-to-end.
- ~~Medium — unbounded sweep batch size.~~ — done: `findByStatus`/
  `findByStatusAndCreatedAtBefore` gained `Limit`-bounded overloads, and
  `CompensationScheduler`'s two sweeps now pass a configurable
  `transfer.compensation.sweep-batch-size` (default 500).
- ~~Low — `Idempotency-Key` has no `@NotBlank`/`@Size` validation.~~ — done:
  `AccountController`'s `debit`/`credit` endpoints now validate it (`@NotBlank @Size(max =
  255)` via class-level `@Validated`), mapped to 400 `VALIDATION_FAILED` by a new
  `ConstraintViolationException` handler instead of falling through to a generic 500.
- ~~Low — README's `Idempotency-Key: demo-debit-1` examples share the global key
  namespace.~~ — done: examples now work the account id into the key.
