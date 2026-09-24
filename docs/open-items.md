# Open Items

A single list of every open item mentioned anywhere in `docs/`, each one checked against the
code on `master` as of 2026-09-22. The phase docs remain the source of detail. This file only
collects the items and points back to where each one is discussed. Remove an item once it
lands, and add new ones as phases defer them.

## 1. Planned phases and features

| # | Item | Source |
|---|---|---|
| 1 | **Phase 10: end-to-end saga tests**, including fault injection against the real services. | `roadmap.md` |
| 2 | **Boot 4.x migration.** The current 3.5.16 is past OSS end of life, which the phase doc calls "a real gap, not deferred lightly". | `phase-8-observability.md` Scope Boundary |
| 3 | **Flyway/Liquibase instead of `ddl-auto: update`**, deferred three times and marked "its own phase". | `phase-1-foundation-account-service.md` Deferred / Known Gaps; phases 2–4 Scope Boundary |
| 4 | **Release automation**: versions from git tags, an image registry, a changelog. | `phase-8b-version-visibility.md` Scope Boundary |
| 5 | **Automated browser tests for the Bank UI** (e.g. Playwright). | `phase-9-bank-ui.md` Scope Boundary |

## 2. Minor tech debt

| # | Item | Source | Status in code |
|---|---|---|---|
| 6 | `AccountControllerIT`'s same-key concurrency test is `@Disabled`. The root cause (a second serialization factor on CI) is unknown, and a rewrite is recommended. `returns409ForConcurrentUpdateConflict` may carry the same risk. | `investigation-account-controller-it-concurrency-flake.md` | Open |

## 3. Deliberately not planned

These were decided against rather than postponed. They only come back if someone revisits the
decision.

| Area | Items | Source |
|---|---|---|
| Fraud | Amount/velocity rules; an admin API or a persisted blocklist | `phase-5-fraud-service.md` |
| Gateway | Rate limiting, CORS and logging filters; a Gateway-level resilience layer | `phase-6-api-gateway.md` |
| Auth | Keycloak Authorization Services; `oauth2Login` for Swagger; silent token refresh; email/password-reset flows; admin views in the UI | `phase-7-auth-keycloak-jwt.md`, `phase-7b-account-ownership-authorization.md`, `phase-9-bank-ui.md` |
| Transfer outbox | Pruning published `outbox_events` rows (a retention policy); the table grows forever, which is irrelevant at demo scale, and the index on `publishedAt` keeps the poll query cheap regardless | `phase-4-outbox-kafka-notification.md` Final Review |
| Notification | A distinct event type/topic for `COMPENSATION_FAILED` (today it publishes `TRANSFER_FAILED`, like a clean failure). Notifications only log and will not become customer-facing in this demo, and the payload's `status` field already tells the outcomes apart | `phase-4-outbox-kafka-notification.md` Final Review |
| Kafka events | A `schemaVersion` on outbox event payloads. Notification logs the raw payload without parsing it, and its one producer and one consumer ship together from one repo, so the field would have no reader | `phase-8b-version-visibility.md` Scope Boundary |
| Saga | A read-only (or void-if-absent) operation lookup on Account to settle a stale-`PENDING` row whose source has since been blocklisted. Such a row stays `PENDING`, retried every sweep, until the block is lifted; reaching it needs a Fraud reconfiguration and restart | `microservices-showcase-design.md` §4, `README.md` |
| Observability and platform | Alerting; trace-to-metrics exemplars; log aggregation; a Resilience4j TimeLimiter; multi-account per user | `phase-3-resilience-compensation-idempotency.md`, `phase-8-observability.md`, `phase-9-bank-ui.md` |
