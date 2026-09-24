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
  transfer created before this phase still works. The check applies only to an operation Account
  has **not yet applied**. A replay of an idempotency key already in the `AccountOperation` ledger
  is answered from the ledger, as today, without a currency check: rejecting it would make the
  compensator read "rejected" for money that already moved.
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

---

# Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. If something in this plan is wrong or impossible, report it rather than silently working around it — read it critically rather than transcribing it.

**Goal:** Build the design above in four independently shippable tasks, each its own branch and PR.

**Architecture:** Task 1 adds `fx-service` and Redis on their own, with nothing calling them yet. Task 2 gives
accounts a currency and makes Account refuse a mislabelled debit/credit. Transfer ignores the new
`exists` body until Task 3, so the stack keeps working in between. Task 3 wires FX into the saga,
the compensator, the outbox event and Notification. Task 4 exposes FX through the Gateway, shows
quotes in the UI, adds the Grafana panel and finishes the docs.

**Tech Stack:** as in the header of this document.

**Spec:** the design sections above.

## Review Focus

Things the spec implies but no happy-path test would exercise, most likely first. Each has a
test in the task named.

1. **A replay of an already-applied Account operation carrying a different currency must still
   succeed** (Task 2). If Account checked the currency before its idempotency ledger, a
   compensator replay after a Transfer bug would read an applied debit as rejected, and
   `reconcileDebit` would record `FAILED` for money that already moved.
2. **A PENDING row must never exist without its conversion** (Task 3). The insert must happen
   after pricing; a test snapshots `creditAmount` at insert time.
3. **Legacy rows (null currencies) in the compensator** (Task 3). They must replay without
   `currency` and credit `amount`; the existing `CompensationSchedulerTest` cases become exactly
   these tests once their expectations carry a `null` currency.
4. **Currency codes arriving in lowercase or padded** (`" pln "`) at `/fx/rates` (Task 1). They are
   normalised, not rejected.
5. **A provider body missing the requested quote, or carrying a `null` rate** (Task 1). This
   is `503 FX_RATE_UNAVAILABLE`, never a 500 or an NPE.

## Before any task that runs Docker

Per `CLAUDE.md`, the coordinating session (never an implementer) brings down any other
worktree's `docker compose` stack before a task's live-verification step. Container names are
fixed, so two stacks collide.

## Task 1: FX Service + Redis

**Branch:** `feature/phase-12-task-1-fx-service`, off the current `master`.

**Files** (Java paths are under `fx-service/src/main/java/com/showcase/fx/` or `fx-service/src/test/java/com/showcase/fx/`):
- Modify: `pom.xml` (module list)
- Modify: `account-service/Dockerfile`, `transfer-service/Dockerfile`, `notification-service/Dockerfile`, `fraud-service/Dockerfile`, `gateway-service/Dockerfile` (one `COPY` line each)
- Create: `fx-service/pom.xml`, `fx-service/Dockerfile`, `fx-service/src/main/resources/application.yml`
- Create: `FxServiceApplication.java`
- Create (copied from `fraud-service`, package renamed): `config/BuildInfoMetricsConfig.java`, `config/JwtDecoderConfig.java`, `config/KeycloakProperties.java`, `config/KeycloakRealmRoleConverter.java`, `api/Problems.java`
- Create: `config/SecurityConfig.java`, `config/ProviderClientConfig.java`, `api/FxRateController.java`, `api/ApiExceptionHandler.java`
- Create: `service/FxProperties.java`, `service/RateTable.java`, `service/FxQuote.java`, `service/RateProvider.java`, `service/RateCache.java`, `service/FxRateService.java`, `service/UnsupportedCurrencyException.java`, `service/RateUnavailableException.java`, `service/ProviderUnavailableException.java`
- Test: `service/RateProviderTest.java`, `service/FxRateServiceTest.java`, `service/RateCacheIT.java`, `service/FxRateServiceIT.java`, `service/FxRateServiceRedisDownIT.java`, `api/FxRateControllerTest.java`, `support/FakeRateProvider.java`, `BuildInfoIT.java`, `OpenApiDocsIT.java`
- Modify: `docker/keycloak/showcase-realm.json`, `docker-compose.yml`, `docker/prometheus/prometheus.yml`, `CLAUDE.md`, `docs/roadmap.md`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `GET /fx/rates?base=<ISO>&quote=<ISO>` requiring the `fx-reader` realm role and
  returning `{"base":"PLN","quote":"EUR","rate":0.22819,"asOf":"2026-09-23","stale":false}`.
  Errors are `400 UNSUPPORTED_CURRENCY`, `400 VALIDATION_FAILED` and `503 FX_RATE_UNAVAILABLE`.
  It runs in Compose as `fx-service:8085`. Task 3's `FxClient` and Task 4's Gateway route depend
  on exactly this.

- [ ] **Step 1: Register the module and teach every image about it**

In the root `pom.xml`, add `<module>fx-service</module>` after `<module>gateway-service</module>`.

Every Dockerfile copies every module's `pom.xml` before `dependency:go-offline`, because Maven
reads the whole reactor. A missing one breaks every image build. In each of the five existing
Dockerfiles, directly after the line `COPY gateway-service/pom.xml gateway-service/pom.xml`,
add:

```dockerfile
COPY fx-service/pom.xml fx-service/pom.xml
```

- [ ] **Step 2: Create `fx-service/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.showcase</groupId>
    <artifactId>microservices-showcase</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>fx-service</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
      <version>${springdoc-openapi.version}</version>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <executions>
          <execution>
            <goals>
              <goal>build-info</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/*Test.java</include>
            <include>**/*Tests.java</include>
            <include>**/*IT.java</include>
            <include>**/*ITTest.java</include>
          </includes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Create `fx-service/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY fraud-service/pom.xml fraud-service/pom.xml
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY fx-service/pom.xml fx-service/pom.xml
RUN chmod +x mvnw && ./mvnw -pl fx-service -am dependency:go-offline -B
COPY fx-service/src fx-service/src
RUN ./mvnw -pl fx-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/fx-service/target/fx-service-*.jar app.jar
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}
LABEL org.opencontainers.image.revision=${GIT_SHA}
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 4: Create `fx-service/src/main/resources/application.yml`**

```yaml
server:
  port: 8085

logging:
  structured:
    format:
      console: logstash

spring:
  application:
    name: fx-service
  threads:
    virtual:
      enabled: true
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      # Redis is an optimisation for this service, never a dependency (docs/phase-12-fx-rates-redis-cache.md):
      # a dead or hung Redis must fail fast so FxRateService can go straight to the provider,
      # not park a request for Lettuce's 60s default command timeout.
      timeout: 500ms
      connect-timeout: 500ms

info:
  commit: ${GIT_SHA:unknown}

management:
  info:
    env:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
  endpoint:
    health:
      probes:
        enabled: true
  # Same reason as the Redis timeouts above: Boot's Redis health indicator would report this
  # service DOWN (and fail its Compose healthcheck) while it is still answering every request
  # from the provider.
  health:
    redis:
      enabled: false
  opentelemetry:
    resource-attributes:
      "service.version": "@project.version@"
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}

keycloak:
  issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/showcase}
  jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:8180/realms/showcase/protocol/openid-connect/certs}

fx:
  # Must match account-service's SupportedCurrency enum (Task 2). A currency Account accepts but
  # FX does not would fail every transfer out of it as FX_SERVICE_UNAVAILABLE.
  supported-currencies: EUR,USD,GBP,PLN
  fresh-ttl: 10m
  last-known-ttl: 24h
  lock-ttl: 5s
  lock-wait: 1s
  provider:
    base-url: ${FX_PROVIDER_URL:https://api.frankfurter.dev/v1}
    connect-timeout: 2s
    read-timeout: 3s
```

- [ ] **Step 5: Copy the shared boilerplate from `fraud-service`, and add the application and security config**

Copy these five files from `fraud-service/src/main/java/com/showcase/fraud/` into
`fx-service/src/main/java/com/showcase/fx/`. Change only the `package` line
(`com.showcase.fraud.…` → `com.showcase.fx.…`) and, in `KeycloakRealmRoleConverter`'s javadoc,
the example role list, which should mention `fx-reader`:

- `config/BuildInfoMetricsConfig.java`
- `config/JwtDecoderConfig.java`
- `config/KeycloakProperties.java`
- `config/KeycloakRealmRoleConverter.java`
- `api/Problems.java`

`FxServiceApplication.java`:

```java
package com.showcase.fx;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// See AccountServiceApplication's comment: a plain HTTP-bearer scheme so Swagger UI's
// Authorize button can attach a token obtained via the README's curl instructions.
@OpenAPIDefinition(
        info = @Info(
                title = "FX Service API",
                version = "v1",
                description = "Exchange rates from the ECB reference feed, cached in Redis."
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@SpringBootApplication
@ConfigurationPropertiesScan
public class FxServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxServiceApplication.class, args);
    }
}
```

`config/SecurityConfig.java`:

```java
package com.showcase.fx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Independently validates every request's JWT, like every other service (docs/phase-7-auth-keycloak-jwt.md).
 * "fx-reader" is held by customers through the "customer" composite: the Bank UI asks for a quote
 * with the user's token, and Transfer's live saga relays that same token. transfer-service's own
 * machine identity does not hold it, because nothing without a user token calls FX -- the
 * compensator replays locked amounts instead (docs/phase-12-fx-rates-redis-cache.md).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/fx/rates").hasAuthority("fx-reader")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
```

- [ ] **Step 6: Create the service's value types, properties and exceptions**

`service/FxProperties.java`:

```java
package com.showcase.fx.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "fx")
public record FxProperties(List<String> supportedCurrencies, Duration freshTtl, Duration lastKnownTtl,
                           Duration lockTtl, Duration lockWait, Provider provider) {

    public FxProperties {
        supportedCurrencies = (supportedCurrencies != null) ? List.copyOf(supportedCurrencies) : List.of();
        freshTtl = (freshTtl != null) ? freshTtl : Duration.ofMinutes(10);
        lastKnownTtl = (lastKnownTtl != null) ? lastKnownTtl : Duration.ofHours(24);
        lockTtl = (lockTtl != null) ? lockTtl : Duration.ofSeconds(5);
        lockWait = (lockWait != null) ? lockWait : Duration.ofSeconds(1);
        provider = (provider != null) ? provider : new Provider(null, null, null);
    }

    public record Provider(String baseUrl, Duration connectTimeout, Duration readTimeout) {

        public Provider {
            baseUrl = (baseUrl != null) ? baseUrl : "https://api.frankfurter.dev/v1";
            connectTimeout = (connectTimeout != null) ? connectTimeout : Duration.ofSeconds(2);
            readTimeout = (readTimeout != null) ? readTimeout : Duration.ofSeconds(3);
        }
    }
}
```

`service/RateTable.java`:

```java
package com.showcase.fx.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Every rate the provider published for one base currency on one day. This is the unit the
 * provider returns and the unit Redis caches (one JSON value per base), so a single provider call
 * serves every quote from that base.
 */
public record RateTable(String base, LocalDate asOf, Map<String, BigDecimal> rates) {
}
```

`service/FxQuote.java`:

```java
package com.showcase.fx.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One rate, as GET /fx/rates returns it. asOf is the provider's publication date, not the request
 * time; stale means the provider was unreachable and this is the last rate it gave.
 */
public record FxQuote(String base, String quote, BigDecimal rate, LocalDate asOf, boolean stale) {
}
```

`service/UnsupportedCurrencyException.java`:

```java
package com.showcase.fx.service;

public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String currency) {
        super("Unsupported currency: " + currency);
    }
}
```

`service/RateUnavailableException.java`:

```java
package com.showcase.fx.service;

/** No rate can be given: the provider is unreachable and Redis holds nothing to fall back on. */
public class RateUnavailableException extends RuntimeException {

    public RateUnavailableException(String message) {
        super(message);
    }

    public RateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`service/ProviderUnavailableException.java`:

```java
package com.showcase.fx.service;

/** The external rate provider failed, timed out, or returned something unusable. Internal to this service. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 7: Write the failing `RateProviderTest`**

`service/RateProviderTest.java`:

```java
package com.showcase.fx.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RateProviderTest {

    private static final String BASE_URL = "http://provider.test";
    private static final String PLN_URL = BASE_URL + "/latest?base=PLN&symbols=EUR,USD,GBP";

    private MockRestServiceServer server;
    private RateProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new RateProvider(builder.build(), List.of("EUR", "USD", "GBP", "PLN"));
    }

    @Test
    void fetchesEveryOtherSupportedCurrencyForTheBaseInOneCall() {
        // The body is the real shape api.frankfurter.dev/v1 returned on 2026-09-24.
        server.expect(requestTo(PLN_URL))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"PLN","date":"2026-09-24","rates":{"EUR":0.22819,"GBP":0.19621,"USD":0.25938}}
                        """, MediaType.APPLICATION_JSON));

        RateTable table = provider.fetch("PLN");

        assertThat(table.base()).isEqualTo("PLN");
        assertThat(table.asOf()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(table.rates()).containsOnlyKeys("EUR", "GBP", "USD");
        assertThat(table.rates().get("EUR")).isEqualByComparingTo("0.22819");
        server.verify();
    }

    @Test
    void aServerErrorIsProviderUnavailable() {
        server.expect(requestTo(PLN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aConnectionFailureIsProviderUnavailable() {
        server.expect(requestTo(PLN_URL)).andRespond(withException(new ConnectException("connection refused")));

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aBodyForADifferentBaseIsProviderUnavailable() {
        server.expect(requestTo(PLN_URL))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"EUR","date":"2026-09-24","rates":{"PLN":4.38}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aNullRateIsProviderUnavailableNotAnNpe() {
        server.expect(requestTo(PLN_URL))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"PLN","date":"2026-09-24","rates":{"EUR":null}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }
}
```

Run: `./mvnw -pl fx-service -Dtest=RateProviderTest test`
Expected: compilation FAILURE, because `RateProvider` does not exist yet.

- [ ] **Step 8: Implement `RateProvider` and its wiring**

`service/RateProvider.java`:

```java
package com.showcase.fx.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches ECB reference rates from Frankfurter (https://frankfurter.dev). One call per base
 * currency returns the rates to every other supported currency. Every failure -- a non-2xx, a
 * timeout, a body that is not what was asked for -- collapses onto ProviderUnavailableException,
 * so FxRateService only ever has to decide "fresh rates" or "fall back".
 */
public class RateProvider {

    private final RestClient restClient;
    private final List<String> supportedCurrencies;

    public RateProvider(RestClient restClient, List<String> supportedCurrencies) {
        this.restClient = restClient;
        this.supportedCurrencies = List.copyOf(supportedCurrencies);
    }

