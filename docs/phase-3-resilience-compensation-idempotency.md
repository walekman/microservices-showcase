# Resilience4j + Compensation + Idempotency Implementation Phase

**Goal:** Make Transfer Service's calls into Account Service resilient (CircuitBreaker + Retry), make those calls safe to retry (idempotency keys on debit/credit), and add a scheduled compensator that automatically drains the `COMPENSATION_REQUIRED` and stale-`PENDING` states Phase 2 deliberately left unresolved.

**Architecture:** Two coordinated additions, one per service, tied together by one mechanism: idempotent replay.

- **Account Service** gains `AccountOperation`, a permanent, append-only ledger entry written atomically with every applied debit/credit, keyed by a caller-supplied `Idempotency-Key`. A repeat key returns the original result instead of reapplying the operation. This is the ground truth Transfer needs to resolve ambiguity — not a guess, an authoritative answer.
- **Transfer Service** gains: (1) Resilience4j `@CircuitBreaker`/`@Retry` wrapping every `AccountClient` call, using the deterministic key `"{transferId}:{leg}"` so a retry is automatically safe; (2) `CompensationScheduler`, an in-process `@Scheduled` job that periodically resolves every `COMPENSATION_REQUIRED` and stale-`PENDING` row by *replaying* the ambiguous call — Account's dedup either confirms it landed or applies it now — rather than guessing from Transfer's own possibly-stale record.

No new infrastructure (no Kafka, no outbox, no second service) — this stays in-process on both existing services, consistent with the single-instance Docker Compose deployment (no leader election needed for the scheduler).

**Tech Stack (additions):** `resilience4j-spring-boot3` on `transfer-service` — pin to the version compatible with Spring Boot 3.3.4 and verify it actually starts against that Boot line before committing to it, the same discipline already applied to springdoc-openapi's pin (see `docs/roadmap.md`).

**Spec:** [docs/microservices-showcase-design.md](microservices-showcase-design.md) §3 (Resilience), §4 (Compensation)

## Global Constraints

- Java 21 floor; Spring Boot 3.3.4. Use `C:\dev\openjdk-21.0.2` / `JAVA_HOME`. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads via `spring.threads.virtual.enabled=true`. `AccountClient` stays a blocking `RestClient` — see the TimeLimiter decision below for why no async/bulkhead machinery is introduced. (spec §3)
- One logical database per stateful service — `AccountOperation` lives in Account Service's own database; Transfer never queries it directly, only through `AccountClient`. (spec §2, §3)
- Integration-level tests use Testcontainers against real Postgres — never H2. (spec §6)
- Lombok for entity boilerplate: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic. (CLAUDE.md)
- `ddl-auto: update` stays; Flyway is still deferred to its own phase (unchanged from Phase 2).
- Never commit directly to `master`; work happens on a `feature/phase-3-*` branch. Subagent review is manual, on request — not automatic. (CLAUDE.md)
- springdoc-openapi stays pinned to `2.6.0`. (docs/roadmap.md)

## Scope Boundary

**In scope:**
- `AccountOperation` entity + idempotency dedup on Account's `debit`/`credit` (required `Idempotency-Key` header, 409 on a key reused with mismatched parameters).
- Resilience4j CircuitBreaker + Retry on all three `AccountClient` calls (`getAccount` × 2, `debit`, `credit`).
- `CompensationScheduler` with the two sweeps (`drainCompensationRequired`, `sweepStalePending`), and the new `COMPENSATED`/`COMPENSATION_FAILED` terminal states.

**Explicitly out of scope** — each is a named follow-up, not an oversight:

| Deferred | Why not now | Lands in |
|---|---|---|
| Resilience4j TimeLimiter | Would require wrapping blocking `RestClient` calls in `CompletableFuture` on a dedicated bulkhead executor purely to let Resilience4j enforce a second, redundant deadline — `AccountClientProperties`' existing connect/read timeouts already bound how long a call can hang. Revisit only if the existing timeouts prove insufficient in practice. | not planned |
| Idempotency key on `POST /transfers` (caller → Transfer) | Protects against a *caller* retrying transfer creation, not Transfer retrying into Account — a different gap. No automated caller exists yet to retry it (no Gateway, no Fraud calling Transfer). | Phase 5 or 6, once one exists |
| Transactional outbox + Kafka publisher | Distinct subsystem, its own container and test infrastructure | Phase 4 |
| Fraud Service (the saga's third call) | The saga is built to accept another step; adding one is additive | Phase 5 |
| Flyway migrations | Would require baselining two existing schemas — real work outside this slice | its own phase |
| Auth / JWT | No security on any service yet | Phase 6 |

## Design Decisions Worth Knowing Before You Start

**Reconciliation is replay, not a query API.** The Phase 2 deferral table's blocking precondition — "reconcile against Account before crediting anything back" — could have meant building a new read endpoint on Account to answer "did operation X happen?" Instead, the compensator resolves ambiguity by **replaying the original call with its original idempotency key**. Account's dedup then gives a definitive answer: applied now (first time), or already-applied (confirms it landed). This reuses the exact mechanism that makes retries safe in the first place, instead of building a second one.

**`COMPENSATION_FAILED` is reached only by a definitive rejection, never by exhausted unavailability.** If Account is merely unreachable when the compensator tries the source credit-back, that recreates the same ambiguity the compensator exists to resolve — so it just retries next sweep, logged at ERROR like `COMPENSATION_REQUIRED` already is. Only an explicit `AccountRejectedException` (e.g. the source account no longer exists) escalates to the manual-review terminal state.

**The stale-`PENDING` sweep resolves only the debit leg, then hands off.** It never independently re-checks the credit leg — if the debit is confirmed landed, the row is promoted to `COMPENSATION_REQUIRED` and left for the other sweep to resolve, reusing `markCompensationRequired()` (the same transition the live saga already uses). This keeps each sweep single-purpose. One consequence worth remembering, not a bug: a stale-`PENDING` row where *both* legs actually landed (only the final `save` failed) still passes through `COMPENSATION_REQUIRED` for one extra sweep before self-correcting to `COMPLETED` — the label is momentarily imprecise but never user-facing and never wrong for longer than one `sweep-interval`.

**`CallNotPermittedException` is mapped to `AccountServiceUnavailableException` inside `AccountClient`.** An open circuit breaker throws Resilience4j's own exception type, not either of the two domain exceptions `TransferService` already branches on. Catching and remapping it inside the client means `TransferService.execute()` needs zero changes — an open circuit is, correctly, indistinguishable from Account being unavailable.

**`AccountOperation` is a ledger entry, not a transient cache.** Unlike a generic idempotency-key cache (Stripe's model expires keys after 24h), these rows are permanent and never deleted — Account Service needs a per-operation record anyway for a system whose job is moving money, so idempotency dedup rides along on top of it rather than building a second, overlapping mechanism with its own lifecycle.

## Error Code Contract (additions)

| Account code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Already existed (Phase 2) — now also covers a missing `Idempotency-Key` header |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | The key was reused with a different `accountId`/`operation`/`amount` than its stored record |

Transfer needs no new `TransferFailureCode` for `IDEMPOTENCY_KEY_CONFLICT` — as an unmapped 4xx it already falls through to the existing `REQUEST_REJECTED` fallback. This should never actually surface in normal operation, since a given `(transferId, leg)` always maps to the same account/operation/amount (amount is immutable on `Transfer`).

## File Structure

**Account Service (modified):**

| File | Responsibility |
|---|---|
| `domain/AccountOperation.java` (new) | Entity: `idempotencyKey` (unique), `accountId`, `operation` (`DEBIT`/`CREDIT`), `amount`, `balanceAfter`, `createdAt` |
| `domain/AccountOperationRepository.java` (new) | Spring Data repository, lookup by `idempotencyKey` |
| `service/AccountService.java` (modified) | `debit`/`credit` check for an existing `AccountOperation` first; on the race between two duplicate inserts, catch the constraint violation and re-read rather than fail |
| `api/AccountController.java` (modified) | `debit`/`credit` require `@RequestHeader("Idempotency-Key")` |
| `api/ApiExceptionHandler.java` (modified) | Maps missing header → `VALIDATION_FAILED`; mismatched replay → `IDEMPOTENCY_KEY_CONFLICT` |

**Transfer Service (modified):**

| File | Responsibility |
|---|---|
| `client/AccountClient.java` (modified) | `debit`/`credit` take an `idempotencyKey` parameter, sent as the `Idempotency-Key` header; `@CircuitBreaker`/`@Retry` on all three public methods; catches `CallNotPermittedException` → `AccountServiceUnavailableException` |
| `service/TransferService.java` (modified) | Passes `"{transferId}:debit"` / `"{transferId}:credit"` as the idempotency key on each call — otherwise unchanged |
| `service/CompensationScheduler.java` (new) | `drainCompensationRequired()` and `sweepStalePending()`, each `@Scheduled` |
| `service/CompensationProperties.java` (new) | `sweep-interval` (default 15s), `pending-stale-after` (default 30s) |
| `domain/TransferStatus.java` (modified) | Adds `COMPENSATED`, `COMPENSATION_FAILED` |
| `domain/Transfer.java` (modified) | `requirePending()` generalizes to `requireStatus(TransferStatus...)`; `markCompleted()` now also accepts `COMPENSATION_REQUIRED` as a pre-state; new `markCompensated(...)`, `markCompensationFailed(...)`, both requiring `COMPENSATION_REQUIRED` |
| `domain/TransferRepository.java` (modified) | New `findByStatusAndCreatedAtBefore(TransferStatus, Instant)` |

**Infrastructure (modified):** `transfer-service/pom.xml` (resilience4j dependency), both services' `application.yml` (resilience4j config, compensation properties), `docs/roadmap.md`.
