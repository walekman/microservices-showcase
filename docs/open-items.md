# Open Items

A single list of every open item mentioned anywhere in `docs/`, each one checked against the
code on `master` as of 2026-09-22. The phase docs remain the source of detail. This file only
collects the items and points back to where each one is discussed. Remove an item once it
lands, and add new ones as phases defer them.

## 1. Correctness and security gaps

| # | Item | Source | Status in code |
|---|---|---|---|
| 2 | **Any customer can mint money directly.** Any authenticated `customer` can call `POST /accounts/{id}/credit` on port 8081, bypassing the saga. Closing it means Transfer uses its own machine identity for the credit leg instead of relaying the customer's token. | `phase-7-auth-keycloak-jwt.md` Known Gaps, `phase-7b-account-ownership-authorization.md` Design Decisions | Open; "not currently planned" |
| 3 | **`showcase-ui` still has a wildcard redirect URI** (`http://localhost:*`), a leftover `http://localhost:3000/*`, and `webOrigins: ["*"]`. Phase 7 deferred this to "the real login UI phase", but Phase 9 shipped the UI on port 8090 without tightening it, so it is now a live Authorization Code flow on a public client. | `phase-7-auth-keycloak-jwt.md` Scope Boundary | Open, and the stated trigger has passed |
| 5 | **`COMPENSATION_FAILED` publishes the same `TRANSFER_FAILED` event type as a clean failure.** The payload's `status` still distinguishes them, but the phase doc asks for a revisit before notifications become customer-facing. | `phase-4-outbox-kafka-notification.md` Final Review | Open (Notification still only logs) |
| 19 | **The stale-`PENDING` sweep can settle an unknown debit as `FAILED` without learning its outcome.** `CompensationScheduler.reconcileDebit` re-screens the source first, and a blocklisted source marks the row `FAILED` without replaying the debit key. Until now only a crashed saga reached this path; a live debit with an unknown outcome now does too. Resolving it needs a read-only operation lookup on Account, since replaying the debit against a blocked account would move the money. | `microservices-showcase-design.md` §4, `README.md` | Open |
| 20 | **Rows written before the unknown-debit fix are not migrated.** A database from before it can still hold transfers recorded `FAILED` + `ACCOUNT_SERVICE_UNAVAILABLE` on the debit leg. No sweep revisits them, and the debit may have committed. `docker compose down -v` clears them locally. | `microservices-showcase-design.md` §4 | Open (legacy data only) |

## 2. Planned phases and features

| # | Item | Source |
|---|---|---|
| 6 | **Phase 10: end-to-end saga tests**, including fault injection against the real services. | `roadmap.md` |
| 7 | **Boot 4.x migration.** The current 3.5.16 is past OSS end of life, which the phase doc calls "a real gap, not deferred lightly". | `phase-8-observability.md` Scope Boundary |
| 8 | **Flyway/Liquibase instead of `ddl-auto: update`**, deferred three times and marked "its own phase". | `phase-1-foundation-account-service.md` Deferred / Known Gaps; phases 2–4 Scope Boundary |
| 9 | **Kafka event `schemaVersion` on outbox payloads.** | `phase-8b-version-visibility.md` Scope Boundary |
| 10 | **Release automation**: versions from git tags, an image registry, a changelog. | `phase-8b-version-visibility.md` Scope Boundary |
| 11 | **Automated browser tests for the Bank UI** (e.g. Playwright). | `phase-9-bank-ui.md` Scope Boundary |

## 3. Minor tech debt

| # | Item | Source | Status in code |
|---|---|---|---|
| 14 | No explicit `NewTopic` beans; the partition count comes from the broker's auto-create default. | `phase-4-outbox-kafka-notification.md` Final Review | Still true |
| 15 | The `outbox_events` table is never pruned and grows forever. The phase doc waits for Flyway (#8) to carry a retention policy. | `phase-4-outbox-kafka-notification.md` Final Review | Still true |
| 16 | `AccountControllerIT`'s same-key concurrency test is `@Disabled`. The root cause (a second serialization factor on CI) is unknown, and a rewrite is recommended. `returns409ForConcurrentUpdateConflict` may carry the same risk. | `investigation-account-controller-it-concurrency-flake.md` | Open |
| 17 | `phase-8-observability.md` (Roadmap Changes) still says the design-doc edits are "Still pending — lands in Task 6". | `phase-8-observability.md` | Stale wording; the edits are done |
| 18 | `phase-4-outbox-kafka-notification.md`'s Final Review still lists the missing host-side Kafka listener as deferred, but `docker-compose.yml` now has it (`HOST://localhost:29092`). | `phase-4-outbox-kafka-notification.md` Final Review | Stale wording; already fixed in code |

## 4. Deliberately not planned

These were decided against rather than postponed. They only come back if someone revisits the
decision.

| Area | Items | Source |
|---|---|---|
| Fraud | Amount/velocity rules; an admin API or a persisted blocklist | `phase-5-fraud-service.md` |
| Gateway | Rate limiting, CORS and logging filters; a Gateway-level resilience layer | `phase-6-api-gateway.md` |
| Auth | Keycloak Authorization Services; `oauth2Login` for Swagger; silent token refresh; email/password-reset flows; admin views in the UI | `phase-7-auth-keycloak-jwt.md`, `phase-7b-account-ownership-authorization.md`, `phase-9-bank-ui.md` |
| Observability and platform | Alerting; trace-to-metrics exemplars; log aggregation; a Resilience4j TimeLimiter; multi-account per user | `phase-3-resilience-compensation-idempotency.md`, `phase-8-observability.md`, `phase-9-bank-ui.md` |