    public RateTable fetch(String base) {
        String symbols = supportedCurrencies.stream()
                .filter(currency -> !currency.equals(base))
                .collect(Collectors.joining(","));
        FrankfurterResponse body;
        try {
            body = restClient.get()
                    .uri("/latest?base={base}&symbols={symbols}", base, symbols)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw new ProviderUnavailableException(
                                "FX provider returned " + response.getStatusCode().value());
                    })
                    .body(FrankfurterResponse.class);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("FX provider call failed: " + ex.getMessage(), ex);
        }
        if (body == null || body.date() == null || body.rates() == null || !base.equals(body.base())
                || body.rates().containsValue(null)) {
            throw new ProviderUnavailableException("FX provider returned an unusable body for base " + base);
        }
        return new RateTable(base, body.date(), Map.copyOf(body.rates()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrankfurterResponse(String base, LocalDate date, Map<String, BigDecimal> rates) {
    }
}
```

`config/ProviderClientConfig.java`:

```java
package com.showcase.fx.config;

import com.showcase.fx.service.FxProperties;
import com.showcase.fx.service.RateProvider;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// Same RestClient.Builder + explicit timeouts shape as transfer-service's FraudClientConfig. The
// injected builder carries Boot's observation instrumentation, so each provider call is a span.
@Configuration
public class ProviderClientConfig {

    @Bean
    public RateProvider rateProvider(RestClient.Builder builder, FxProperties properties) {
        RestClient restClient = builder
                .baseUrl(properties.provider().baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(properties.provider().connectTimeout())
                                .withReadTimeout(properties.provider().readTimeout())))
                .build();
        return new RateProvider(restClient, properties.supportedCurrencies());
    }
}
```

Run: `./mvnw -pl fx-service -Dtest=RateProviderTest test`
Expected: 5 tests PASS. If `fetchesEvery…` fails because the comma in `symbols` was encoded as
`%2C`, report it; don't change the expected URL to match. (Commas are legal in a query string,
and Spring should leave them alone.)

- [ ] **Step 9: Write the failing `FxRateServiceTest`**

`service/FxRateServiceTest.java`:

```java
package com.showcase.fx.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);
    private static final RateTable PLN_RATES = new RateTable("PLN", AS_OF, Map.of(
            "EUR", new BigDecimal("0.22819"),
            "USD", new BigDecimal("0.25938"),
            "GBP", new BigDecimal("0.19621")));

    @Mock
    private RateCache cache;
    @Mock
    private RateProvider provider;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FxRateService service;

    @BeforeEach
    void setUp() {
        // lockWait is short so the lock-loser tests don't slow the suite down.
        FxProperties properties = new FxProperties(List.of("EUR", "USD", "GBP", "PLN"),
                Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofSeconds(5), Duration.ofMillis(200), null);
        service = new FxRateService(cache, provider, properties, registry);
    }

    private double cacheRequests(String result) {
        return registry.counter("fx.cache.requests", "result", result).count();
    }

    private double providerCalls(String outcome) {
        return registry.counter("fx.provider.calls", "outcome", outcome).count();
    }

    @Test
    void theSameCurrencyIsRateOneWithoutTouchingTheCacheOrTheProvider() {
        FxQuote quote = service.quote("EUR", "EUR");

        assertThat(quote.rate()).isEqualByComparingTo("1");
        assertThat(quote.stale()).isFalse();
        verifyNoInteractions(cache, provider);
    }

    @Test
    void currencyCodesAreNormalisedNotRejected() {
        when(cache.getFresh("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote(" pln ", "eur");

        assertThat(quote.base()).isEqualTo("PLN");
        assertThat(quote.quote()).isEqualTo("EUR");
        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
    }

    @Test
    void anUnsupportedOrMissingCurrencyIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.quote("JPY", "EUR")).isInstanceOf(UnsupportedCurrencyException.class);
        assertThatThrownBy(() -> service.quote("PLN", null)).isInstanceOf(UnsupportedCurrencyException.class);
        verifyNoInteractions(cache, provider);
    }

    @Test
    void aFreshHitIsServedWithoutTheProvider() {
        when(cache.getFresh("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote).isEqualTo(new FxQuote("PLN", "EUR", new BigDecimal("0.22819"), AS_OF, false));
        verifyNoInteractions(provider);
        verify(cache, never()).tryLock(any());
        assertThat(cacheRequests("hit")).isEqualTo(1);
    }

    @Test
    void aMissFetchesThenCachesThenReleasesTheLock() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenReturn(PLN_RATES);

        FxQuote quote = service.quote("PLN", "USD");

        assertThat(quote.rate()).isEqualByComparingTo("0.25938");
        assertThat(quote.stale()).isFalse();
        InOrder inOrder = inOrder(provider, cache);
        inOrder.verify(provider).fetch("PLN");
        inOrder.verify(cache).put(PLN_RATES);
        inOrder.verify(cache).unlock("PLN", "token-1");
        assertThat(cacheRequests("miss")).isEqualTo(1);
        assertThat(providerCalls("success")).isEqualTo(1);
    }

    @Test
    void aProviderFailureServesTheLastKnownRatesMarkedStale() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenThrow(new ProviderUnavailableException("provider down"));
        when(cache.getLastKnown("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.stale()).isTrue();
        assertThat(quote.asOf()).isEqualTo(AS_OF);
        verify(cache, never()).put(any());
        verify(cache).unlock("PLN", "token-1");
        assertThat(cacheRequests("stale")).isEqualTo(1);
        assertThat(providerCalls("failure")).isEqualTo(1);
    }

    @Test
    void aProviderFailureWithNothingCachedIsUnavailable() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenThrow(new ProviderUnavailableException("provider down"));
        when(cache.getLastKnown("PLN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.quote("PLN", "EUR")).isInstanceOf(RateUnavailableException.class);
        verify(cache).unlock("PLN", "token-1");
    }

    @Test
    void losingTheLockWaitsForTheWinnersRatesInsteadOfCallingTheProvider() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty(), Optional.empty(), Optional.of(PLN_RATES));
        when(cache.tryLock("PLN")).thenReturn(Optional.empty());

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.stale()).isFalse();
        verifyNoInteractions(provider);
        verify(cache, never()).unlock(any(), any());
    }

    @Test
    void losingTheLockAndTimingOutFallsBackToTheLastKnownRates() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.empty());
        when(cache.getLastKnown("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.stale()).isTrue();
        verifyNoInteractions(provider);
    }

    @Test
    void redisDownBypassesTheCacheAndAsksTheProvider() {
        when(cache.getFresh("PLN")).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(provider.fetch("PLN")).thenReturn(PLN_RATES);

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
        assertThat(quote.stale()).isFalse();
        assertThat(cacheRequests("bypass")).isEqualTo(1);
    }

    @Test
    void redisDownAndTheProviderDownIsUnavailable() {
        when(cache.getFresh("PLN")).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(provider.fetch("PLN")).thenThrow(new ProviderUnavailableException("provider down"));

        assertThatThrownBy(() -> service.quote("PLN", "EUR")).isInstanceOf(RateUnavailableException.class);
    }

    @Test
    void aFailedCacheWriteStillServesTheRatesAlreadyFetched() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenReturn(PLN_RATES);
        doThrow(new RedisConnectionFailureException("gone")).when(cache).put(PLN_RATES);

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
        verify(provider, times(1)).fetch("PLN");
    }

    @Test
    void aQuoteMissingFromTheProvidersDataIsUnavailableNotAnNpe() {
        RateTable partial = new RateTable("PLN", AS_OF, Map.of("EUR", new BigDecimal("0.22819")));
        when(cache.getFresh("PLN")).thenReturn(Optional.of(partial));

        assertThatThrownBy(() -> service.quote("PLN", "GBP")).isInstanceOf(RateUnavailableException.class);
    }
}
```

Run: `./mvnw -pl fx-service -Dtest=FxRateServiceTest test`
Expected: compilation FAILURE, because `RateCache` and `FxRateService` do not exist yet.

- [ ] **Step 10: Implement `RateCache` and `FxRateService`**

`service/RateCache.java`:

```java
package com.showcase.fx.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Redis side of FX Service's cache-aside. Three keys per base currency (see
 * docs/phase-12-fx-rates-redis-cache.md, "Cache"): the fresh rates, a longer-lived last-known copy,
 * and a short lock that lets exactly one caller -- across every instance -- fetch from the
 * provider on a miss.
 *
 * <p>Redis failures are NOT caught here: they surface as Spring's DataAccessException, and
 * FxRateService decides what a dead Redis means (go to the provider directly).
 */
@Component
public class RateCache {

    static final String FRESH_PREFIX = "fx:rates:";
    static final String LAST_KNOWN_PREFIX = "fx:rates:last-known:";
    static final String LOCK_PREFIX = "fx:lock:";

    private static final Logger log = LoggerFactory.getLogger(RateCache.class);

    // Compare-and-delete in one atomic step. A plain DEL could remove a lock that already expired
    // and was taken by another caller -- whose fetch would then be unprotected.
    private static final RedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FxProperties properties;

    public RateCache(StringRedisTemplate redis, ObjectMapper objectMapper, FxProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<RateTable> getFresh(String base) {
        return read(FRESH_PREFIX + base);
    }

    public Optional<RateTable> getLastKnown(String base) {
        return read(LAST_KNOWN_PREFIX + base);
    }

    public void put(RateTable table) {
        String json = write(table);
        redis.opsForValue().set(FRESH_PREFIX + table.base(), json, properties.freshTtl());
        redis.opsForValue().set(LAST_KNOWN_PREFIX + table.base(), json, properties.lastKnownTtl());
    }

    /** The token to release the lock with, or empty if another caller already holds it. */
    public Optional<String> tryLock(String base) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_PREFIX + base, token, properties.lockTtl());
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    public void unlock(String base, String token) {
        redis.execute(RELEASE_LOCK, List.of(LOCK_PREFIX + base), token);
    }

    private Optional<RateTable> read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, RateTable.class));
        } catch (JsonProcessingException unreadable) {
            // Treated as absent rather than as an error: the next successful fetch overwrites it.
            log.warn("Ignoring unreadable FX cache entry {}: {}", key, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private String write(RateTable table) {
        try {
            return objectMapper.writeValueAsString(table);
        } catch (JsonProcessingException impossible) {
            // A String, a LocalDate and a Map<String, BigDecimal> -- Boot's ObjectMapper
            // (JavaTimeModule registered) cannot fail on this.
            throw new IllegalStateException("Failed to serialize rates for " + table.base(), impossible);
        }
    }
}
```

`service/FxRateService.java`:

```java
package com.showcase.fx.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers GET /fx/rates through the Redis cache in front of the rate provider. The read path and
 * how it degrades are specified in docs/phase-12-fx-rates-redis-cache.md ("Cache"): fresh hit;
 * else one caller fetches under a Redis lock while the others wait for its result; a provider
 * failure falls back to the last known rates, marked stale; a dead Redis is bypassed entirely.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final RateCache cache;
    private final RateProvider provider;
    private final FxProperties properties;
    private final Counter hits;
    private final Counter misses;
    private final Counter staleServes;
    private final Counter bypasses;
    private final Counter providerSuccesses;
    private final Counter providerFailures;

    public FxRateService(RateCache cache, RateProvider provider, FxProperties properties, MeterRegistry registry) {
        this.cache = cache;
        this.provider = provider;
        this.properties = properties;
        this.hits = cacheRequests(registry, "hit");
        this.misses = cacheRequests(registry, "miss");
        this.staleServes = cacheRequests(registry, "stale");
        this.bypasses = cacheRequests(registry, "bypass");
        this.providerSuccesses = providerCalls(registry, "success");
        this.providerFailures = providerCalls(registry, "failure");
    }

    private static Counter cacheRequests(MeterRegistry registry, String result) {
        return Counter.builder("fx.cache.requests").tag("result", result)
                .description("FX rate lookups by how the cache answered them").register(registry);
    }

    private static Counter providerCalls(MeterRegistry registry, String outcome) {
        return Counter.builder("fx.provider.calls").tag("outcome", outcome)
                .description("Calls to the external FX rate provider").register(registry);
    }

    public FxQuote quote(String base, String quote) {
        String from = requireSupported(base);
        String to = requireSupported(quote);
        if (from.equals(to)) {
            return new FxQuote(from, to, BigDecimal.ONE, LocalDate.now(ZoneOffset.UTC), false);
        }
        Lookup lookup = ratesFor(from);
        BigDecimal rate = lookup.table().rates().get(to);
        if (rate == null) {
            throw new RateUnavailableException("The provider's " + from + " rates have no " + to + " rate");
        }
        return new FxQuote(from, to, rate, lookup.table().asOf(), lookup.stale());
    }

    private String requireSupported(String currency) {
        String normalised = (currency == null) ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!properties.supportedCurrencies().contains(normalised)) {
            throw new UnsupportedCurrencyException(currency);
        }
        return normalised;
    }

    private Lookup ratesFor(String base) {
        try {
            return lookUpThroughCache(base);
        } catch (DataAccessException redisUnavailable) {
            bypasses.increment();
            log.warn("Redis unavailable, fetching {} rates straight from the provider: {}",
                    base, redisUnavailable.getMessage());
            try {
                return new Lookup(fetch(base), false);
            } catch (ProviderUnavailableException providerAlsoDown) {
                throw new RateUnavailableException(
                        "No " + base + " rates: Redis and the provider are both unavailable", providerAlsoDown);
            }
        }
    }

    private Lookup lookUpThroughCache(String base) {
        Optional<RateTable> fresh = cache.getFresh(base);
        if (fresh.isPresent()) {
            hits.increment();
            return new Lookup(fresh.get(), false);
        }
        misses.increment();

        Optional<String> lock = cache.tryLock(base);
        if (lock.isEmpty()) {
            // Another caller (possibly on another instance) is fetching right now. Wait for its
            // result rather than calling the provider a second time.
            return waitForFresh(base)
                    .map(table -> new Lookup(table, false))
                    .orElseGet(() -> lastKnown(base));
        }
        try {
            RateTable table = fetch(base);
            try {
                cache.put(table);
            } catch (DataAccessException cacheWriteFailed) {
                // The rates are in hand; failing to cache them only costs the next caller a fetch.
                log.warn("Could not cache {} rates: {}", base, cacheWriteFailed.getMessage());
            }
            return new Lookup(table, false);
        } catch (ProviderUnavailableException ex) {
            log.warn("FX provider unavailable for {}, falling back to the last known rates: {}", base, ex.getMessage());
            return lastKnown(base);
        } finally {
            release(base, lock.get());
        }
    }

    private Optional<RateTable> waitForFresh(String base) {
        long deadline = System.nanoTime() + properties.lockWait().toNanos();
        while (System.nanoTime() < deadline) {
            pause();
            Optional<RateTable> fresh = cache.getFresh(base);
            if (fresh.isPresent()) {
                return fresh;
            }
        }
        return Optional.empty();
    }

    private Lookup lastKnown(String base) {
        RateTable table = cache.getLastKnown(base).orElseThrow(() -> new RateUnavailableException(
                "No " + base + " rates: the provider is unavailable and nothing is cached"));
        staleServes.increment();
        return new Lookup(table, true);
    }

    private RateTable fetch(String base) {
        try {
            RateTable table = provider.fetch(base);
            providerSuccesses.increment();
            return table;
        } catch (ProviderUnavailableException ex) {
            providerFailures.increment();
            throw ex;
        }
    }

    private void release(String base, String token) {
        try {
            cache.unlock(base, token);
        } catch (DataAccessException ex) {
            // Harmless: the lock expires on its own after fx.lock-ttl.
            log.warn("Could not release the {} rate lock: {}", base, ex.getMessage());
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RateUnavailableException("Interrupted while waiting for rates", ex);
        }
    }

    private record Lookup(RateTable table, boolean stale) {
    }
}
```

Run: `./mvnw -pl fx-service -Dtest=FxRateServiceTest test`
Expected: 13 tests PASS.

- [ ] **Step 11: Write the Redis-backed ITs**

The shared fake provider, `support/FakeRateProvider.java`:

```java
package com.showcase.fx.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for api.frankfurter.dev, in the repo's usual JDK-HttpServer style (see e.g.
 * transfer-service's FraudClientFallbackIT). Counts calls and can be slowed down or switched off.
 */
public final class FakeRateProvider {

    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean down = new AtomicBoolean();
    private volatile Duration delay = Duration.ZERO;

    public FakeRateProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/latest", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (down.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String base = exchange.getRequestURI().getQuery().replaceAll(".*base=([A-Z]{3}).*", "$1");
            byte[] body = """
                    {"amount":1.0,"base":"%s","date":"2026-09-24","rates":{"EUR":0.22819,"GBP":0.19621,"USD":0.25938,"PLN":4.38231}}
                    """.formatted(base).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
        down.set(false);
        delay = Duration.ZERO;
    }

    public void goDown() {
        down.set(true);
    }

    public void slowDownTo(Duration delay) {
        this.delay = delay;
    }

    public void stop() {
        server.stop(0);
    }
}
```

`service/RateCacheIT.java`:

```java
package com.showcase.fx.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// fresh-ttl shortened so expiry can be observed; last-known keeps its real 24h.
@SpringBootTest(properties = "fx.fresh-ttl=1s")
@Testcontainers
class RateCacheIT {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final RateTable PLN_RATES = new RateTable("PLN", LocalDate.of(2026, 9, 23),
            Map.of("EUR", new BigDecimal("0.22819"), "USD", new BigDecimal("0.25938")));

