# Multi-Currency Transfers + Redis-Cached FX Service (Phase 12)

**Goal:** Accounts hold a currency, and a transfer between accounts in different currencies
converts at a live exchange rate. The rate comes from a new FX Service, which fronts an
external rate provider (Frankfurter, which publishes ECB reference rates) with a Redis
cache. This is the project's first use of Redis. It exists because the data has the three
properties that make a shared cache worthwhile: it is expensive to fetch (a remote,
rate-limited public API), it is safe to reuse for a while (ECB publishes once a working
day), and every instance of every caller wants the same answer. The cache also carries
FX Service through a provider outage, by serving the last known rate marked as stale.

**Architecture:** A new `fx-service` (port 8085), shaped like `fraud-service`: stateless, no
database, validates JWTs itself, RFC 7807 errors. It is the only Redis client. Transfer
Service calls it synchronously during the saga, and the Bank UI calls it through the
Gateway to show a quote before sending. Transfer locks the rate onto the `Transfer` row
before any money moves, and every later step, including every compensator replay, uses the
locked values and never asks FX again.

**Tech Stack:** New `fx-service` module with `spring-boot-starter-data-redis` (Lettuce), and
`redis:7-alpine` in Compose. Tests use a Testcontainers `GenericContainer` for Redis and
`MockRestServiceServer` for the provider. All versions come from Boot's BOM or the existing
root `pom.xml` pins.

**Spec:** This document, brainstormed with the user on 2026-09-24. The task-by-task
implementation is added to it once this design is approved.

## Global Constraints

- Java 21; Spring Boot 3.5.16. Use `C:\dev\openjdk-21.0.2` as `JAVA_HOME`. (CLAUDE.md)
- One branch and one PR per task, each off the current `master`, stopping after each
  (CLAUDE.md, Git workflow).
- **The saga invariant holds unchanged.** A debit whose outcome is unknown stays `PENDING`,
  and is settled only by replaying `<transferId>:debit`. Nothing in this phase settles such a
  row as `FAILED`.
- **A replay never re-prices.** Every retry, reconciliation and compensation of a leg sends
  exactly the amount and currency locked on the row. A fresh rate would change the amount
  under an idempotency key Account has already recorded, and Account's ledger rejects that as
  a conflict.
- Schemas evolve through `ddl-auto: update`, as elsewhere (`open-items.md` §1). New columns
  are nullable at the database level for that reason. After pulling this phase, run
  `docker compose down -v` before `up`.

## Behaviour

- Each account has a currency, chosen when it is created and never changed. Supported
  currencies are a fixed, configured set: `EUR`, `USD`, `GBP`, `PLN`.
- A transfer's `amount` is always in the **source** account's currency, which is what gets
  debited. When the destination's currency differs, the destination is credited
  `creditAmount = amount × rate`, rounded `HALF_EVEN` to 2 decimal places. When the
  currencies match, the rate is `1` and FX Service is not called.
- The transfer's response, history, and outbox event carry `sourceCurrency`,
  `destinationCurrency`, `rate`, `rateAsOf` and `creditAmount`.
- Before sending, the UI shows an **approximate** quote ("recipient gets ≈ €X"). The amount
  that counts is the one Transfer locks when the transfer is created; the UI says so, and the
  result screen shows the locked amount.
- If FX Service cannot supply a rate, a cross-currency transfer fails cleanly, with nothing
  moved, as `FX_SERVICE_UNAVAILABLE`. Same-currency transfers are unaffected.

## FX Service

### API

`GET /fx/rates?base=PLN&quote=EUR` requires the `fx-reader` realm role and returns:

```json
{ "base": "PLN", "quote": "EUR", "rate": 0.2331, "asOf": "2026-09-23", "stale": false }
```

- `asOf` is the provider's publication date for the rate, not the time of the request.
- `stale: true` means the provider could not be reached and this is the last known rate.
- `base == quote` returns rate `1` without touching Redis or the provider.
- An unsupported or malformed currency returns `400 UNSUPPORTED_CURRENCY`.
- With no rate available at all, it returns `503 FX_RATE_UNAVAILABLE`.

### Provider

