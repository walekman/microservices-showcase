# Fraud Service Implementation Phase (Phase 5)

**Goal:** Add Fraud Service — a stateless account-blocklist screen — as the saga's second and third synchronous calls (once before the debit, once before the credit), giving the compensation machinery Phase 3 built a reliable, on-demand way to trigger through normal API calls, instead of requiring fault injection.

**Spec:** [docs/microservices-showcase-design.md](microservices-showcase-design.md) §2–§4 (component table, tech stack, saga). Those sections describe Fraud generically ("rule-based risk check on a transfer... amount/velocity thresholds", one sync call between debit and credit); this phase's Design Decisions below supersede that with what was actually brainstormed with the user on 2026-09-16 — see the "Design doc updates needed" task for reconciling the prose.

## Design Decisions Worth Knowing Before You Start

**Why account-blocklist, not amount/velocity thresholds.** The roadmap's forecast (amount/velocity) was reconsidered during brainstorming. Velocity would require Fraud Service to either hold state itself (contradicts its "stateless" component-table entry) or have Transfer Service compute and forward a rolling aggregate (real, but adds real scope for a rule that exists to demonstrate a saga pattern, not actual fraud modelling). Amount thresholds are a magic number a demo caller has to remember. A destination-account blocklist is the simplest rule that's still a real-world pattern (OFAC/sanctions-style screening) and gives a self-documenting, repeatable trigger: send to a reserved test account id. Both velocity and amount thresholds are explicitly deferred, not designed around.

**Why the check runs twice — once before debit, once before credit — not once.** The original one-call design (a single check between debit and credit) was reconsidered because "fraud check after the money already moved" is the *less* realistic ordering on its own. Splitting it into two calls resolves that and adds a second, independently-triggerable demo path:
- **Source check, before debit** — screening the sender before their money moves, the realistic ordering for outbound screening. A block here is a clean rejection: nothing moved, so it behaves exactly like every other pre-debit rejection already in `TransferService.execute()`.
- **Destination check, after debit, before credit** — screening the recipient before finalizing the incoming funds. This is the realistic shape of a real-world "wire recall": the sending side has already released the money, and the receiving side (or a correspondent/AML screen) rejects it, forcing a genuine reversal. This is also the *only* new trigger for `COMPENSATION_REQUIRED` — deliberately, since giving the compensation path a reliable trigger was the whole reason to build Fraud Service at all.

Fraud Service itself doesn't know which "side" it's checking — it's a single `GET /fraud-check?accountId=` that answers "is this account blocked?" for whatever id it's given. The saga position, not the service, decides what a block means.