    @Autowired
    private RateCache cache;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        Set<String> keys = redisTemplate.keys("fx:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void putWritesTheFreshAndLastKnownKeysEachWithItsOwnTtl() {
        cache.put(PLN_RATES);

        assertThat(redisTemplate.getExpire("fx:rates:PLN", TimeUnit.MILLISECONDS)).isBetween(1L, 1_000L);
        assertThat(redisTemplate.getExpire("fx:rates:last-known:PLN", TimeUnit.SECONDS)).isBetween(86_000L, 86_400L);
    }

    @Test
    void aRateTableSurvivesTheRoundTripThroughRedis() {
        cache.put(PLN_RATES);

        assertThat(cache.getFresh("PLN")).contains(PLN_RATES);
        assertThat(cache.getLastKnown("PLN")).contains(PLN_RATES);
    }

    @Test
    void theFreshEntryExpiresWhileTheLastKnownOneSurvives() {
        cache.put(PLN_RATES);

        await().atMost(Duration.ofSeconds(5)).until(() -> cache.getFresh("PLN").isEmpty());
        assertThat(cache.getLastKnown("PLN")).contains(PLN_RATES);
    }

    @Test
    void onlyOneCallerHoldsTheLockUntilItIsReleased() {
        Optional<String> first = cache.tryLock("PLN");

        assertThat(first).isPresent();
        assertThat(cache.tryLock("PLN")).isEmpty();
        cache.unlock("PLN", first.get());
        assertThat(cache.tryLock("PLN")).isPresent();
    }

    @Test
    void releasingWithSomeoneElsesTokenLeavesTheLockInPlace() {
        String token = cache.tryLock("PLN").orElseThrow();

        cache.unlock("PLN", "not-" + token);

        assertThat(redisTemplate.opsForValue().get("fx:lock:PLN")).isEqualTo(token);
    }

    @Test
    void theLockExpiresOnItsOwnSoACrashedHolderCannotWedgeIt() {
        cache.tryLock("PLN").orElseThrow();

        assertThat(redisTemplate.getExpire("fx:lock:PLN", TimeUnit.MILLISECONDS)).isBetween(1L, 5_000L);
    }

    @Test
    void anUnreadableEntryReadsAsAbsent() {
        redisTemplate.opsForValue().set("fx:rates:PLN", "not json");

        assertThat(cache.getFresh("PLN")).isEmpty();
    }
}
```

`service/FxRateServiceIT.java`:

```java
package com.showcase.fx.service;

import com.showcase.fx.support.FakeRateProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The real FxRateService against a real Redis and a fake provider: the cache behaviour end to end. */
@SpringBootTest
@Testcontainers
class FxRateServiceIT {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static FakeRateProvider provider;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws IOException {
        provider = new FakeRateProvider();
        registry.add("fx.provider.base-url", provider::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        provider.stop();
    }

    @Autowired
    private FxRateService fxRateService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void reset() {
        Set<String> keys = redisTemplate.keys("fx:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        provider.reset();
    }

    @Test
    void concurrentMissesForOneBaseMakeExactlyOneProviderCall() throws Exception {
        // Slow enough that every caller arrives while the first fetch is still in flight.
        provider.slowDownTo(Duration.ofMillis(300));
        int callers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FxQuote>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return fxRateService.quote("PLN", "EUR");
                }));
            }
            start.countDown();
            for (Future<FxQuote> result : results) {
                FxQuote quote = result.get(10, TimeUnit.SECONDS);
                assertThat(quote.rate()).isEqualByComparingTo("0.22819");
                assertThat(quote.stale()).isFalse();
            }
        }

        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void aSecondQuoteFromTheSameBaseIsServedFromRedis() {
        fxRateService.quote("EUR", "PLN");
        fxRateService.quote("EUR", "USD");

        assertThat(provider.calls()).isEqualTo(1);
        assertThat(redisTemplate.hasKey("fx:rates:EUR")).isTrue();
        assertThat(redisTemplate.hasKey("fx:rates:last-known:EUR")).isTrue();
    }

    @Test
    void aProviderOutageServesTheLastKnownRatesMarkedStale() {
        fxRateService.quote("GBP", "EUR");
        redisTemplate.delete("fx:rates:GBP"); // as if the fresh entry had expired
        provider.goDown();

        FxQuote quote = fxRateService.quote("GBP", "EUR");

        assertThat(quote.stale()).isTrue();
        assertThat(quote.asOf()).isEqualTo(LocalDate.of(2026, 9, 24));
    }
}
```

`service/FxRateServiceRedisDownIT.java`:

```java
package com.showcase.fx.service;

import com.showcase.fx.support.FakeRateProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Redis is an optimisation, never a dependency: with nothing listening on the configured Redis
 * port, quotes still come from the provider, promptly (the 500ms Redis timeouts in
 * application.yml, not Lettuce's 60s default).
 */
@SpringBootTest(properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
class FxRateServiceRedisDownIT {

    private static FakeRateProvider provider;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws IOException {
        provider = new FakeRateProvider();
        registry.add("fx.provider.base-url", provider::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        provider.stop();
    }

    @Autowired
    private FxRateService fxRateService;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void withRedisUnreachableQuotesStillComeFromTheProviderPromptly() {
        FxQuote quote = assertTimeout(Duration.ofSeconds(5), () -> fxRateService.quote("PLN", "EUR"));

        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
        assertThat(quote.stale()).isFalse();
        assertThat(meterRegistry.counter("fx.cache.requests", "result", "bypass").count()).isEqualTo(1);
    }
}
```

Run: `./mvnw -pl fx-service -Dtest='RateCacheIT,FxRateServiceIT,FxRateServiceRedisDownIT' test` (Docker running)
Expected: 11 tests PASS. If `withRedisUnreachable…` takes more than 5s, the Redis timeouts in
`application.yml` are not taking effect. That is a real defect, so report it; don't raise the
test's limit.

- [ ] **Step 12: Controller, error handling and their tests**

`api/FxRateController.java`:

```java
package com.showcase.fx.api;

import com.showcase.fx.service.FxQuote;
import com.showcase.fx.service.FxRateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FxRateController {

    private final FxRateService fxRateService;

    public FxRateController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @GetMapping("/fx/rates")
    public FxQuote rate(@RequestParam String base, @RequestParam String quote) {
        return fxRateService.quote(base, quote);
    }
}
```

`api/ApiExceptionHandler.java`:

```java
package com.showcase.fx.api;

import com.showcase.fx.service.RateUnavailableException;
import com.showcase.fx.service.UnsupportedCurrencyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                        "Missing required parameter: " + ex.getParameterName()));
    }

    /** Same "every error carries a code" backstop as every other service's ApiExceptionHandler. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", statusCode.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED");
            problem.setProperty("timestamp", Instant.now());
        }
        return response;
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ProblemDetail handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY", "Unsupported currency", ex.getMessage());
    }

    @ExceptionHandler(RateUnavailableException.class)
    public ProblemDetail handleRateUnavailable(RateUnavailableException ex) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "FX_RATE_UNAVAILABLE", "FX rate unavailable",
                "No exchange rate is available right now");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred");
    }
}
```

`api/FxRateControllerTest.java`:

```java
package com.showcase.fx.api;

import com.showcase.fx.service.FxQuote;
import com.showcase.fx.service.FxRateService;
import com.showcase.fx.service.RateUnavailableException;
import com.showcase.fx.service.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @SpringBootTest, not @WebMvcTest -- the slice does not load this service's SecurityConfig
// (see fraud-service's FraudCheckControllerTest). FxRateService is mocked, so no Redis is needed.
@SpringBootTest
@AutoConfigureMockMvc
class FxRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FxRateService fxRateService;

    @Test
    void returnsTheQuote() throws Exception {
        when(fxRateService.quote("PLN", "EUR")).thenReturn(
                new FxQuote("PLN", "EUR", new BigDecimal("0.22819"), LocalDate.of(2026, 9, 23), false));

        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("PLN"))
                .andExpect(jsonPath("$.quote").value("EUR"))
                .andExpect(jsonPath("$.rate").value(0.22819))
                .andExpect(jsonPath("$.asOf").value("2026-09-23"))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void anUnsupportedCurrencyIs400() throws Exception {
        when(fxRateService.quote("JPY", "EUR")).thenThrow(new UnsupportedCurrencyException("JPY"));

        mockMvc.perform(get("/fx/rates").param("base", "JPY").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void noRateAvailableIs503() throws Exception {
        when(fxRateService.quote("PLN", "EUR")).thenThrow(new RateUnavailableException("nothing cached"));

        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FX_RATE_UNAVAILABLE"));
    }

    @Test
    void aMissingParameterIs400() throws Exception {
        mockMvc.perform(get("/fx/rates").param("base", "PLN").with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutFxReaderIs403() throws Exception {
        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fraud-checker")))
                .andExpect(status().isForbidden());
    }
}
```

Also copy `fraud-service`'s `BuildInfoIT.java` and `OpenApiDocsIT.java` into
`fx-service/src/test/java/com/showcase/fx/`. Change the package, and in `OpenApiDocsIT` replace
`$.paths['/fraud-check']` with `$.paths['/fx/rates']`.

Run: `./mvnw -pl fx-service test`
Expected: the whole module passes, 39 tests:

| Test class | Tests |
|---|---|
| `RateProviderTest` | 5 |
| `FxRateServiceTest` | 13 |
| `RateCacheIT` | 7 |
| `FxRateServiceIT` | 3 |
| `FxRateServiceRedisDownIT` | 1 |
| `FxRateControllerTest` | 6 |
| `BuildInfoIT` | 3 |
| `OpenApiDocsIT` | 1 |

- [ ] **Step 13: Keycloak role**

In `docker/keycloak/showcase-realm.json`, add to `roles.realm`, after the `fraud-checker` entry:

```json
      {
        "name": "fx-reader",
        "description": "Can read exchange rates (GET /fx/rates) -- held by customers; transfer-service's machine identity does not need it, because only the live saga (relaying the user's token) calls FX"
      },
```

and change the `customer` composite to:

```json
          "realm": ["transfer-executor", "account-reader", "account-editor", "fraud-checker", "fx-reader"]
```

Do **not** add `fx-reader` to `service-account-transfer-service`'s `realmRoles`.

- [ ] **Step 14: Compose and Prometheus**

In `docker-compose.yml`, add these two services after `fraud-service`:

```yaml
  redis:
    image: redis:7-alpine
    container_name: showcase-redis
    # A cache, not a store: no RDB snapshots, no AOF. Losing it only costs provider calls.
    command: ["redis-server", "--save", "", "--appendonly", "no"]
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  fx-service:
    build:
      context: .
      dockerfile: fx-service/Dockerfile
      args:
        GIT_SHA: ${GIT_SHA:-unknown}
    container_name: showcase-fx-service
    environment:
      REDIS_HOST: redis
      REDIS_PORT: 6379
      FX_PROVIDER_URL: ${FX_PROVIDER_URL:-https://api.frankfurter.dev/v1}
      KEYCLOAK_JWK_SET_URI: http://keycloak:8080/realms/showcase/protocol/openid-connect/certs
      KEYCLOAK_ISSUER_URI: http://localhost:8180/realms/showcase
      OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: http://otel-collector:4318/v1/traces
    ports:
      - "8085:8085"
    depends_on:
      redis:
        condition: service_healthy
      keycloak:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8085/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 40s
```

In `docker/prometheus/prometheus.yml`, append:

```yaml
  - job_name: fx-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["fx-service:8085"]
```

- [ ] **Step 15: Docs**

- `CLAUDE.md`, "Project status":
  - change "Five Spring Boot / Java 21 services" to "Six";
  - add a bullet after Fraud Service:
    ``- **FX Service** (`fx-service/`, port 8085) — exchange rates from the Frankfurter/ECB feed behind a Redis cache (fresh TTL, last-known fallback, cross-instance single-flight lock); no database. The only Redis client.``
  - add "Redis (FX Service's cache, no persistence)" to the "Supporting containers" sentence.
- `docs/roadmap.md`: change the Phase 12 row's status from "In design" to "In progress (Task 1 of 4)".

- [ ] **Step 16: Full suite, live check, commit, PR**

Run `./mvnw test` from the repo root, with `JAVA_HOME=C:\dev\openjdk-21.0.2` and Docker running.
Expected: BUILD SUCCESS, with every module passing. Report the reactor-wide totals, not one
module's.

Live check (the coordinating session first stops any other worktree's stack):

```bash
docker compose up -d --build redis keycloak fx-service
TOKEN=$(curl -s -d 'grant_type=password&client_id=showcase-ui&username=ada&password=password' \
  http://localhost:8180/realms/showcase/protocol/openid-connect/token | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8085/fx/rates?base=PLN&quote=EUR'   # a rate, "stale":false
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8085/fx/rates?base=PLN&quote=USD'   # served from Redis
docker exec showcase-redis redis-cli KEYS 'fx:*'             # fx:rates:PLN and fx:rates:last-known:PLN
docker exec showcase-redis redis-cli TTL fx:rates:PLN         # <= 600
curl -s http://localhost:8085/actuator/prometheus | grep fx_cache_requests_total   # hit 1, miss 1
docker stop showcase-redis
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8085/fx/rates?base=PLN&quote=EUR'   # still answers (bypass)
docker start showcase-redis
```

Put the real output in the PR description. Then commit on the task branch and open the PR
(`feat(fx): FX Service with Redis-cached rates (Phase 12 Task 1)`). **Stop**: the user reviews
and merges.


## Task 2: Account currency

**Branch:** `feature/phase-12-task-2-account-currency`, off the current `master` (with Task 1 merged).

**Files** (Java paths under `account-service/src/main/java/com/showcase/account/` or the matching `src/test/java/...`):
- Create: `domain/SupportedCurrency.java`, `domain/CurrencyMismatchException.java`, `api/AccountExistsResponse.java`
- Modify: `domain/Account.java`, `service/AccountService.java`, `api/AccountController.java`, `api/ApiExceptionHandler.java`, `api/CreateAccountRequest.java`, `api/AmountRequest.java`, `api/AccountResponse.java`, `api/AccountSummaryResponse.java`
- Modify tests: `domain/AccountTest.java`, `domain/AccountRepositoryTest.java`, `service/AccountServiceTest.java`, `api/AccountSecurityIT.java`, `api/AccountControllerIT.java`
- Modify: `web-ui/js/app.js` (the registration currency picker and the balance display)

**Interfaces:**
- Consumes: nothing from Task 1 at runtime. `SupportedCurrency` must hold the same codes as
  `fx.supported-currencies`.
- Produces, for Task 3:
  - `GET /accounts/exists/{id}` → `200 {"currency":"PLN"}`, or `404 ACCOUNT_NOT_FOUND`.
  - `POST /accounts/{id}/debit` and `POST /accounts/{id}/credit` accept
    `{"amount": 40.00, "currency": "PLN"}`. `currency` is optional. If it is present and differs
    from the account's currency, the call gets `422 CURRENCY_MISMATCH` and nothing is applied.
  - `AccountResponse` and `GET /accounts/{id}/summary` both carry `"currency"`.
- Deployment order: the Transfer on `master` at this point reads `exists` bodiless and sends no
  `currency`, and both still work.

- [ ] **Step 1: Write the failing domain tests**

Append to `domain/AccountTest.java` (add `import org.springframework.test.util.ReflectionTestUtils;`):

```java
    @Test
    void keepsTheCurrencyItWasCreatedIn() {
        Account account = new Account(UUID.randomUUID(), "Ada", new BigDecimal("10.00"), SupportedCurrency.PLN);

        assertThat(account.getCurrency()).isEqualTo(SupportedCurrency.PLN);
    }

    @Test
    void aRowFromBeforeCurrenciesExistedReadsAsEur() {
        Account account = new Account(UUID.randomUUID(), "Ada", new BigDecimal("10.00"), SupportedCurrency.PLN);
        ReflectionTestUtils.setField(account, "currency", null);

        assertThat(account.getCurrency()).isEqualTo(SupportedCurrency.EUR);
    }

    @Test
    void requiresACurrency() {
        assertThatThrownBy(() -> new Account(UUID.randomUUID(), "Ada", new BigDecimal("10.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

In the same file, and in `domain/AccountRepositoryTest.java`, change every existing
`new Account(owner, name, balance)` to
`new Account(owner, name, balance, SupportedCurrency.EUR)`.

Run: `./mvnw -pl account-service -Dtest=AccountTest test`
Expected: compilation FAILURE, because `SupportedCurrency` does not exist yet.

- [ ] **Step 2: Implement the domain changes**

`domain/SupportedCurrency.java`:

```java
package com.showcase.account.domain;

/**
 * The currencies an account can be held in. Must list the same codes as fx-service's
 * fx.supported-currencies: a currency Account accepts but FX does not would fail every transfer
 * out of it. See docs/phase-12-fx-rates-redis-cache.md.
 */