Frankfurter, called through `RestClient` with short connect and read timeouts (configurable).
Its base URL is configurable; Task 1 confirms the current endpoint. One call, `latest?base=<B>`
(restricted to the supported symbols), returns every rate for base `B`. The cache is
therefore keyed **per base currency**, not per pair: one provider call serves all three
quotes from that base.

### Cache

Hand-written cache-aside over `StringRedisTemplate`, with values stored as JSON
(`{asOf, rates{...}}`).

| Key | TTL | Purpose |
|---|---|---|
| `fx:rates:<base>` | 10 min (configurable) | Fresh rates. A hit is served directly. |
| `fx:rates:last-known:<base>` | 24 h (configurable) | Fallback while the provider is down. Rewritten on every successful fetch. |
| `fx:lock:<base>` | 5 s (`SET NX PX`) | Single flight: only one caller fetches from the provider on a miss. |

Read path:

1. `GET fx:rates:<base>`. On a hit, serve it.
2. On a miss, try `SET fx:lock:<base> NX PX 5000`.
   - **Lock won:** fetch from the provider, then write both rate keys, then release the lock
     (delete it only if it still holds this caller's token). Serve the fresh rates.
   - **Lock lost:** poll `fx:rates:<base>` for up to about 1 s. If it appears, serve it.
     Otherwise fall through to step 3.
3. If the provider fetch failed, or the wait timed out: serve `fx:rates:last-known:<base>`
   with `stale: true`. If that is missing too, return `503 FX_RATE_UNAVAILABLE`.

If Redis itself is unreachable, FX Service calls the provider directly on every request. It
logs a WARN and counts it; it does not fail the request. Redis is an optimisation here, never
a dependency.

### Observability

- `fx_cache_requests_total{result="hit|miss|stale|bypass"}`, where `bypass` means Redis was
  unreachable.
- `fx_provider_calls_total{outcome="success|failure"}`.
- Redis commands appear as spans in Tempo through Boot's Lettuce observation, with no code
  needed.
- A Grafana panel shows the cache hit ratio, stale serves, and provider call rate.

## Account Service changes

- `Account.currency`: an ISO 4217 code, `length = 3`, `updatable = false`. `POST /accounts`
  requires it and validates it against the supported set (`400` otherwise).
- `AccountResponse` and `AccountSummaryResponse` gain `currency`.
- `GET /accounts/exists/{id}` now returns `{"currency": "PLN"}` instead of an empty body.
  Transfer needs both accounts' currencies. A currency code discloses nothing a caller could
  misuse, unlike a balance or an owner.
- `debit`/`credit` bodies gain an optional `currency`. If present and different from the
  account's, the call is rejected with `422 CURRENCY_MISMATCH`, before anything is applied.
  This is defense in depth: no Transfer bug can credit an amount computed for one currency
  into an account held in another. If absent, the call proceeds as today, so a replay of a
  transfer created before this phase still works.
- Accounts created before this phase have a null currency and are read as `EUR`. This only
  matters for a stack that was not reset (`docker compose down -v`).

## Transfer Service changes

### Rate locking: the start of `TransferService.execute` is reordered

Today `execute` inserts a `PENDING` row first, then pre-validates. This phase moves the insert
after pre-validation and pricing, so **every `PENDING` row carries its conversion from the
moment it exists**:

1. Idempotent-replay lookup by `(initiatorId, idempotencyKey)`. Unchanged.
2. Build the `Transfer` in memory (the constructor guards, such as self-transfer, still throw
   before anything else).
3. Pre-validate both accounts through `accountExists`, which now returns each currency.
4. If the currencies differ, call `FxClient.rate(source, destination)`. Compute
   `creditAmount`; if it rounds to `0.00`, the transfer fails as `AMOUNT_TOO_SMALL`.
5. **One insert:** the row is saved either `FAILED` (from step 3 or 4, with nothing moved), or
   `PENDING` with `sourceCurrency`, `destinationCurrency`, `rate`, `rateAsOf` and
   `creditAmount` already set.
6. Source fraud check, debit (`amount`, `sourceCurrency`), destination fraud check, credit
   (`creditAmount`, `destinationCurrency`). Unchanged apart from the currency and credit
   amount.

The idempotency race still resolves at the insert in step 5. The unique constraint on
`(initiator_id, idempotency_key)` lets exactly one row in, and the loser becomes a replay of
the winner, as today. Steps 3 and 4 only read, so a loser has moved nothing.

`FxClient` mirrors `FraudClient`: `RestClient`, a Resilience4j circuit breaker and retry, and
the user's token relayed through the existing `AuthorizationPropagatingInterceptor`. On an
unreachable or erroring FX Service it throws `FxServiceUnavailableException`, which the saga
maps to `FX_SERVICE_UNAVAILABLE`. A `stale: true` rate is accepted; its `rateAsOf` is recorded
on the row, so the age of the rate a transfer used is always visible.

### `CompensationScheduler`

Every replay reads the locked values and never calls FX Service:

| Step | Account | Amount | Currency |
|---|---|---|---|
| `reconcileDebit` (`<id>:debit`) | source | `amount` | `sourceCurrency` |
| `reconcileCredit` (`<id>:credit`) | destination | `creditAmount` | `destinationCurrency` |
| `compensateSource` (`<id>:compensate`) | source | `amount` | `sourceCurrency` |

A row created before this phase has null conversion fields. It is treated as same-currency,
with `creditAmount = amount`, and its calls are sent without `currency`, exactly as before.

### Events and Notification

`TransferSaveService.TransferEventPayload` gains `sourceCurrency`, `destinationCurrency`,
`rate` and `creditAmount`. Notification's `TransferEvent` record and `Notification` entity
mirror them as nullable fields. The change is backward compatible in both directions: Spring
Kafka's `JsonDeserializer` ignores properties it does not know, so either service can deploy
first. An older event simply stores nulls. `NotificationService` still compares only
`status` when deciding whether a redelivery conflicts.

## Gateway, Keycloak, Compose, UI

- **Gateway:** one new route, `GET /fx/rates` to `fx-service`, in `GatewayRoutesConfig`.
- **Keycloak** (`docker/keycloak/showcase-realm.json`): a new realm role, `fx-reader`, added
  to the `customer` composite. The `transfer-service` client does **not** get it. Only the
  live saga calls FX, and it always carries the user's token; the compensator never calls
  FX. Least privilege comes free with the rate lock.
- **Compose:**
  - `showcase-redis` (`redis:7-alpine`, `--save "" --appendonly no`, a `redis-cli ping`
    healthcheck, no volume)
  - `fx-service` (the `GIT_SHA` build arg, an Actuator healthcheck, `depends_on` Redis and
    Keycloak)
  - Prometheus scrapes `fx-service`
- **UI** (`web-ui/`):
  - a currency picker when the account is provisioned after registration (seed 1000.00 in that
    currency)
  - balances show their currency
  - the transfer form, once the recipient's `/summary` shows a different currency, fetches
    `/fx/rates` and shows "≈ X, rate R as of D", with a "stale" badge when relevant
  - the result screen and history show the locked `creditAmount` and currencies

## Design Decisions

- **Why FX rates, and not another Redis use case.** Brainstorming rejected four alternatives:
  - *Caching existing Account reads* (`/summary`, `/exists`): the data never changes, and
    each service runs as a single instance, so a cache would be decoration.
  - *Pay by username*: resolving usernames lets any logged-in user test which usernames
    exist, and a login username is half a credential.
  - *Caching Fraud verdicts*: Fraud checks an in-memory set, so a Redis round trip saves
    nothing. A cached "allowed" verdict outlives a new block, and using one while Fraud is
    down would turn today's fail-closed screen into a fail-open one.
  - *Gateway rate limiting*: deliberately not planned (`open-items.md` §2), and it is a
    distributed counter rather than a cache.
- **A separate service, not FX inside Transfer.** Transfer already orchestrates two
  downstream services. FX has its own dependency (the provider), its own storage (Redis) and
  its own consumer (the UI's quote), so it gets its own boundary, and Redis has exactly one
  owner.
- **Hand-written cache-aside, not `@Cacheable`.** Spring's cache abstraction handles
  hit/miss/TTL, but has no notion of serving a last-known value when the loader fails, and its
  `sync = true` locks only within one JVM. The Redis lock is what makes the single flight hold
  across instances.
- **Cache keyed per base currency.** The provider returns every rate for a base in one call,
  so caching per pair would call it up to three times for data it had already sent.
- **One insert with the rate already set, rather than locking the rate in a second save.**
  With two saves, a crash between them would leave a `PENDING` row with no rate for the
  stale-`PENDING` sweep to find. That sweep replays the debit, and would then have no credit
  amount to finish with. With one insert, that state cannot exist.
- **Stale rates are accepted for money movement, and recorded.** ECB rates move once a day,
  so a last-known rate up to 24 h old is a reasonable demo trade-off against failing every
  cross-currency transfer during a provider outage. `rateAsOf` on the row makes each use
  auditable.
- **`CURRENCY_MISMATCH` on Account.** Account cannot check that a conversion is correct, but it
  can refuse a credit labelled with the wrong currency. That turns a Transfer pricing bug into
  a clean rejection instead of silently wrong money.

## Scope Boundary

- **Redis high availability and persistence:** a single Redis node with persistence off. It is
  a cache, and losing it only costs provider calls.
- **Converting balances for display** (e.g. "total ≈ €X"): the rates endpoint makes it easy
  later, but nothing asks for it now.
- **Rate margins or fees:** transfers convert at the reference rate exactly.
- **Quote locking** (holding the displayed quote for N seconds and honouring it at send time):
  the UI quote is advisory; the amount that counts is the one Transfer locks.
- **Changing an account's currency, or accounts with several currencies:** one currency per
  account, fixed at creation.
- **An offline provider stub:** without internet access, cross-currency transfers fail as
  `FX_SERVICE_UNAVAILABLE` once the cache is empty. Tests never touch the real provider.

## Delivery

| Task | Branch | Scope |
|---|---|---|
| 1 | `feature/phase-12-task-1-fx-service` | `fx-service` module, the Redis cache, the `fx-reader` role, Compose (Redis and `fx-service`), Prometheus scrape |
| 2 | `feature/phase-12-task-2-account-currency` | `Account.currency`, the `exists` body, `CURRENCY_MISMATCH` |
| 3 | `feature/phase-12-task-3-saga-fx` | `FxClient`, the reordered `execute`, the `Transfer` columns, `CompensationScheduler`, the event payload, Notification's mirror |
| 4 | `feature/phase-12-task-4-ui-gateway` | Gateway route, UI, Grafana panel |

Docs updated alongside:
- `docs/roadmap.md`: the Phase 12 row
- `docs/microservices-showcase-design.md`: the new service, Redis, and rate locking in §4
- the service list in `CLAUDE.md`
- `docs/open-items.md`: the Scope Boundary items above

## Testing

- **FX Service** (Testcontainers Redis, `MockRestServiceServer` for the provider):
  - a hit
  - a miss that writes both keys
  - fresh-key expiry leading to a refetch
  - a provider failure served from last-known with `stale: true`
  - `503` when nothing is cached
  - Redis unreachable, still answered from the provider (`bypass`)
  - N concurrent misses make exactly 1 provider call
  - `base == quote` makes no calls
  - `400` on an unsupported currency
  - security 401/403, and `OpenApiDocsIT`
- **Account:**
  - `currency` required and validated on create
  - `exists` returns the currency
  - `422 CURRENCY_MISMATCH` on debit and on credit, with the balance and ledger untouched
  - a debit/credit without `currency` still applies
- **Transfer:**
  - a cross-currency transfer completes with the locked `creditAmount`, and the debit and
    credit each carry the right currency
  - an FX outage produces a `FAILED` row with `FX_SERVICE_UNAVAILABLE`, and no debit was sent
  - `HALF_EVEN` rounding, and `AMOUNT_TOO_SMALL`
  - an idempotent replay never calls FX
  - the concurrent-insert race still resolves to a replay
  - all three compensator paths send the amounts and currencies in the table above, and
    never call FX
  - a row from before this phase replays without `currency`
- **Notification:** an event with the new fields and one without them are both stored.
