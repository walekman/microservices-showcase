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
`CLAUDE.md`'s policy of using a more capable model for this gate), not yet resolved:
- **Critical — a transient 409 is read as a permanent rejection.** `AccountClient.rejected()`
  maps every 4xx, including `CONCURRENT_MODIFICATION` (Account's own optimistic-lock
  conflict, genuinely transient and expected under concurrent load), to
  `AccountRejectedException`. `CompensationScheduler.reconcileCredit`/`compensateSource`
  treat *any* `AccountRejectedException` as definitive without checking which code it
  carries, so a routine version conflict on either leg during a sweep can trigger an
  unwarranted reversal, or — if both legs hit one — a permanent `COMPENSATION_FAILED` that
  neither sweep ever revisits. Fix: map `CONCURRENT_MODIFICATION` to
  `AccountServiceUnavailableException` in `AccountClient.rejected()` (or branch on
  `ex.getCode()` in the compensator), so it retries next sweep instead of reversing.
- **High — the concurrent-duplicate-debit race's safety is unproven.** Two concurrent
  requests with the same `Idempotency-Key` both reach `account.debit(amount)` in
  `AccountService.apply(...)` before either flushes; today's 500-not-409 outcome for the
  loser (which the Critical item above depends on to stay safe) appears to rely on
  Hibernate flushing queued inserts before queued updates within one transaction — plausible
  and consistent with documented Hibernate behavior, but untested and unstated in the code.
  Add a live concurrency test that races two same-key debits at the real endpoint and
  asserts the loser gets 500, and document the ordering dependency inline.
- **High — the idempotent-replay path re-reads the account, not just the ledger.** In
  `AccountService.apply(...)`, a replay hit still does an independent
  `accountRepository.findById(id)` and can 404 if the account is ever gone — turning an
  already-applied operation into a false rejection. Currently unreachable (no delete
  capability exists anywhere in the app), so defense-in-depth rather than a live bug; revisit
  if any future phase adds account deletion/archival.
- **High — `AccountClientFallbackIT` only covers `getAccount`'s Resilience4j fallback.** The
  test written specifically to guard the Task 6 fallback-passthrough bug doesn't call
  `debit`/`credit`, so `debitCreditFallback` — the one the compensator actually depends on —
  has no real-AOP-proxy regression coverage. Add the same assertion for `debit`/`credit`.
- **Medium — `markCompleted()` from `COMPENSATION_REQUIRED` leaves stale `failureCode`/
  `failureReason`.** A transfer reconciled to `COMPLETED` after being stranded still returns
  its old failure diagnostics in the API response. Clear both fields in `markCompleted()`.
- **Medium — Resilience4j threshold tuning.** Each retry attempt counts as its own
  circuit-breaker call (`@Retry` is the outer decorator), so two failing sagas alone can trip
  `minimum-number-of-calls: 5` — observed live during Phase 3's own verification (the circuit
  opened after two transfer attempts while `account-service` was down). Not clearly wrong,
  but worth a deliberate tuning pass rather than the current default.
- **Medium — test coverage gaps that could hide a real-wiring bug the way the fallback bug
  was hidden.** `AccountClientResilienceTest`'s hand-built Retry/CircuitBreaker config isn't
  cross-checked against `application.yml` (the two can drift silently); no test exercises a
  real scheduled `CompensationScheduler` execution end-to-end; `CompensationSchedulerTest`
  mocks both `AccountClient` and `TransferRepository`, so the real `save()`/`@Version` path
  is untested at that layer too.
- **Medium — unbounded sweep batch size.** Neither `findByStatus` nor
  `findByStatusAndCreatedAtBefore` in `CompensationScheduler` paginates; fine at this
  project's scale, worth a `Limit`-bounded query if transfer volume ever grows.
- **Low — `Idempotency-Key` has no `@NotBlank`/`@Size` validation** on `AccountController`'s
  `debit`/`credit` endpoints; a blank or overlong key reaches a generic 500 that
  `AccountClient` then treats as retryable.
- **Low — README's `Idempotency-Key: demo-debit-1` examples share the global key namespace**
  (the `account_operations` PK is the key alone, not scoped per account), so copy-pasting the
  literal example against a second account returns 409 instead of debiting it.