public enum SupportedCurrency {
    EUR, USD, GBP, PLN
}
```

`domain/CurrencyMismatchException.java`:

```java
package com.showcase.account.domain;

import java.util.UUID;

/**
 * A debit or credit named a currency the account is not held in. Never a legitimate request:
 * Transfer computes every amount for the account's own currency, so this turns a pricing bug into
 * a clean rejection instead of silently wrong money. See docs/phase-12-fx-rates-redis-cache.md.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(UUID accountId, SupportedCurrency accountCurrency, String requestedCurrency) {
        super("Account %s is held in %s, not %s".formatted(accountId, accountCurrency, requestedCurrency));
    }
}
```

In `domain/Account.java`:
- Add the imports `jakarta.persistence.EnumType` and `jakarta.persistence.Enumerated`.
- Replace the comment above `balance`. The old one says the project has no FX. The new one:
  ```java
      // scale 2, not more: AmountRequest restricts every debit/credit to 2 decimal places
      // (@Digits(fraction = 2)), and Transfer rounds every converted amount to 2 places before it
      // gets here (docs/phase-12-fx-rates-redis-cache.md). A wider scale would be unused headroom.
  ```
- Add after `balance`:
  ```java
      // Fixed at creation. Nullable at the database level only because ddl-auto: update cannot add a
      // NOT NULL column over existing rows; getCurrency() reads such a pre-Phase-12 row as EUR.
      @Enumerated(EnumType.STRING)
      @Column(length = 3, updatable = false)
      private SupportedCurrency currency;
  ```
- Replace the constructor with:
  ```java
      public Account(UUID ownerId, String ownerName, BigDecimal balance, SupportedCurrency currency) {
          if (currency == null) {
              throw new IllegalArgumentException("currency is required");
          }
          this.ownerId = ownerId;
          this.ownerName = ownerName;
          this.balance = balance;
          this.currency = currency;
          this.createdAt = Instant.now();
      }

      // Hand-written, so Lombok's @Getter skips this field. See the comment on the field.
      public SupportedCurrency getCurrency() {
          return currency != null ? currency : SupportedCurrency.EUR;
      }
  ```

Run: `./mvnw -pl account-service -Dtest=AccountTest test`
Expected: PASS. Other test classes won't compile until Step 5.

- [ ] **Step 3: Write the failing service tests**

In `service/AccountServiceTest.java`, first bring the existing tests up to the new signatures:
- Every `new Account(a, b, c)` becomes `new Account(a, b, c, SupportedCurrency.EUR)`.
- `accountService.createAccount(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"))` becomes
  `accountService.createAccount(OWNER_ID, "Ada Lovelace", new BigDecimal("100.00"), SupportedCurrency.EUR)`,
  and that test gains `assertThat(result.getCurrency()).isEqualTo(SupportedCurrency.EUR);`.
- Every `accountService.debit(id, amount, key, caller, flag)` becomes
  `accountService.debit(id, amount, null, key, caller, flag)`.
- Every `accountService.credit(id, amount, key)` becomes `accountService.credit(id, amount, null, key)`.
  A `null` currency is the pre-Phase-12 call shape, and these tests keep proving it still works.
- Replace `requireAccountExistsPassesRegardlessOfOwnership` with the `getCurrency…` tests below.
  If anything else calls `requireAccountExists`, switch it to `getCurrency`.

Then add (with imports for `CurrencyMismatchException` and `SupportedCurrency`, and
`org.junit.jupiter.api.Test` is already there):

```java
    @Test
    void getCurrencyReturnsTheAccountsCurrencyRegardlessOfOwnership() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id))
                .thenReturn(Optional.of(new Account(UUID.randomUUID(), "Bob", new BigDecimal("5.00"), SupportedCurrency.GBP)));

        assertThat(accountService.getCurrency(id)).isEqualTo(SupportedCurrency.GBP);
    }

    @Test
    void getCurrencyThrowsForAMissingAccount() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getCurrency(id)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void debitInTheAccountsCurrencyApplies() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("100.00"), SupportedCurrency.PLN);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.empty());
        when(accountRepository.save(account)).thenReturn(account);

        accountService.debit(id, new BigDecimal("40.00"), "PLN", "key-1", OWNER_ID, false);

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitInAnotherCurrencyIsRejectedWithoutApplyingAnything() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("100.00"), SupportedCurrency.PLN);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.debit(id, new BigDecimal("40.00"), "EUR", "key-1", OWNER_ID, false))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository, never()).save(any());
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    @Test
    void creditInAnotherCurrencyIsRejectedWithoutApplyingAnything() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("100.00"), SupportedCurrency.PLN);
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.credit(id, new BigDecimal("40.00"), "EUR", "key-1"))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(accountOperationRepository, never()).saveAndFlush(any());
    }

    // Review Focus #1. The ledger row is proof the operation already happened. Rejecting its
    // replay over a currency label would make CompensationScheduler's reconcileDebit record FAILED
    // for money that did move.
    @Test
    void aReplayOfAnAlreadyAppliedOperationIsNotRecheckedForCurrency() {
        UUID id = UUID.randomUUID();
        Account account = new Account(OWNER_ID, "Ada", new BigDecimal("125.00"), SupportedCurrency.PLN);
        AccountOperation applied = new AccountOperation("key-1", id, AccountOperationType.CREDIT,
                new BigDecimal("25.00"), new BigDecimal("125.00"));
        when(accountOperationRepository.findById("key-1")).thenReturn(Optional.of(applied));
        when(accountRepository.findById(id)).thenReturn(Optional.of(account));

        Account result = accountService.credit(id, new BigDecimal("25.00"), "EUR", "key-1");

        assertThat(result).isSameAs(account);
        verify(accountRepository, never()).save(any());
    }
```

Run: `./mvnw -pl account-service -Dtest=AccountServiceTest test`
Expected: compilation FAILURE, because the new signatures do not exist yet.

- [ ] **Step 4: Implement the service, API and error mapping**

`service/AccountService.java`:

- `createAccount` takes the currency:
  ```java
      @Transactional
      public Account createAccount(UUID ownerId, String ownerName, BigDecimal initialBalance, SupportedCurrency currency) {
  ```
  and inside it, `new Account(ownerId, ownerName, initialBalance, currency)`.
- Replace `requireAccountExists` entirely with:
  ```java
      // Existence and currency, no ownership -- backs GET /accounts/exists/{id}, which Transfer's
      // pre-validate step calls for both legs of a transfer (including the destination account,
      // which the initiating caller never owns), and which tells Transfer what currency each leg is
      // in. See docs/phase-7b-account-ownership-authorization.md and docs/phase-12-fx-rates-redis-cache.md.
      @Transactional(readOnly = true)
      public SupportedCurrency getCurrency(UUID id) {
          return accountRepository.findById(id)
                  .orElseThrow(() -> new AccountNotFoundException(id))
                  .getCurrency();
      }
  ```
- `debit` and `credit` gain a `String currency` parameter, placed straight after `amount`, and
  pass it to `apply`:
  ```java
      public Account debit(UUID id, BigDecimal amount, String currency, String idempotencyKey,
                           UUID callerId, boolean serviceCaller) {
          requireOwnership(id, callerId, serviceCaller);
          return apply(id, amount, currency, idempotencyKey, AccountOperationType.DEBIT);
      }
  ```
  ```java
      public Account credit(UUID id, BigDecimal amount, String currency, String idempotencyKey) {
          return apply(id, amount, currency, idempotencyKey, AccountOperationType.CREDIT);
      }
  ```
- `apply` gets the same parameter: `private Account apply(UUID id, BigDecimal amount, String currency, String idempotencyKey, AccountOperationType type)`.
  **Only the new-operation path** checks it. Directly after
  `Account account = accountRepository.findById(id).orElseThrow(...)` (the line before
  `if (type == AccountOperationType.DEBIT)`), insert:
  ```java
          // Checked only here, on the path that is about to change the balance -- never on the
          // replay branch above. A recorded operation already happened; rejecting its replay over
          // a currency label would let CompensationScheduler read "rejected" for money that moved.
          requireCurrency(account, currency);
  ```
  and add:
  ```java
      // null is a caller from before Phase 12 (a replay of an older transfer's leg): nothing to check.
      private static void requireCurrency(Account account, String currency) {
          if (currency != null && !account.getCurrency().name().equals(currency)) {
              throw new CurrencyMismatchException(account.getId(), account.getCurrency(), currency);
          }
      }
  ```

`api/CreateAccountRequest.java` (import `com.showcase.account.domain.SupportedCurrency`):

```java
public record CreateAccountRequest(
        @NotBlank String ownerName,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 15, fraction = 2) BigDecimal initialBalance,
        // An unknown code fails JSON binding (400 MALFORMED_REQUEST); a missing one fails @NotNull (400 VALIDATION_FAILED).
        @NotNull SupportedCurrency currency) {
}
```

`api/AmountRequest.java` (import `jakarta.validation.constraints.Pattern`):

```java
public record AmountRequest(
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        // Optional: the currency the caller computed amount in. Absent only on a replay of a
        // pre-Phase-12 transfer's leg. See AccountService.requireCurrency.
        @Pattern(regexp = "[A-Z]{3}") String currency) {
}
```

`api/AccountResponse.java`: add `String currency` after `balance`, and
`account.getCurrency().name()` in `from(...)` in the same position.

`api/AccountSummaryResponse.java`:

```java
public record AccountSummaryResponse(String ownerName, String currency) {

    public static AccountSummaryResponse from(Account account) {
        return new AccountSummaryResponse(account.getOwnerName(), account.getCurrency().name());
    }
}
```

`api/AccountExistsResponse.java`:

```java
package com.showcase.account.api;

/** GET /accounts/exists/{id}: the account exists, and this is the currency it is held in. Nothing else. */
public record AccountExistsResponse(String currency) {
}
```

`api/AccountController.java`:
- `createAccount`: `accountService.createAccount(UUID.fromString(jwt.getSubject()), request.ownerName(), request.initialBalance(), request.currency())`.
- Replace the `accountExists` method and its comment with:
  ```java
      // Existence and currency only, no ownership check -- see docs/phase-7b-account-ownership-authorization.md
      // and docs/phase-12-fx-rates-redis-cache.md. Deliberately not reused as HEAD /accounts/{id}: HEAD
      // shares getAccount's handler, so it would inherit that endpoint's ownership check instead of
      // staying a general probe.
      @GetMapping("/exists/{id}")
      public AccountExistsResponse accountExists(@PathVariable UUID id) {
          return new AccountExistsResponse(accountService.getCurrency(id).name());
      }
  ```
  Remove the `ResponseEntity` import if nothing else uses it (`createAccount` still does).
- `debit`: `accountService.debit(id, request.amount(), request.currency(), idempotencyKey, callerId, serviceCaller)`.
- `credit`: `accountService.credit(id, request.amount(), request.currency(), idempotencyKey)`.

`api/ApiExceptionHandler.java`: add, next to `handleInsufficientFunds`:

```java
    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH", "Currency mismatch", ex.getMessage());
    }