**Why Fraud-unavailable is handled identically to Account-unavailable at each position, not retried specially.** Early in brainstorming, unavailability (as opposed to a definitive block) was going to get bespoke handling — stay `PENDING` and silently retry pre-debit, since nothing has moved and permanently failing the request over a transient blip seemed wasteful. This was reconsidered once the *existing* Account-unavailable behavior was laid out in full (see the table below and the README's "All saga outcomes" section): Account Service unreachable during the pre-validate step **already** just fails clean today, no auto-retry, caller resubmits — there was no precedent anywhere in the codebase for a pre-debit step silently lingering and self-healing. Introducing one only for Fraud would be new, asymmetric machinery for a small win. The simpler, consistent choice: Fraud-unavailable uses the exact same `fail()`/`strand()` shape Account-unavailable already uses at the equivalent position — terminal `FAILED` before debit, `COMPENSATION_REQUIRED` (scheduler-resolved) after.

**The one real gap this still requires closing: recovery paths must replay every gate the live saga enforces, not just the leg they were built to resolve.** `CompensationScheduler`'s two sweeps predate Fraud Service and only ever resolved a single Account leg (debit or credit). Naively bolting the two new checks on as one-more-thing-to-branch-on almost reintroduced a real bug:
- A `COMPENSATION_REQUIRED` row that reached that status via `reconcileDebit()`'s stale-`PENDING` promotion (see below) or via an ordinary credit failure has *never* had its destination checked. If the scheduler only re-ran the destination fraud check for rows the live saga had already tagged `DESTINATION_*`, those other rows would skip the gate entirely and credit a potentially-blocklisted destination.
- Fix: the destination fraud check is unconditional in `drainCompensationRequired()` — every `COMPENSATION_REQUIRED` row gets it, regardless of why it's stranded, before any credit attempt. `DESTINATION_ACCOUNT_BLOCKED`/`DESTINATION_FRAUD_SERVICE_UNAVAILABLE` remain on the transfer purely as diagnostics of what the *live* saga observed, not as scheduler control flow.
- Symmetrically, `reconcileDebit()`'s stale-`PENDING` recovery already skips replaying the account-existence pre-check — safe today only because Account's own `debit()` endpoint naturally re-enforces existence as a side effect. Fraud blocklisting has no such natural backstop anywhere else, so `reconcileDebit()` must gain its own source fraud check in front of the (idempotent) debit call, run unconditionally on every stale-`PENDING` row.

**No new `TransferStatus` values, no new schema surface.** Both new failure modes reuse `FAILED`/`COMPENSATION_REQUIRED` — `TransferFailureCode` is the entire discriminating mechanism. This matters beyond tidiness: `docs/roadmap.md` carries a Phase-1 deferral ("Flyway vs `ddl-auto`... revisit before Phase 5 or later phases add more schema surface"). This phase adds zero columns, so that revisit trigger is not tripped by Phase 5 — worth noting explicitly in the roadmap update so a future reader doesn't have to re-derive it.

## Complete Saga Outcome Table (supersedes the README's pre-Phase-5 list)

Live request (`TransferService.execute()`):

| # | Path | Outcome |
|---|---|---|
| 1 | source exists → destination exists → source not blocked → debit succeeds → destination not blocked → credit succeeds | `COMPLETED` |
| 2 | source or destination doesn't exist | `FAILED` (`ACCOUNT_NOT_FOUND`) |
| 3 | Account Service unreachable during existence pre-check | `FAILED` (`ACCOUNT_SERVICE_UNAVAILABLE`) |
| 4 | **source account blocklisted** | `FAILED` (`SOURCE_ACCOUNT_BLOCKED`) — *new* |
| 5 | **Fraud Service unreachable checking the source** | `FAILED` (`SOURCE_FRAUD_SERVICE_UNAVAILABLE`) — *new* |
| 6 | debit rejected (e.g. `INSUFFICIENT_FUNDS`) | `FAILED` |
| 7 | debit call unreachable, retries/circuit breaker exhausted | `FAILED` (`ACCOUNT_SERVICE_UNAVAILABLE`) — terminal, not reconciled (unchanged Phase 2/3 gap) |
| 8 | **destination account blocklisted** | `COMPENSATION_REQUIRED` (`DESTINATION_ACCOUNT_BLOCKED`) → scheduler compensates — *new* |
| 9 | **Fraud Service unreachable checking the destination** | `COMPENSATION_REQUIRED` (`DESTINATION_FRAUD_SERVICE_UNAVAILABLE`) → scheduler retries the check — *new* |
| 10 | credit rejected | `COMPENSATION_REQUIRED` → scheduler compensates |
| 11 | credit call unreachable | `COMPENSATION_REQUIRED` → scheduler resolves |
| 12 | unexpected exception before debit | `FAILED` (`UNEXPECTED_ERROR`) |
| 13 | unexpected exception after debit | `COMPENSATION_REQUIRED` (`UNEXPECTED_ERROR`) |
| 14 | process crashes mid-request | stays `PENDING` → scheduler resolves |

Background scheduler resolution:

- **stale `PENDING`** (`reconcileDebit`, modified) → re-check source fraud: blocked → `FAILED`; unreachable → stays `PENDING`, retried next sweep; clear → (idempotent) debit attempt exactly as today (lands → promoted to `COMPENSATION_REQUIRED`; rejected → `FAILED`; unreachable → stays `PENDING`)
- **`COMPENSATION_REQUIRED`** (`reconcileCredit`, modified) → re-check destination fraud, unconditionally: blocked → compensate the source (as today's rejected-credit path); unreachable → stays `COMPENSATION_REQUIRED`, retried next sweep; clear → (idempotent) credit attempt exactly as today (lands → `COMPLETED`; rejected → compensate; unreachable → stays `COMPENSATION_REQUIRED`)

## Fraud Service

New Spring Boot module `fraud-service/` (port 8084, package `com.showcase.fraud`), stateless — no database, no Kafka.

- `GET /fraud-check?accountId={uuid}` → `200 OK`, empty body, if not blocked; `422 application/problem+json` with `code: ACCOUNT_BLOCKED` if blocked; `400 VALIDATION_FAILED` for a missing/malformed `accountId`.
- Blocklist is static config: `fraud.blocklist.account-ids` (a `List<UUID>`), a `@ConfigurationProperties` record following the `CompensationProperties` pattern. No admin API, no persistence — editing the list means editing config and restarting, which is fine for a demo/showcase service.
- No `Idempotency-Key` — a fraud check is a pure read with no side effects, safe to call any number of times.
- Actuator health endpoint, Docker Compose healthcheck, Dockerfile matching the other services' two-stage build.

## Transfer Service Changes

- **`client/FraudClient.java`** (new) — mirrors `AccountClient`'s two-exception pattern: `FraudRejectedException` (business rejection — `ACCOUNT_BLOCKED`, never retried, never trips the breaker) / `FraudServiceUnavailableException` (infra trouble — retried). One method: `void check(UUID accountId)`, called with `fromAccountId` before debit and `toAccountId` before credit. Wrapped in `@CircuitBreaker(name = "fraudService")` + `@Retry(name = "fraudService", fallbackMethod = ...)`, same `resilience4j.*` shape as the existing `accountService` instance in `application.yml`. Same "verified live, not mocked" caution as `AccountClient`'s javadoc — the fallback-method passthrough behavior needs the real annotated bean exercised, not a mock, per the lesson Phase 3 already learned once for `AccountClient`.
- **`client/FraudClientProperties.java`** (new) — base URL, timeouts, mirroring `AccountClientProperties`.
- **`domain/TransferFailureCode.java`** (modified) — add `SOURCE_ACCOUNT_BLOCKED`, `SOURCE_FRAUD_SERVICE_UNAVAILABLE`, `DESTINATION_ACCOUNT_BLOCKED`, `DESTINATION_FRAUD_SERVICE_UNAVAILABLE`.
- **`domain/TransferStatus.java`** (modified, javadoc only) — `FAILED` and `COMPENSATION_REQUIRED` doc comments gain a sentence naming the fraud-block case as another cause; no enum values change.
- **`service/TransferService.java`** (modified) — two new steps in `execute()`: source fraud-check after the existence pre-validate and before debit (`fail()` on block/unavailable); destination fraud-check after debit and before credit (`strand()` on block/unavailable).
- **`service/CompensationScheduler.java`** (modified) — gains a `FraudClient` dependency; `reconcileDebit()` gains the source fraud gate described above; `reconcileCredit()` gains the unconditional destination fraud gate described above.
- **`application.yml`** (modified) — `fraud-client.*` (base URL etc.), `resilience4j.circuitbreaker.instances.fraudService` / `resilience4j.retry.instances.fraudService`.

## Testing

- Fraud Service: unit tests for the blocklist rule (hit/miss), a thin controller test for the two response shapes.
- Transfer Service: `TransferService` unit tests for all four new branches (source blocked/unavailable, destination blocked/unavailable) with a mocked `FraudClient`; `CompensationSchedulerTest` additions for the new fraud gates in both sweeps; an integration test exercising the real Resilience4j-wrapped `FraudClient` bean (not mocked) for the fallback-passthrough behavior, matching the `AccountClient` precedent.
- End-to-end: this phase makes the Phase 7-planned "fraud-rejection-with-compensation" E2E case (design doc §6) exercisable for the first time — not built now, but no longer blocked.

## Scope Boundary

**In scope:** Fraud Service module; `FraudClient` + resilience wiring in Transfer; the two new saga steps; the two scheduler-sweep fraud gates; new `TransferFailureCode` values; Docker Compose wiring for the new service; README "All saga outcomes" section (drafted during this brainstorm, needs the fraud rows folded in once implemented — see the table above); roadmap.md status/scope row.

**Explicitly out of scope** — named follow-ups, not oversights:

| Deferred | Why not now | Lands in |
|---|---|---|
| Amount/velocity-threshold rules | Reconsidered during brainstorming in favor of a blocklist-only rule (see Design Decisions) | Not currently planned; revisit only if a concrete need emerges |
| `docs/microservices-showcase-design.md` §4 prose update (single generic "Fraud rejects" framing → the two-position source/destination design) | Real doc-sync work, but separate from the code change itself | This phase, as its own task, before closing |
| `docs/microservices-showcase-design.md` §4/§8 stale `TransferCompleted`/`TransferFailed` event-pair naming (carried over from Phase 4's review) | Bundled here per roadmap.md's note ("give it a full pass alongside Phase 5's design doc updates") | This phase, same doc-sync task |
| Fraud Service admin API / persisted blocklist | Static config is sufficient for a demo; no requirement for runtime edits | Not currently planned |
| Auth / JWT | No security on any service yet | Phase 6 |
| Trace propagation across the new sync hop | No OTel Collector yet | Phase 7 |
| `transfers fraud-rejected` business-metric counter | Full business metrics land with Prometheus/Grafana wiring | Phase 7 |