```

Run: `./mvnw -pl account-service -Dtest='AccountTest,AccountServiceTest' test`
Expected: PASS.

- [ ] **Step 5: Bring the web-layer tests up to date and add the HTTP-level checks**

`api/AccountSecurityIT.java`:
- Every `new Account(a, b, c)` → `new Account(a, b, c, SupportedCurrency.EUR)`.
- The two JSON bodies `{"ownerName":"Ada","initialBalance":10.00}` →
  `{"ownerName":"Ada","initialBalance":10.00,"currency":"EUR"}`.
- `createAccount(any(), any(), any())` → `createAccount(any(), any(), any(), any())`, and
  `verify(accountService).createAccount(eq(subject), eq("Ada"), eq(new BigDecimal("10.00")))` →
  add `, eq(SupportedCurrency.EUR)`.
- `debit(any(), any(), any(), any(), anyBoolean())` → `debit(any(), any(), any(), any(), any(), anyBoolean())`;
  `debit(eq(id), any(), any(), any(), eq(false))` → `debit(eq(id), any(), any(), any(), any(), eq(false))`;
  `credit(any(), any(), any())` → `credit(any(), any(), any(), any())`.
- The `exists` tests: `verify(accountService).requireAccountExists(id)` →
  `verify(accountService).getCurrency(id)`. The test that authorizes a call should stub
  `when(accountService.getCurrency(id)).thenReturn(SupportedCurrency.EUR)`, because a mocked enum
  return of `null` would NPE in the controller. `doThrow(...).when(accountService).requireAccountExists(id)`
  → `when(accountService.getCurrency(id)).thenThrow(new AccountNotFoundException(id))`. The two
  tests that expect 401/403 on `exists` never reach the service and need no stub.

`api/AccountControllerIT.java`:
- In the `createAccount(BigDecimal, String)` helper:
  `new CreateAccountRequest("Ada Lovelace", initialBalance, SupportedCurrency.PLN)`.
- Every `new AmountRequest(x)` → `new AmountRequest(x, null)`. That is the helpers and three direct
  call sites.
- Add these tests (with imports for `SupportedCurrency` and `java.util.Map`):

```java
    @Test
    void createWithoutACurrencyIs400() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity("/accounts",
                Map.of("ownerName", "Ada Lovelace", "initialBalance", new BigDecimal("10.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void createWithAnUnsupportedCurrencyIs400() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity("/accounts",
                Map.of("ownerName", "Ada Lovelace", "initialBalance", new BigDecimal("10.00"), "currency", "JPY"),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "MALFORMED_REQUEST");
    }

    @Test
    void existsReportsTheAccountsCurrency() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountExistsResponse> response =
                restTemplate.getForEntity("/accounts/exists/" + id, AccountExistsResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().currency()).isEqualTo("PLN");
    }

    @Test
    void summaryCarriesTheCurrency() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountSummaryResponse> response =
                restTemplate.getForEntity("/accounts/" + id + "/summary", AccountSummaryResponse.class);

        assertThat(response.getBody().currency()).isEqualTo("PLN");
    }

    @Test
    void aDebitLabelledWithAnotherCurrencyIs422AndLeavesTheBalanceAlone() {
        UUID id = createAccount(new BigDecimal("100.00"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<ProblemDetail> response = restTemplate.exchange("/accounts/" + id + "/debit", HttpMethod.POST,
                new HttpEntity<>(new AmountRequest(new BigDecimal("40.00"), "EUR"), headers), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getProperties()).containsEntry("code", "CURRENCY_MISMATCH");
        AccountResponse after = restTemplate.getForObject("/accounts/" + id, AccountResponse.class);
        assertThat(after.balance()).isEqualByComparingTo("100.00");
        assertThat(after.currency()).isEqualTo("PLN");
    }
```

If a test's customer identity cannot call `/accounts/exists/{id}` (it needs `account-reader`),
check `TestSecurityConfig`. Adjust how the test authenticates, not the endpoint's security.

Run: `./mvnw -pl account-service test`
Expected: the whole module passes.

- [ ] **Step 6: Keep registration working in the UI**

`POST /accounts` now requires a currency, so the onboarding screen must send one. In
`web-ui/js/app.js`, `renderOnboarding()`:

- Replace `<p>You'll start with a balance of $1000.00.</p>` with
  `<p>You'll start with a balance of 1000.00 in the currency you choose.</p>`.
- After the `owner-name` input, add:
  ```html
            <label for="currency">Currency</label>
            <select id="currency">
                <option value="EUR">EUR</option>
                <option value="USD">USD</option>
                <option value="GBP">GBP</option>
                <option value="PLN">PLN</option>
            </select>
  ```
- Change the create call to
  `Api.post('/accounts', { ownerName, initialBalance: '1000.00', currency: document.getElementById('currency').value })`.

In `renderDashboard`, replace
`<p class="balance">$${Number(account.balance).toFixed(2)}</p>` with
`<p class="balance">${Number(account.balance).toFixed(2)} ${escapeHtml(account.currency)}</p>`.

- [ ] **Step 7: Full suite, live check, commit, PR**

Run `./mvnw test` from the repo root. Expected: BUILD SUCCESS across the reactor.

Live check. The schema changed, so start from a clean volume:

```bash
docker compose down -v
GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build
```

Then:
1. Register a new user in the UI (http://localhost:8090) and pick PLN. The dashboard should show
   `1000.00 PLN`.
2. Send a transfer to another account. It still completes: Transfer on `master` does not send a
   currency yet.
3. With a customer token, `curl` `/accounts/exists/<id>` on port 8081. It should return
   `{"currency":"PLN"}`.

Put the output in the PR. Open the PR (`feat(account): account currency and CURRENCY_MISMATCH (Phase 12 Task 2)`), then **stop**.


## Task 3: The saga prices and locks the conversion; events carry it

**Branch:** `feature/phase-12-task-3-saga-fx`, off the current `master` (with Tasks 1–2 merged).

This task touches the saga and the compensator. Before writing code, re-read the "Saga
invariant" in `CLAUDE.md` and "Rate locking" above. The two rules this task must not break:
- an unknown debit stays `PENDING`;
- a replay never re-prices.

**Files** (Java paths under `transfer-service/src/main/java/com/showcase/transfer/` unless stated):
- Create: `client/FxClient.java`, `client/FxClientConfig.java`, `client/FxClientProperties.java`, `client/FxRate.java`, `client/FxServiceUnavailableException.java`
- Modify: `domain/Transfer.java`, `domain/TransferFailureCode.java`, `client/AccountClient.java`, `service/TransferService.java` (full replacement below), `service/CompensationScheduler.java`, `service/TransferSaveService.java`, `api/TransferResponse.java`, `api/ApiExceptionHandler.java`, `src/main/resources/application.yml`
- Test (`transfer-service/src/test/java/com/showcase/transfer/`):
  - Create: `client/FxClientTest.java`
  - Modify: `domain/TransferTest.java`, `service/TransferServiceTest.java`, `service/CompensationSchedulerTest.java`, `service/TransferSaveServiceTest.java`, `client/AccountClientTest.java`, `client/AccountClientResilienceTest.java`, `client/AccountClientFallbackIT.java`, `api/TransferControllerTest.java`
- Modify: `notification-service/src/main/java/com/showcase/notification/event/TransferEvent.java`, `notification-service/src/main/java/com/showcase/notification/domain/Notification.java`, `notification-service/src/test/java/com/showcase/notification/NotificationPersistenceIT.java`
- Modify: `docker-compose.yml` (Transfer's `FX_SERVICE_URL` and `depends_on`)

**Interfaces:**
- Consumes: `GET /fx/rates` (Task 1); `GET /accounts/exists/{id}` → `{"currency"}`, and the
  optional `currency` on debit/credit (Task 2).
- Produces:
  - `TransferResponse` gains `sourceCurrency`, `destinationCurrency`, `rate`, `rateAsOf` and
    `creditAmount`. All are null for a transfer that failed before it was priced, or for a row
    from before this phase.
  - The outbox payload gains `sourceCurrency`, `destinationCurrency`, `rate` and `creditAmount`.
  - New failure codes `FX_SERVICE_UNAVAILABLE` (503) and `AMOUNT_TOO_SMALL` (422).
  - Task 4's UI relies on these field names.

- [ ] **Step 1: `Transfer` gets the locked conversion (test first)**

Append to `domain/TransferTest.java` (add `import java.time.LocalDate;`):

```java
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);

    @Test
    void creditAmountRoundsHalfEvenToCents() {
        // 0.50 * 0.25 = 0.125 -> 0.12 (to even); 1.50 * 0.25 = 0.375 -> 0.38 (to even)
        assertThat(Transfer.creditAmountFor(new BigDecimal("0.50"), new BigDecimal("0.25"))).isEqualByComparingTo("0.12");
        assertThat(Transfer.creditAmountFor(new BigDecimal("1.50"), new BigDecimal("0.25"))).isEqualByComparingTo("0.38");
    }

    @Test
    void lockConversionFixesEveryConversionField() {
        Transfer transfer = new Transfer(FROM, TO, new BigDecimal("40.00"), INITIATOR);

        transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), AS_OF);

        assertThat(transfer.getSourceCurrency()).isEqualTo("PLN");
        assertThat(transfer.getDestinationCurrency()).isEqualTo("EUR");
        assertThat(transfer.getRate()).isEqualByComparingTo("0.22819");
        assertThat(transfer.getRateAsOf()).isEqualTo(AS_OF);
        assertThat(transfer.getCreditAmount()).isEqualByComparingTo("9.13"); // 9.1276
        assertThat(transfer.amountToCredit()).isEqualByComparingTo("9.13");
    }

    @Test
    void aConversionCanOnlyBeLockedOnce() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.lockConversion("EUR", "EUR", BigDecimal.ONE, null);

        assertThatThrownBy(() -> transfer.lockConversion("EUR", "EUR", BigDecimal.ONE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lockConversionRefusesARateThatCreditsNothing() {
        Transfer transfer = new Transfer(FROM, TO, new BigDecimal("0.01"), INITIATOR);

        assertThatThrownBy(() -> transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), AS_OF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lockConversionIsOnlyForAPendingTransfer() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);
        transfer.markFailed(TransferFailureCode.ACCOUNT_NOT_FOUND, "gone");

        assertThatThrownBy(() -> transfer.lockConversion("EUR", "EUR", BigDecimal.ONE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRowWithoutALockedConversionCreditsTheDebitedAmount() {
        Transfer transfer = new Transfer(FROM, TO, TEN, INITIATOR);

        assertThat(transfer.getCreditAmount()).isNull();
        assertThat(transfer.amountToCredit()).isEqualByComparingTo("10.00");
    }
```

Run: `./mvnw -pl transfer-service -Dtest=TransferTest test` → compilation FAILURE.

Implement in `domain/Transfer.java` (add imports `java.math.RoundingMode` and `java.time.LocalDate`).
After `amount`, add:

```java
    // The conversion, locked before the row is first inserted and never changed afterwards
    // (updatable = false): every leg and every CompensationScheduler replay reads these, never a
    // fresh rate -- a replay that re-priced would send a different amount under an idempotency key
    // Account has already recorded. All null on a transfer that failed before it was priced, and on
    // a row from before Phase 12 (same-currency by construction; see amountToCredit()). See
    // docs/phase-12-fx-rates-redis-cache.md.
    @Column(length = 3, updatable = false)
    private String sourceCurrency;

    @Column(length = 3, updatable = false)
    private String destinationCurrency;

    @Column(precision = 19, scale = 10, updatable = false)
    private BigDecimal rate;

    // The provider's publication date for rate; null for a same-currency transfer (rate 1).
    @Column(updatable = false)
    private LocalDate rateAsOf;

    // scale 2, matching Account.balance.
    @Column(precision = 19, scale = 2, updatable = false)
    private BigDecimal creditAmount;
```

Add these methods after `conflictsWith`:

```java
    /** What the destination is credited for amount at rate: HALF_EVEN to scale 2, matching Account.balance. */
    public static BigDecimal creditAmountFor(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Fixes the conversion this transfer will use for every leg and every replay. Called exactly
     * once, by TransferService, before the row's first insert -- so no PENDING row ever exists
     * without it.
     */
    public void lockConversion(String sourceCurrency, String destinationCurrency, BigDecimal rate, LocalDate rateAsOf) {
        requireStatus(TransferStatus.PENDING);
        if (this.creditAmount != null) {
            throw new IllegalStateException("Transfer %s already has a locked conversion".formatted(id));
        }
        if (sourceCurrency == null || destinationCurrency == null || rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("A conversion needs both currencies and a positive rate");
        }
        BigDecimal credit = creditAmountFor(amount, rate);
        if (credit.signum() <= 0) {
            throw new IllegalArgumentException("%s at rate %s credits %s".formatted(amount, rate, credit));
        }
        this.sourceCurrency = sourceCurrency;
        this.destinationCurrency = destinationCurrency;
        this.rate = rate;
        this.rateAsOf = rateAsOf;
        this.creditAmount = credit;
    }

    /**
     * The amount the credit leg sends: the locked creditAmount, or -- for a row from before Phase 12,
     * which was same-currency by construction -- the debited amount. The saga and the compensator
     * use this; the API and the outbox report the raw (nullable) creditAmount.
     */
    public BigDecimal amountToCredit() {
        return creditAmount != null ? creditAmount : amount;
    }
```

Run: `./mvnw -pl transfer-service -Dtest=TransferTest test` → PASS.

- [ ] **Step 2: `FxClient` (test first)**

`client/FxRate.java`:

```java
package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/** FX Service's GET /fx/rates answer. stale: the provider was down and this is its last known rate. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxRate(String base, String quote, BigDecimal rate, LocalDate asOf, boolean stale) {
}
```

`client/FxServiceUnavailableException.java`:

```java
package com.showcase.transfer.client;

/** FX Service could not supply a usable rate (5xx, 4xx, timeout, connection failure, unusable body). */
public class FxServiceUnavailableException extends RuntimeException {

    public FxServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public FxServiceUnavailableException(String message) {
        super(message);
    }
}
```

`client/FxClientTest.java`:

```java
package com.showcase.transfer.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FxClientTest {

    private static final String BASE_URL = "http://fx-service:8085";
    private static final String PLN_EUR = BASE_URL + "/fx/rates?base=PLN&quote=EUR";

    private MockRestServiceServer server;
    private FxClient fxClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        fxClient = new FxClient(builder.build());
    }

    @Test
    void returnsTheRate() {
        server.expect(requestTo(PLN_EUR)).andRespond(withSuccess("""
                {"base":"PLN","quote":"EUR","rate":0.22819,"asOf":"2026-09-23","stale":true}
                """, MediaType.APPLICATION_JSON));

        FxRate rate = fxClient.rate("PLN", "EUR");

        assertThat(rate.rate()).isEqualByComparingTo("0.22819");
        assertThat(rate.asOf()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(rate.stale()).isTrue();
        server.verify();
    }

    @Test
    void a503IsUnavailable() {
        server.expect(requestTo(PLN_EUR)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }

    // FX has no business rejections: its 400 means Account and FX disagree on the currency list.
    @Test
    void a400IsUnavailableNotARejection() {
        server.expect(requestTo(PLN_EUR)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }

    @Test
    void aConnectionFailureIsUnavailable() {
        server.expect(requestTo(PLN_EUR)).andRespond(withException(new ConnectException("connection refused")));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }

    @Test
    void aZeroOrMissingRateIsUnavailable() {
        server.expect(requestTo(PLN_EUR)).andRespond(withSuccess("""
                {"base":"PLN","quote":"EUR","rate":0,"asOf":"2026-09-23","stale":false}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }
}
```

`client/FxClient.java`:

```java
package com.showcase.transfer.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks FX Service for one rate. Unlike AccountClient/FraudClient there is only ONE outcome
 * exception: FX Service has no business rejections. Its 400 (an unsupported currency) can only
 * mean Account and FX disagree on the currency list -- a misconfiguration, not an answer about
 * this transfer -- so every non-2xx is "no rate available", same as a timeout. Called only from
 * the live saga, before anything is persisted; CompensationScheduler never calls it (see
 * docs/phase-12-fx-rates-redis-cache.md).
 *
 * <p>Same Resilience4j caution as FraudClient: with a fallbackMethod, every exception the body
 * throws -- and an open circuit's CallNotPermittedException -- goes through rateFallback.
 */
public class FxClient {

    private final RestClient restClient;

    public FxClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = "fxService")
    @Retry(name = "fxService", fallbackMethod = "rateFallback")
    public FxRate rate(String base, String quote) {
        FxRate rate;
        try {
            rate = restClient.get()
                    .uri("/fx/rates?base={base}&quote={quote}", base, quote)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw new FxServiceUnavailableException("FX Service returned " + response.getStatusCode().value());
                    })
                    .body(FxRate.class);
        } catch (RestClientException ex) {
            throw new FxServiceUnavailableException("FX Service call failed: " + ex.getMessage(), ex);
        }
        if (rate == null || rate.rate() == null || rate.rate().signum() <= 0) {
            throw new FxServiceUnavailableException("FX Service returned no usable rate for " + base + "->" + quote);
        }
        return rate;
    }

    private FxRate rateFallback(String base, String quote, Throwable t) {
        if (t instanceof FxServiceUnavailableException already) {
            throw already;
        }
        throw new FxServiceUnavailableException("FX Service call failed: " + t.getMessage(), t);
    }
}
```

`client/FxClientProperties.java`:

```java
package com.showcase.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "fx-service")
public record FxClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public FxClientProperties {
        connectTimeout = (connectTimeout != null) ? connectTimeout : Duration.ofSeconds(2);
        readTimeout = (readTimeout != null) ? readTimeout : Duration.ofSeconds(5);
    }
}
```

`client/FxClientConfig.java`:

```java
package com.showcase.transfer.client;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// Same shape as FraudClientConfig. The injected builder already carries
// AuthorizationPropagatingInterceptor (ServiceOAuth2ClientConfig's RestClientCustomizer), so the
// live saga's call relays the user's token -- which holds fx-reader through "customer".
@Configuration
public class FxClientConfig {

    @Bean
    public FxClient fxClient(RestClient.Builder builder, FxClientProperties properties) {
        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(properties.connectTimeout())
                                .withReadTimeout(properties.readTimeout())))
                .build();
        return new FxClient(restClient);
    }
}
```

In `src/main/resources/application.yml`, after the `fraud-service:` block:

```yaml
fx-service:
  base-url: ${FX_SERVICE_URL:http://localhost:8085}
  connect-timeout: 2s
  read-timeout: 5s
```

and add `fxService` instances under both `resilience4j.circuitbreaker.instances` and
`resilience4j.retry.instances`:

```yaml
      fxService:
        # Same tuning as accountService (see the x3 retry-attempts reasoning there). No
        # ignore-exceptions: FX Service has no business rejections (see FxClient's javadoc).
        sliding-window-size: 30
        minimum-number-of-calls: 15
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        record-exceptions:
          - com.showcase.transfer.client.FxServiceUnavailableException
```

```yaml
      fxService:
        max-attempts: 3
        wait-duration: 200ms
        retry-exceptions:
          - com.showcase.transfer.client.FxServiceUnavailableException
```

Run: `./mvnw -pl transfer-service -Dtest=FxClientTest test` → 5 PASS.

- [ ] **Step 3: `AccountClient` reads the currency and sends it**

In `client/AccountClient.java`:

- Replace `accountExists` (keep its javadoc, adding one sentence: "Since Phase 12 it also returns
  the currency the account is held in, which the saga prices the transfer with."):
  ```java
      @CircuitBreaker(name = "accountService")
      @Retry(name = "accountService", fallbackMethod = "accountCurrencyFallback")
      public String accountCurrency(UUID accountId) {
          AccountExistsBody body = call(() -> restClient.get()
                  .uri("/accounts/exists/{id}", accountId)
                  .retrieve()
                  .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                  .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
                  .body(AccountExistsBody.class));
          if (body == null || body.currency() == null) {
              // Not a business answer: an Account from before Phase 12, or a proxy that swallowed the body.
              throw new AccountServiceUnavailableException("Account Service reported no currency for " + accountId);
          }
          return body.currency();
      }
  ```
- `debit` and `credit` take the currency after the amount:
  ```java
      public void debit(UUID accountId, BigDecimal amount, String currency, String idempotencyKey) {
          post(accountId, amount, currency, "debit", idempotencyKey, false);
      }
  ```
  ```java
      public void credit(UUID accountId, BigDecimal amount, String currency, String idempotencyKey) {
          // (keep the existing comment)
          post(accountId, amount, currency, "credit", idempotencyKey, true);
      }
  ```
- `post(...)` gains `String currency` after `amount`, and its body becomes
  `.body(new AmountBody(amount, currency))` instead of `.body(Map.of("amount", amount))`. Remove
  the `java.util.Map` import.
- Rename `accountExistsFallback` to `accountCurrencyFallback`, returning `String`:
  `private String accountCurrencyFallback(UUID accountId, Throwable t) { throw rethrow(t); }`.
- `debitCreditFallback` gains the extra parameter:
  `private void debitCreditFallback(UUID accountId, BigDecimal amount, String currency, String idempotencyKey, Throwable t)`.
  Update its javadoc to "(UUID, BigDecimal, String, String)".
- Add, at the end of the class (import `com.fasterxml.jackson.annotation.JsonIgnoreProperties`
  and `com.fasterxml.jackson.annotation.JsonInclude`):
  ```java
      @JsonIgnoreProperties(ignoreUnknown = true)
      record AccountExistsBody(String currency) {
      }

      /**
       * Account's AmountRequest. currency is null only when CompensationScheduler replays a leg of
       * a transfer from before Phase 12 -- it is then omitted, and Account skips its currency check.
       */
      @JsonInclude(JsonInclude.Include.NON_NULL)
      record AmountBody(BigDecimal amount, String currency) {
      }
  ```

Update the client tests:
- `AccountClientTest`:
  - Each `accountClient.accountExists(ACCOUNT_ID)` becomes `accountClient.accountCurrency(ACCOUNT_ID)`.
  - The success test responds
    `withSuccess("{\"currency\":\"PLN\"}", MediaType.APPLICATION_JSON)` and asserts the returned
    `"PLN"`.
  - Every `debit(ACCOUNT_ID, amount, key)` becomes `debit(ACCOUNT_ID, amount, "PLN", key)`, and
    likewise for `credit`.
  - `debitPostsTheAmountAndIdempotencyKeyAndSucceeds` gains
    `.andExpect(jsonPath("$.currency").value("PLN"))`.
  - Add two tests:
  ```java
      @Test
      void aReplayWithoutACurrencyOmitsTheFieldEntirely() {
          server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                  .andExpect(jsonPath("$.amount").value(40.00))
                  .andExpect(jsonPath("$.currency").doesNotExist())
                  .andRespond(withStatus(HttpStatus.OK));

          accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00"), null, "transfer-1:credit");

          server.verify();
      }

      @Test
      void accountCurrencyIsUnavailableWhenTheBodyHasNoCurrency() {
          server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID)).andRespond(withStatus(HttpStatus.OK));

          assertThatThrownBy(() -> accountClient.accountCurrency(ACCOUNT_ID))
                  .isInstanceOf(AccountServiceUnavailableException.class);
      }
  ```
- `AccountClientResilienceTest`: rename `callAccountExists` to `callAccountCurrency`, returning
  `accountClient.accountCurrency(ACCOUNT_ID)`. In `retriesATransientFailureAndEventuallySucceeds`,
  the second, successful response becomes
  `withSuccess("{\"currency\":\"EUR\"}", MediaType.APPLICATION_JSON)`. An empty 200 is now "no
  currency", which is retried as unavailable.
- `AccountClientFallbackIT`: `accountExists` → `accountCurrency`. For `debit`/`credit`, pass a
  currency (`"EUR"`) as the new third argument.

Run: `./mvnw -pl transfer-service -Dtest='AccountClient*' test` → PASS.

- [ ] **Step 4: New failure codes and their HTTP mapping**

In `domain/TransferFailureCode.java`, add before `UNEXPECTED_ERROR`:

```java
    FX_SERVICE_UNAVAILABLE,
    AMOUNT_TOO_SMALL,
```

In `api/ApiExceptionHandler.java`, both exhaustive switches need the new codes:
- In `handleTransferFailed`'s status switch, add `AMOUNT_TOO_SMALL` to the
  `UNPROCESSABLE_ENTITY` arm and `FX_SERVICE_UNAVAILABLE` to the `SERVICE_UNAVAILABLE` arm.
- In `detailFor`, add `AMOUNT_TOO_SMALL` to the arm returning `failureReason`, and a new arm
  `case FX_SERVICE_UNAVAILABLE -> "FX Service is currently unavailable";`.

Add to `api/TransferControllerTest.java`:

```java
    @Test
    void returns503WhenNoExchangeRateIsAvailable() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.FX_SERVICE_UNAVAILABLE, "FX Service returned 503 from fx-service:8085");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FX_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("FX Service is currently unavailable"));
    }

    @Test
    void returns422WhenTheConvertedAmountRoundsToNothing() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.AMOUNT_TOO_SMALL, "0.01 PLN converts to 0.00 EUR at rate 0.22819");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_TOO_SMALL"))
                .andExpect(jsonPath("$.detail").value("0.01 PLN converts to 0.00 EUR at rate 0.22819"));
    }

    @Test
    void theResponseCarriesTheLockedConversion() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), java.time.LocalDate.of(2026, 9, 23));
        transfer.markCompleted();
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceCurrency").value("PLN"))
                .andExpect(jsonPath("$.destinationCurrency").value("EUR"))
                .andExpect(jsonPath("$.rate").value(0.22819))
                .andExpect(jsonPath("$.rateAsOf").value("2026-09-23"))
                .andExpect(jsonPath("$.creditAmount").value(9.13));
    }
```

`api/TransferResponse.java`: add, after `amount`,
`String sourceCurrency, String destinationCurrency, BigDecimal rate, LocalDate rateAsOf, BigDecimal creditAmount`.
Fill them in `from(...)` from the matching Lombok getters. Use the raw `getCreditAmount()`, not
`amountToCredit()`: a transfer that failed before it was priced must show no credit amount.
Import `java.time.LocalDate`.

- [ ] **Step 5: Replace `TransferService`**

The start of `execute` is reordered: price in memory, then **one insert**. The money-moving legs
are unchanged, apart from the currency and the credit amount. Replace the whole of
`service/TransferService.java` with:

```java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.client.FraudClient;
import com.showcase.transfer.client.FraudRejectedException;
import com.showcase.transfer.client.FraudServiceUnavailableException;
import com.showcase.transfer.client.FxClient;
import com.showcase.transfer.client.FxRate;
import com.showcase.transfer.client.FxServiceUnavailableException;
import com.showcase.transfer.domain.DebitOutcomeUnknownException;
import com.showcase.transfer.domain.IdempotencyKeyConflictException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferInProgressException;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the transfer saga.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}. A database transaction spanning
 * the HTTP calls below would hold a connection and row locks across two network
 * round-trips, and would roll back local state that the remote service has already
 * committed. Each state change is persisted by {@code TransferRepository.save}, which is
 * itself transactional, so every write commits independently. Do not add
 * {@code @Transactional} to this class.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final AccountClient accountClient;
    private final FraudClient fraudClient;
    private final FxClient fxClient;
    private final TransferSaveService transferSaveService;

    public TransferService(TransferRepository transferRepository, AccountClient accountClient,
                            FraudClient fraudClient, FxClient fxClient, TransferSaveService transferSaveService) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.fraudClient = fraudClient;
        this.fxClient = fxClient;
        this.transferSaveService = transferSaveService;
    }

    public Transfer execute(UUID fromAccountId, UUID toAccountId, BigDecimal amount, UUID initiatorId,
                            String idempotencyKey) {
        // A caller retrying the same request (a double-click, a retry after a lost response)
        // gets the transfer its first attempt created, never a second saga moving the money again.
        Optional<Transfer> earlier = transferRepository.findByInitiatorIdAndIdempotencyKey(initiatorId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), fromAccountId, toAccountId, amount, idempotencyKey);
        }

        // Constructor guards reject a self-transfer before anything is looked up or persisted:
        // SameAccountTransferException must propagate to the caller as a 400.
        Transfer transfer = new Transfer(fromAccountId, toAccountId, amount, initiatorId, idempotencyKey);

        // Steps 1-2, entirely in memory: validate both accounts and lock the conversion. Nothing
        // here moves money, so every failure settles the transfer as FAILED before it is inserted.
        prepare(transfer);

        // The single insert. A PENDING row is born with its conversion locked, so no sweep can
        // ever find a PENDING row without the amounts it must replay (docs/phase-12-fx-rates-redis-cache.md).
        // A FAILED row goes through the outbox choke point like every other terminal write.
        try {
            transfer = transfer.getStatus() == TransferStatus.PENDING
                    ? transferRepository.save(transfer)
                    : transferSaveService.save(transfer);
        } catch (DataIntegrityViolationException ex) {
            // Two requests with the same key both missed the lookup above; the unique constraint
            // on (initiator_id, idempotency_key) let exactly one insert through. Nothing has moved
            // for this request -- prepare() only reads -- so it becomes a replay of the winner. No
            // winner means the violation was something else -- rethrow it.
            Transfer winner = transferRepository.findByInitiatorIdAndIdempotencyKey(initiatorId, idempotencyKey)
                    .orElseThrow(() -> ex);
            return replay(winner, fromAccountId, toAccountId, amount, idempotencyKey);
        }
        if (transfer.getStatus() != TransferStatus.PENDING) {
            log.info("Transfer {} failed before any money moved [{}]: {}",
                    transfer.getId(), transfer.getFailureCode(), transfer.getFailureReason());
            return transfer;
        }
        log.info("Transfer {} started: {} -> {} amount {} {}, credits {} {}", transfer.getId(), fromAccountId,
                toAccountId, amount, transfer.getSourceCurrency(), transfer.getCreditAmount(),
                transfer.getDestinationCurrency());
        return moveMoney(transfer);
    }

    /**
     * Steps 1-2. Marks the transfer FAILED in memory on any failure, or locks its conversion. Never
     * persists and never moves money, which is what makes the single insert in execute() safe.
     */
    private void prepare(Transfer transfer) {
        try {
            // Step 1: pre-validate both accounts, learning the currency each is held in. An
            // optimisation for the common mistyped-id case, NOT a guarantee -- an account can
            // still disappear before the debit, which is why the legs handle every rejection on their own.
            String sourceCurrency;
            String destinationCurrency;
            try {
                sourceCurrency = accountClient.accountCurrency(transfer.getFromAccountId());
                destinationCurrency = accountClient.accountCurrency(transfer.getToAccountId());
            } catch (AccountRejectedException ex) {
                transfer.markFailed(TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
                return;
            } catch (AccountServiceUnavailableException ex) {
                transfer.markFailed(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
                return;
            }

            // Step 2: price the transfer. The same currency needs no rate. Anything else asks FX
            // Service once, here, and never again: every later step and every replay uses the
            // locked values.
            if (sourceCurrency.equals(destinationCurrency)) {
                transfer.lockConversion(sourceCurrency, destinationCurrency, BigDecimal.ONE, null);
                return;
            }
            FxRate fx;
            try {
                fx = fxClient.rate(sourceCurrency, destinationCurrency);
            } catch (FxServiceUnavailableException ex) {
                transfer.markFailed(TransferFailureCode.FX_SERVICE_UNAVAILABLE, ex.getMessage());
                return;
            }
            BigDecimal creditAmount = Transfer.creditAmountFor(transfer.getAmount(), fx.rate());
            if (creditAmount.signum() == 0) {
                transfer.markFailed(TransferFailureCode.AMOUNT_TOO_SMALL, "%s %s converts to %s %s at rate %s"
                        .formatted(transfer.getAmount(), sourceCurrency, creditAmount, destinationCurrency, fx.rate()));
                return;
            }
            transfer.lockConversion(sourceCurrency, destinationCurrency, fx.rate(), fx.asOf());
        } catch (RuntimeException ex) {
            // Anything the client exceptions do not cover -- a bug, a mapper failure. Nothing has
            // moved and nothing is persisted yet, so this is a clean failure.
            transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, ex.toString());
        }
    }

    /** Steps 3-6, on a persisted PENDING row with its conversion locked. */
    private Transfer moveMoney(Transfer transfer) {
        // Tracks whether the debit leg committed, so the catch-all below knows whether an
        // unexpected failure left money stranded or left everything untouched.
        boolean debited = false;

        try {
            // Step 3: screen the source account before any money moves. A block or an
            // unreachable Fraud Service both fail clean here -- nothing to compensate, same
            // shape as every other pre-debit rejection. See docs/phase-5-fraud-service.md.
            try {
                fraudClient.check(transfer.getFromAccountId());
            } catch (FraudRejectedException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 4: debit the source, in its own currency.
            try {
                accountClient.debit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getSourceCurrency(),
                        transfer.getId() + ":debit");
            } catch (AccountRejectedException ex) {
                // Account understood and refused. Nothing moved -- genuinely clean.
                return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                // AMBIGUOUS, and the most dangerous state in this service. A read timeout is
                // indistinguishable from "the request never arrived": Account may have
                // committed the debit and failed to tell us.
                //
                // So the transfer is NOT settled here. FAILED would be terminal -- no sweep
                // revisits it -- and would stay wrong for good if the debit had committed.
                // COMPENSATION_REQUIRED means "debit definitely succeeded", and the compensator
                // would credit the source back on a guess, inventing money if it never landed.
                // Left PENDING, the row is exactly what CompensationScheduler.sweepStalePending
                // exists for: it replays <id>:debit against Account's idempotency ledger and
                // settles the transfer from the answer, not from a guess.
                log.error("Transfer {} debit outcome UNKNOWN for account {} amount {}: {}. "
                                + "Left PENDING for the stale-PENDING sweep to reconcile.",
                        transfer.getId(), transfer.getFromAccountId(), transfer.getAmount(), ex.getMessage());
                throw new DebitOutcomeUnknownException(transfer.getId(), ex);
            }
            debited = true;

            // Step 5: screen the destination account. Money has already moved, so a
            // block or an unreachable Fraud Service both strand the transfer for
            // CompensationScheduler rather than failing clean.
            try {
                fraudClient.check(transfer.getToAccountId());
            } catch (FraudRejectedException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 6: credit the destination the locked amount, in its own currency. Past this
            // point the source is already debited, so business rejection and infrastructure
            // failure have identical consequences: funds are stranded and something has to put
            // them back. CompensationScheduler resolves that.
            try {
                accountClient.credit(transfer.getToAccountId(), transfer.amountToCredit(),
                        transfer.getDestinationCurrency(), transfer.getId() + ":credit");
            } catch (AccountRejectedException ex) {
                return strand(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            transfer.markCompleted();
            log.info("Transfer {} completed", transfer.getId());
            return transferSaveService.save(transfer);
        } catch (DebitOutcomeUnknownException ex) {
            // Deliberately unsettled -- see step 4. Must not fall into the catch-all below,
            // which would record the PENDING row as FAILED.
            throw ex;
        } catch (RuntimeException ex) {
            // Anything the client exceptions do not cover -- a DataAccessException or an
            // optimistic-lock failure from a save, a bug. Without this the row is orphaned in
            // PENDING: a 500 reaches the caller and nothing ever revisits it.
            if (transfer.getStatus() != TransferStatus.PENDING) {
                // Already settled in memory -- the failure was persisting that outcome.
                // Marking again would throw IllegalStateException from requirePending()
                // and mask the real cause.
                log.error("Transfer {} failed to persist terminal state {}", transfer.getId(), transfer.getStatus(), ex);
                // Wrapped, not rethrown raw: by now the transfer has an id and a row that still
                // reads PENDING, and the API has to hand that id back -- a caller who cannot
                // name the record cannot reconcile it. The original stays as the cause.
                throw new TransferPersistenceException(transfer.getId(), ex);
            }
            return debited
                    ? strand(transfer, TransferFailureCode.UNEXPECTED_ERROR, ex.toString())
                    : fail(transfer, TransferFailureCode.UNEXPECTED_ERROR, ex.toString());
        }
    }

    private Transfer replay(Transfer earlier, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                            String idempotencyKey) {
        if (earlier.conflictsWith(fromAccountId, toAccountId, amount)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        if (earlier.getStatus() == TransferStatus.PENDING) {
            throw new TransferInProgressException(earlier.getId());
        }
        log.info("Transfer {} replayed for idempotency key {}: already {}",
                earlier.getId(), idempotencyKey, earlier.getStatus());
        return earlier;
    }

    // Scoped to the initiator or the destination account's owner -- see
    // docs/phase-7b-account-ownership-authorization.md's Design Decisions for why the
    // destination-owner check is a live call rather than something stored on Transfer.
    // Objects.equals, not a raw .equals() call: ddl-auto: update cannot add a NOT NULL column
    // over a table with existing rows, so a pre-Phase-7b row can have a null initiatorId --
    // Objects.equals denies cleanly instead of NPE-ing into a 500.
    public Transfer getTransfer(UUID id, UUID callerId) {
        Transfer transfer = transferRepository.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
        if (Objects.equals(transfer.getInitiatorId(), callerId)) {
            return transfer;
        }
        if (accountClient.isOwnedByCaller(transfer.getToAccountId())) {
            return transfer;
        }
        throw new TransferNotFoundException(id);
    }

    public List<Transfer> listMyTransfers(UUID initiatorId, TransferStatus status) {
        return status == null
                ? transferRepository.findByInitiatorId(initiatorId)
                : transferRepository.findByInitiatorIdAndStatus(initiatorId, status);
    }

    public List<Transfer> listTransfers(TransferStatus status) {
        return status == null ? transferRepository.findAll() : transferRepository.findByStatus(status);
    }

    private Transfer fail(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markFailed(code, reason);
        log.info("Transfer {} failed [{}]: {}", transfer.getId(), code, reason);
        return transferSaveService.save(transfer);
    }

    private Transfer strand(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markCompensationRequired(code, reason);
        log.error("Transfer {} needs compensation: {} was debited {} {} but {} was not credited [{}]: {}",
                transfer.getId(), transfer.getFromAccountId(), transfer.getAmount(), transfer.getSourceCurrency(),
                transfer.getToAccountId(), code, reason);
        return transferSaveService.save(transfer);
    }
}
```

- [ ] **Step 6: Bring `TransferServiceTest` up to date, then add the pricing tests**

Mechanical updates to the existing tests, all of which must still pass:
- Add `@Mock private FxClient fxClient;` and construct with
  `new TransferService(transferRepository, accountClient, fraudClient, fxClient, transferSaveService)`.
- At the end of `setUp()`, add a default so every account exists in EUR:
  ```java
          // Every account exists, in EUR, unless a test says otherwise (with doThrow/doReturn,
          // which do not invoke this stub). lenient: the replay tests never reach pre-validation.
          lenient().when(accountClient.accountCurrency(any())).thenReturn("EUR");
  ```
  and import `static org.mockito.Mockito.lenient`, `doReturn` and `doThrow`.
- In `repositoryEchoesSaves()`, wrap both `when(...)` stubs in `lenient()`. A transfer that fails
  pre-validation is now inserted once, through `transferSaveService`, and never touches
  `transferRepository.save`. Strict stubbing would flag that stub as unused.
- `org.mockito.Mockito.doThrow(X).when(accountClient).accountExists(ID)` →
  `doThrow(X).when(accountClient).accountCurrency(ID)`. Delete the
  `org.mockito.Mockito.doNothing().when(accountClient).accountExists(FROM);` line in
  `failsWithoutDebitingWhenTheDestinationAccountDoesNotExist` (the default stub covers it), and
  delete its now-obsolete comment.
- Every `debit(eq(FROM), eq(AMOUNT), any())` → `debit(eq(FROM), eq(AMOUNT), eq("EUR"), any())`.
  Every `credit(eq(TO), eq(AMOUNT), any())` → `credit(eq(TO), eq(AMOUNT), eq("EUR"), any())`.
  Every `debit(FROM, AMOUNT, TRANSFER_ID + ":debit")` → `debit(FROM, AMOUNT, "EUR", TRANSFER_ID + ":debit")`,
  and likewise for `credit`. Every `never()).debit(any(), any(), any())` / `credit(...)` gains a
  fourth `any()`.
- `rejectsATransferToTheSameAccount`: `verify(accountClient, never()).accountExists(any())` →
  `verify(accountClient, never()).accountCurrency(any())`.
- The two insert-race tests (`losingTheInsertRaceReplaysTheWinner` and
  `rethrowsAnIntegrityViolationThatIsNotAKeyCollision`) now reach pre-validation before the
  insert. That is by design: `prepare()` only reads. Replace their
  `verifyNoInteractions(accountClient, fraudClient, transferSaveService)` with:
  ```java
          verify(accountClient, never()).debit(any(), any(), any(), any());
          verifyNoInteractions(fraudClient, transferSaveService);
  ```
- The replay tests' `verifyNoInteractions(accountClient, fraudClient, transferSaveService)` →
  `verifyNoInteractions(accountClient, fraudClient, fxClient, transferSaveService)`. A replay
  never re-prices.

Then add (with imports for `FxRate`, `FxServiceUnavailableException`, `java.time.LocalDate`):

```java
    // --- Phase 12: pricing and the locked conversion ---

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);
    private static final FxRate PLN_TO_EUR = new FxRate("PLN", "EUR", new BigDecimal("0.22819"), AS_OF, false);

    private void sourceInPlnDestinationInEur() {
        doReturn("PLN").when(accountClient).accountCurrency(FROM);
        doReturn("EUR").when(accountClient).accountCurrency(TO);
    }

    @Test
    void aCrossCurrencyTransferDebitsTheAmountAndCreditsTheLockedConversion() {
        repositoryEchoesSaves();
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenReturn(PLN_TO_EUR);

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getCreditAmount()).isEqualByComparingTo("9.13"); // 40.00 * 0.22819 = 9.1276
        assertThat(result.getRate()).isEqualByComparingTo("0.22819");
        assertThat(result.getRateAsOf()).isEqualTo(AS_OF);
        verify(accountClient).debit(FROM, AMOUNT, "PLN", TRANSFER_ID + ":debit");
        verify(accountClient).credit(TO, new BigDecimal("9.13"), "EUR", TRANSFER_ID + ":credit");
    }

    // Review Focus #2: no PENDING row may ever exist without the amounts a sweep must replay.
    @Test
    void theConversionIsOnTheRowAtItsFirstInsertBeforeAnyMoneyMoves() {
        List<BigDecimal> creditAmountAtInsert = new ArrayList<>();
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", TRANSFER_ID);
            creditAmountAtInsert.add(saved.getCreditAmount());
            return saved;
        });
        when(transferSaveService.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenReturn(PLN_TO_EUR);

        transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        InOrder inOrder = inOrder(fxClient, transferRepository, accountClient);
        inOrder.verify(fxClient).rate("PLN", "EUR");
        inOrder.verify(transferRepository).save(any(Transfer.class));
        inOrder.verify(accountClient).debit(any(), any(), any(), any());
        assertThat(creditAmountAtInsert).containsExactly(new BigDecimal("9.13"));
    }

    @Test
    void aSameCurrencyTransferNeverCallsFx() {
        repositoryEchoesSaves();

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getSourceCurrency()).isEqualTo("EUR");
        assertThat(result.getRate()).isEqualByComparingTo("1");
        assertThat(result.getCreditAmount()).isEqualByComparingTo(AMOUNT);
        verifyNoInteractions(fxClient);
    }

    @Test
    void anFxOutageFailsTheTransferInOneInsertWithNothingMoved() {
        repositoryEchoesSaves();
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenThrow(new FxServiceUnavailableException("FX Service returned 503"));

        Transfer result = transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.FX_SERVICE_UNAVAILABLE);
        assertThat(result.getCreditAmount()).isNull();
        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.FAILED);
        verify(transferRepository, never()).save(any());
        verifyNoInteractions(fraudClient);
        verify(accountClient, never()).debit(any(), any(), any(), any());
    }

    @Test
    void anAmountThatConvertsToNothingIsRejectedBeforeAnyMoneyMoves() {
        repositoryEchoesSaves();
        sourceInPlnDestinationInEur();
        when(fxClient.rate("PLN", "EUR")).thenReturn(PLN_TO_EUR);

        // 0.01 PLN * 0.22819 = 0.0022819 -> 0.00 EUR
        Transfer result = transferService.execute(FROM, TO, new BigDecimal("0.01"), INITIATOR_ID, KEY);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.AMOUNT_TOO_SMALL);
        verify(accountClient, never()).debit(any(), any(), any(), any());
    }

    @Test
    void aFailedPreValidationIsInsertedOnceThroughTheOutboxChokePoint() {
        repositoryEchoesSaves();
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).accountCurrency(TO);

        transferService.execute(FROM, TO, AMOUNT, INITIATOR_ID, KEY);

        assertThat(statusesAtSaveTime).containsExactly(TransferStatus.FAILED);
        verify(transferRepository, never()).save(any());
        verify(transferSaveService).save(any(Transfer.class));
    }
```

Run: `./mvnw -pl transfer-service -Dtest=TransferServiceTest test` → PASS, the existing tests
included. If an existing test fails for a reason other than the updates listed above, stop and
report it. Don't bend the test to fit.

- [ ] **Step 7: The compensator replays the locked amounts**

In `service/CompensationScheduler.java` there are exactly three call sites, and nothing else changes:

- `reconcileCredit`:
  `accountClient.credit(transfer.getToAccountId(), transfer.amountToCredit(), transfer.getDestinationCurrency(), transfer.getId() + ":credit");`
- `compensateSource`:
  `accountClient.credit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getSourceCurrency(), transfer.getId() + ":compensate");`
- `reconcileDebit`:
  `accountClient.debit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getSourceCurrency(), transfer.getId() + ":debit");`

Add one sentence to the class javadoc: "Every replay sends the amounts and currencies locked on
the row and never asks FX Service for a rate (this class has no FxClient, by design); see
docs/phase-12-fx-rates-redis-cache.md." Do **not** give this class an `FxClient` dependency.

In `service/CompensationSchedulerTest.java`, the existing fixtures are unconverted rows, i.e.
pre-Phase-12 rows (Review Focus #3). Their expectations gain a `null` currency:
- every `credit(TO, AMOUNT, TRANSFER_ID + ":credit")` → `credit(TO, AMOUNT, null, TRANSFER_ID + ":credit")`,
- every `credit(FROM, AMOUNT, … ":compensate")` → `credit(FROM, AMOUNT, null, … ":compensate")`,
- every `debit(FROM, AMOUNT, … ":debit")` → `debit(FROM, AMOUNT, null, … ":debit")`,
- and every `any(), any(), any()` / `eq(X), any(), any()` on `debit`/`credit` gains a fourth `any()`.

Add a sentence to the class's top comment: "The fixtures without a locked conversion double as
the pre-Phase-12 row tests: those replay with no currency and credit the debited amount."

Then add (import `java.time.LocalDate`):

```java
    private Transfer convertedTransfer() {
        Transfer transfer = new Transfer(FROM, TO, AMOUNT, UUID.randomUUID());
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), LocalDate.of(2026, 9, 23));
        return transfer;
    }

    @Test
    void reconcilingAConvertedTransferCreditsTheLockedAmountInTheDestinationCurrency() {
        Transfer transfer = convertedTransfer();
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(accountClient).credit(TO, new BigDecimal("9.13"), "EUR", TRANSFER_ID + ":credit");
    }

    @Test
    void compensatingAConvertedTransferReturnsTheDebitedAmountInTheSourceCurrency() {
        Transfer transfer = convertedTransfer();
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
        doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, new BigDecimal("9.13"), "EUR", TRANSFER_ID + ":credit");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        scheduler.drainCompensationRequired();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        verify(accountClient).credit(FROM, AMOUNT, "PLN", TRANSFER_ID + ":compensate");
    }

    @Test
    void replayingAStaleConvertedDebitSendsTheDebitedAmountInTheSourceCurrency() {
        Transfer transfer = convertedTransfer();
        when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any(), any()))
                .thenReturn(List.of(transfer));
        when(transferRepository.save(transfer)).thenReturn(transfer);

        scheduler.sweepStalePending();

        verify(accountClient).debit(FROM, AMOUNT, "PLN", TRANSFER_ID + ":debit");
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
    }
```

Run: `./mvnw -pl transfer-service -Dtest='CompensationScheduler*' test` → PASS.

- [ ] **Step 8: The outbox event and Notification carry the conversion**

In `service/TransferSaveService.java`, extend `TransferEventPayload` by appending
`String sourceCurrency, String destinationCurrency, BigDecimal rate, BigDecimal creditAmount`.
Pass `transfer.getSourceCurrency(), transfer.getDestinationCurrency(), transfer.getRate(),
transfer.getCreditAmount()` in `toPayload`. Use the raw getter, as in `TransferResponse`.

Add to `service/TransferSaveServiceTest.java` (imports: `ArgumentCaptor` is already there;
add `java.time.LocalDate`):

```java
    @Test
    void theOutboxPayloadCarriesTheLockedConversion() throws Exception {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("40.00"), UUID.randomUUID());
        transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), LocalDate.of(2026, 9, 23));
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(event.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().readTree(event.getValue().getPayload());
        assertThat(payload.get("sourceCurrency").asText()).isEqualTo("PLN");
        assertThat(payload.get("destinationCurrency").asText()).isEqualTo("EUR");
        assertThat(payload.get("rate").decimalValue()).isEqualByComparingTo("0.22819");
        assertThat(payload.get("creditAmount").decimalValue()).isEqualByComparingTo("9.13");
    }
```

If `OutboxEvent`'s payload getter has a different name, use that one. Check the entity; don't
add a getter.

In Notification:
- `event/TransferEvent.java`: append `String sourceCurrency, String destinationCurrency, BigDecimal rate, BigDecimal creditAmount`
  to the record. Add a javadoc sentence: "The conversion fields (Phase 12) are null on an event
  published before Transfer locked conversions."
- `domain/Notification.java`: add after `amount`:
  ```java
      @Column(length = 3)
      private String sourceCurrency;

      @Column(length = 3)
      private String destinationCurrency;

      @Column(precision = 19, scale = 10)
      private BigDecimal rate;

      @Column(precision = 19, scale = 2)
      private BigDecimal creditAmount;
  ```
  and assign all four from the event in the constructor.

Add to `NotificationPersistenceIT.java`:

```java
    @Test
    void storesTheConversionTheTransferLocked() {
        UUID transferId = UUID.randomUUID();
        kafkaTemplate.send("transfer.completed", transferId.toString(), """
                {"transferId":"%s","fromAccountId":"%s","toAccountId":"%s","amount":40.00,
                 "sourceCurrency":"PLN","destinationCurrency":"EUR","rate":0.22819,"creditAmount":9.13,
                 "status":"COMPLETED","settledAt":"2026-09-24T10:15:30.123Z"}
                """.formatted(transferId, UUID.randomUUID(), UUID.randomUUID()));

        Notification stored = await().atMost(Duration.ofSeconds(20))
                .until(() -> repository.findByTransferId(transferId).orElse(null), n -> n != null);

        assertThat(stored.getSourceCurrency()).isEqualTo("PLN");
        assertThat(stored.getDestinationCurrency()).isEqualTo("EUR");
        assertThat(stored.getRate()).isEqualByComparingTo("0.22819");
        assertThat(stored.getCreditAmount()).isEqualByComparingTo("9.13");
    }
```

and, in the existing `storesEveryFieldOfTheTransferOutcomeAndWhereItWasReadFrom` (whose payload
is the pre-Phase-12 shape), add `assertThat(stored.getCreditAmount()).isNull();` and
`assertThat(stored.getSourceCurrency()).isNull();`. That proves an old-shape event still stores
cleanly.

- [ ] **Step 9: Compose**

In `docker-compose.yml`, under `transfer-service`:
- add `FX_SERVICE_URL: http://fx-service:8085` to `environment` (next to `FRAUD_SERVICE_URL`);
- add to `depends_on`:
  ```yaml
      fx-service:
        condition: service_healthy
  ```

- [ ] **Step 10: Full suite, live check, commit, PR**

Run `./mvnw test` from the repo root. Expected: BUILD SUCCESS across the reactor.

Live check (`docker compose down -v`, then
`GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build`):
1. In the UI, register user A with PLN and user B with EUR. Send 100.00 from A to B, using B's
   account id from B's dashboard.
2. `GET /transfers/{id}` on 8082 with A's token should show `sourceCurrency` PLN, `rate`,
   `rateAsOf` and `creditAmount` ≈ 22.8. A's balance should be 900.00 PLN, and B's
   1000 + creditAmount EUR.
3. `docker exec showcase-redis redis-cli KEYS 'fx:*'` should show `fx:rates:PLN`.
4. Stop FX (`docker stop showcase-fx-service`) and send A→B again. It should get `503
   FX_SERVICE_UNAVAILABLE`, and A's balance should be unchanged. Then start FX again.
5. `docker exec showcase-postgres psql -U postgres -d notification -c "select credit_amount, source_currency from notifications"`
   should show the converted row.

Put the output in the PR, open it (`feat(transfer): price transfers and lock the FX conversion (Phase 12 Task 3)`),
and **stop**.


## Task 4: Gateway route, UI quote, Grafana panel, docs

**Branch:** `feature/phase-12-task-4-ui-gateway`, off the current `master` (with Tasks 1–3 merged).

**Files:**
- Create: `gateway-service/src/main/java/com/showcase/gateway/config/FxServiceProperties.java`
- Modify: `gateway-service/src/main/java/com/showcase/gateway/config/GatewayRoutesConfig.java`, `.../config/SecurityConfig.java`, `gateway-service/src/main/resources/application.yml`
- Test: `gateway-service/src/test/java/com/showcase/gateway/config/GatewayRoutesConfigTest.java`, `.../GatewaySecurityIT.java`
- Modify: `web-ui/js/app.js`, `web-ui/js/api.js`, `web-ui/css/styles.css`
- Create: `docker/grafana/dashboards/fx-cache.json`
- Modify: `docker-compose.yml` (Gateway env and `depends_on`)
- Modify: `docs/microservices-showcase-design.md`, `docs/open-items.md`, `docs/roadmap.md`, `README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: `GET /fx/rates` (Task 1); `currency` on `AccountResponse` and on the summary (Task 2);
  the conversion fields on `TransferResponse` (Task 3).
- Produces: `GET /fx/rates` reachable through the Gateway at `localhost:8080`, gated on `fx-reader`.

- [ ] **Step 1: Gateway route (test first)**

In `GatewayRoutesConfigTest`, add a field and two tests:

```java
    private final RouterFunction<ServerResponse> fxRoutes =
            config.fxRoutes(new FxServiceProperties("http://fx-service:8085"));

    @Test
    void fxRatesMatchesGetOnly() {
        assertThat(matches(fxRoutes, "GET", "/fx/rates")).isTrue();
        assertThat(matches(fxRoutes, "POST", "/fx/rates")).isFalse();
    }

    @Test
    void nothingElseUnderFxHasARoute() {
        assertThat(matches(fxRoutes, "GET", "/fx/anything-else")).isFalse();
        assertThat(matches(fxRoutes, "GET", "/transfers")).isFalse();
    }
```

In `GatewaySecurityIT`, add:

```java
    @Test
    void fxRatesRouteReturns403WithoutFxReaderAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/fx/rates?base=PLN&quote=EUR", HttpMethod.GET, "transfer-executor");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void fxRatesRouteAcceptsFxReaderAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/fx/rates?base=PLN&quote=EUR", HttpMethod.GET, "fx-reader");
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }
```

Run: `./mvnw -pl gateway-service test` → compilation FAILURE.

`config/FxServiceProperties.java`:

```java
package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fx-service")
public record FxServiceProperties(String baseUrl) {
}
```

In `GatewayRoutesConfig`, add:

```java
    // Only the one read the Bank UI needs for a quote. Nothing else FX Service exposes is reachable.
    @Bean
    public RouterFunction<ServerResponse> fxRoutes(FxServiceProperties properties) {
        return route(GET("/fx/rates"), http(properties.baseUrl()));
    }
```

and update the class javadoc's allow-list sentence so it mentions `GET /fx/rates`.

In `SecurityConfig`, add before `.anyRequest().authenticated()`:

```java
                        .requestMatchers(HttpMethod.GET, "/fx/rates").hasAuthority("fx-reader")
```

In `application.yml`, add after `transfer-service:`:

```yaml
fx-service:
  base-url: ${FX_SERVICE_URL:http://localhost:8085}
```

If the Gateway registers its `@ConfigurationProperties` records explicitly instead of scanning
for them, register `FxServiceProperties` the same way.

In `docker-compose.yml`, under `gateway-service`:
- add `FX_SERVICE_URL: http://fx-service:8085` to `environment`;
- add `fx-service: { condition: service_healthy }` to `depends_on`, in the same block style as
  the others.

Run: `./mvnw -pl gateway-service test` → PASS.

- [ ] **Step 2: UI: quote before sending, and the locked amount after**

`web-ui/js/api.js`: add to `ERROR_MESSAGES`:

```js
    FX_SERVICE_UNAVAILABLE: 'Currency conversion is temporarily unavailable. Transfers in the same currency still work.',
    AMOUNT_TOO_SMALL: 'That amount is too small to convert into the recipient’s currency.',
```

`web-ui/css/styles.css`: add

```css
.hint {
    color: #555;
    font-size: 0.9rem;
}
```

`web-ui/js/app.js`, `renderTransferForm`:
- After the amount `<input>`, add `<p id="transfer-quote" class="hint" hidden></p>`.
- After the `startNewTransfer` definition, add:
  ```js
      // An approximate quote while the user types. Advisory only: Transfer Service prices the
      // transfer itself when it is sent, and the result shows the amount it actually locked.
      const quoteEl = document.getElementById('transfer-quote');
      let latestQuote = 0;
      const refreshQuote = async () => {
          const requestId = ++latestQuote;
          quoteEl.hidden = true;
          const toAccountId = document.getElementById('to-account').value.trim();
          const amount = Number(document.getElementById('amount').value);
          if (!toAccountId || !(amount > 0)) {
              return;
          }
          try {
              const recipient = await Api.get(`/accounts/${encodeURIComponent(toAccountId)}/summary`);
              if (requestId !== latestQuote || recipient.currency === account.currency) {
                  return;
              }
              const fx = await Api.get(`/fx/rates?base=${account.currency}&quote=${recipient.currency}`);
              if (requestId !== latestQuote) {
                  return; // a newer edit superseded this quote
              }
              quoteEl.textContent = `${recipient.ownerName} receives ≈ ${(amount * fx.rate).toFixed(2)} ${recipient.currency}`
                  + ` (1 ${account.currency} = ${fx.rate} ${recipient.currency}, as of ${fx.asOf}`
                  + `${fx.stale ? ' — last known rate' : ''}). The exact amount is fixed when you send.`;
              quoteEl.hidden = false;
          } catch (err) {
              // No quote is not a form error: sending still works, and the server prices it.
          }
      };
  ```
- Both existing `input` listeners now also refresh the quote:
  ```js
      document.getElementById('to-account').addEventListener('input', () => { startNewTransfer(); refreshQuote(); });
      document.getElementById('amount').addEventListener('input', () => { startNewTransfer(); refreshQuote(); });
  ```
  These replace the two lines that pass `startNewTransfer` directly.
- In the submit handler, keep the POST's result and say what landed:
  ```js
              const transfer = await Api.post('/transfers', { fromAccountId: account.id, toAccountId, amount },
                  { 'Idempotency-Key': idempotencyKey });
              const refreshedAccounts = await Api.get('/accounts/mine');
              const converted = transfer.destinationCurrency && transfer.destinationCurrency !== transfer.sourceCurrency;
              await renderDashboard(refreshedAccounts[0], {
                  flashMessage: converted
                      ? `Transfer completed: the recipient received ${Number(transfer.creditAmount).toFixed(2)} ${transfer.destinationCurrency}.`
                      : 'Transfer completed.',
              });
  ```

`renderHistory`: the amount cell shows both sides when converted. Replace
`<td>$${Number(t.amount).toFixed(2)}</td>` with `<td>${formatTransferAmount(t)}</td>`, and add
next to `escapeHtml`:

```js
// "100.00 PLN → 22.82 EUR" for a converted transfer; "40.00 EUR" otherwise. A transfer from before
// currencies existed has no currency fields at all, and shows the bare amount.
function formatTransferAmount(t) {
    const sent = `${Number(t.amount).toFixed(2)} ${escapeHtml(t.sourceCurrency || '')}`.trim();
    if (t.creditAmount == null || t.destinationCurrency === t.sourceCurrency) {
        return sent;
    }
    return `${sent} → ${Number(t.creditAmount).toFixed(2)} ${escapeHtml(t.destinationCurrency)}`;
}
```

- [ ] **Step 3: Grafana panel**

Create `docker/grafana/dashboards/fx-cache.json`:

```json
{
  "title": "Microservices Showcase — FX Cache",
  "uid": "showcase-fx-cache",
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "10s",
  "time": { "from": "now-15m", "to": "now" },
  "panels": [
    {
      "id": 1,
      "title": "Cache hit ratio (5m)",
      "type": "stat",
      "gridPos": { "h": 6, "w": 6, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 } },
      "targets": [
        {
          "expr": "sum(increase(fx_cache_requests_total{result=\"hit\"}[5m])) / clamp_min(sum(increase(fx_cache_requests_total[5m])), 1)",
          "refId": "A"
        }
      ]
    },
    {
      "id": 2,
      "title": "Cache lookups by result",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 18, "x": 6, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "sum by (result) (rate(fx_cache_requests_total[1m]))", "legendFormat": "{{result}}", "refId": "A" }
      ]
    },
    {
      "id": 3,
      "title": "Provider calls by outcome",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "sum by (outcome) (rate(fx_provider_calls_total[1m]))", "legendFormat": "{{outcome}}", "refId": "A" }
      ]
    },
    {
      "id": 4,
      "title": "Stale serves and Redis bypasses (since start)",
      "type": "stat",
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "sum(fx_cache_requests_total{result=\"stale\"})", "legendFormat": "stale", "refId": "A" },
        { "expr": "sum(fx_cache_requests_total{result=\"bypass\"})", "legendFormat": "bypass", "refId": "B" }
      ]
    }
  ]
}
```

Check `docker/grafana/provisioning/dashboards/dashboards.yml` loads every JSON file in the
dashboards directory. If it lists files one by one, add this one.

- [ ] **Step 4: Docs**

`docs/microservices-showcase-design.md`:
- §2 table: add after the Fraud row
  `| **FX** | Exchange rates for cross-currency transfers, from the Frankfurter/ECB feed behind a Redis cache (fresh TTL, last-known fallback, cross-instance single-flight lock) | stateless (Redis is a cache, not a store) |`.
  In the Account row, add "each account held in one currency, fixed at creation".
- §3: add the bullet
  `- **Cache:** Redis (single node, no persistence), used only by FX Service in front of its external rate provider. An optimisation, never a dependency: with Redis down, FX Service calls the provider directly. See docs/phase-12-fx-rates-redis-cache.md.`
  In the Resilience bullet, change "(Transfer → Account, Transfer → Fraud)" to
  "(Transfer → Account, Transfer → Fraud, Transfer → FX)".
- §4, Happy path step 1: replace it with
  `1. Transfer Service pre-validates that both accounts exist, learning each one's currency. If the currencies differ it asks FX Service for a rate (see **Rate locking** below). It then inserts the `Transfer` record, status `PENDING`, with the conversion already on it. A failure at this step inserts the record as `FAILED` instead, and nothing moves.`
- §4 step 5: add "It credits the locked `creditAmount`, in the destination's currency."
- §4: add a paragraph after the Happy path list:
  `**Rate locking:** a transfer's amount is always in the source account's currency. The rate, its publication date and the resulting creditAmount (HALF_EVEN, 2 places) are fixed on the row by the same insert that creates it, so no `PENDING` row ever lacks them. Every leg and every compensator replay sends exactly those values and never asks FX again. A re-priced replay would send a new amount under an idempotency key Account has already recorded. Account rejects a debit or credit labelled with the wrong currency (`422 CURRENCY_MISMATCH`), but only for an operation it has not already applied. See docs/phase-12-fx-rates-redis-cache.md.`
- §4, the Phase 4 note's payload field list: add `sourceCurrency`, `destinationCurrency`, `rate`, `creditAmount`.
- §4, "Rejected before or at the debit": add "no exchange rate available (`FX_SERVICE_UNAVAILABLE`), an amount that converts to nothing (`AMOUNT_TOO_SMALL`)".

`docs/open-items.md` §2 ("Deliberately not planned"): add the row
`| FX | Redis high availability and persistence; converted balance display; rate margins or fees; honouring the displayed quote at send time (quote locking); changing an account's currency or multi-currency accounts; an offline provider stub | `phase-12-fx-rates-redis-cache.md` Scope Boundary |`.

`docs/roadmap.md`: set the Phase 12 row's status to "✅ Done".

`README.md`:
- In the `docker compose up` service list, add "FX Service (8085)" and "Redis (6379)".
- In the Swagger list, add `- FX Service — http://localhost:8085/swagger-ui.html`.
- Add a short "Currencies" paragraph near the transfer section: accounts pick a currency at
  signup; cross-currency transfers convert at the ECB reference rate cached in Redis; the rate is
  locked when the transfer is created; without internet access, cross-currency transfers fail as
  `FX_SERVICE_UNAVAILABLE` once the cache is empty.

`CLAUDE.md`:
- "Project status": "Phases 1–9 (including 7b and 8b) and Phase 11" → "Phases 1–9 (including 7b
  and 8b), Phase 11 (Notification persistence + Kafka consumer error handling) and Phase 12
  (multi-currency transfers + Redis-cached FX Service)".
- The Gateway bullet: add `GET /fx/rates` to the routed paths, and change "all of
  Fraud/Notification have no route" to "all of Fraud/Notification, and everything else on FX,
  have no route".

- [ ] **Step 5: Full suite, end-to-end live check, commit, PR**

Run `./mvnw test` from the repo root. Expected: BUILD SUCCESS across the reactor.

End-to-end (`docker compose down -v`, then `GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build`):
1. Register a PLN user and a EUR user at http://localhost:8090.
2. As the PLN user, start a transfer to the EUR user's account id and type an amount. The quote
   line should appear. Send it. The flash message should give the credited EUR amount, and the
   history row should read "100.00 PLN → 22.82 EUR" (or whatever today's rate gives).
3. Check both balances.
4. `docker exec showcase-redis redis-cli KEYS 'fx:*'`.
5. In Grafana (http://localhost:3001), the "FX Cache" dashboard should show hits once you have
   asked for a second quote.
6. `docker stop showcase-redis`. Quotes and transfers should still work. The bypass count rises.
   Then `docker start showcase-redis`.

Put screenshots or output in the PR, open it (`feat(ui): FX quotes through the Gateway, FX cache dashboard (Phase 12 Task 4)`), and **stop**.

After the user merges Task 4, offer a final review of the whole phase: the four merged PRs,
reviewed together on a more capable model (see `CLAUDE.md`'s subagent model policy). Point it at
the Review Focus list, the single-insert rate lock, and the three compensator call sites.
Dispatch it only if the user asks.

