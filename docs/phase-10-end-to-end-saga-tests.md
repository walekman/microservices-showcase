# End-to-End Saga Tests (Phase 10)

**Goal:** Prove, against the real containers and through the Gateway, what the rest of the suite
proves only with mocks. `MockRestServiceServer` stands in for Account, Fraud and FX in Transfer
Service's tests. Here, the saga, compensation, Resilience4j and the saga invariant from
`CLAUDE.md` run across the real network, with real Keycloak tokens, Postgres, Kafka and Redis.
The measure throughout is that **money moves exactly once**, checked against balances rather
than against a transfer's status alone.

**Architecture:** A new `e2e-tests` Maven module, built only under an `e2e` profile. It boots
its own copy of the stack from `docker-compose.yml` plus a `docker-compose.e2e.yml` override,
using Testcontainers' `ComposeContainer`. Two WireMock containers inject faults. One sits
between Transfer and Account as a programmable proxy, and the other stands in for the FX rate
provider. The tests are a black-box HTTP client: none of the services' code is on their
classpath.

To make a blocked account reachable from a test, Fraud Service gains a persistent blocklist in
its own `fraud` database, managed through two new `fraud-admin` endpoints. This replaces the
`FRAUD_BLOCKLIST_ACCOUNT_IDS` env var.

**Tech Stack:** Testcontainers `ComposeContainer` (local Compose), the WireMock Java client
(admin API only) against `wiremock/wiremock` containers, Awaitility, AssertJ, JDK
`HttpClient`. Fraud Service adds `spring-boot-starter-data-jpa` and the Postgres driver. All
versions come from Boot's BOM or the root `pom.xml` pins, and any new pin is added there.

**Spec:** This document, brainstormed with the user on 2026-09-25. The task-by-task
implementation is added to it once this design is approved.

## Global Constraints

- Java 21; Spring Boot 3.5.16. Use `C:\dev\openjdk-21.0.2` as `JAVA_HOME`. (CLAUDE.md)
- **Phase branch.** All Phase 10 work lands on `feature/phase-10`, which has a long-lived PR
  into `master` (opened with this spec, not merged until the phase is done). Each task gets
  its own branch off the current `feature/phase-10` and its own PR back into
  `feature/phase-10`, stopping after each. At the end of the phase the whole
  `feature/phase-10` → `master` PR is reviewed and merged in one go.
- **Local only.** The E2E suite is not part of `./mvnw test` and not part of CI.
  `./mvnw test` and `.github/workflows/ci.yml` must behave exactly as before this phase. The
  E2E suite runs with `./mvnw -Pe2e -pl e2e-tests verify`.
- **The saga invariant holds unchanged.** Nothing in this phase touches `TransferService` or
  `CompensationScheduler`. Scenario 5 below exists to prove the invariant, not to change it.
- Schemas evolve through `ddl-auto: update`, as elsewhere. The new `fraud` database is created
  by `docker/postgres/init-db.sh`, which only runs on an empty volume. After pulling Task 1,
  add `FRAUD_DB_PASSWORD` to your `.env` and run `docker compose down -v` before `up`.
- **Before any task that runs the E2E suite, the coordinating session brings down every other
  worktree's Compose stack** (CLAUDE.md, Executing implementation phases). The e2e override
  removes fixed container names and host ports, so the E2E stack can run beside a dev stack
  from the *same* checkout. Stacks from other worktrees still compete for memory and for the
  images they build.

## Fraud Service: a persistent blocklist

Today Fraud reads a list of account UUIDs from `FRAUD_BLOCKLIST_ACCOUNT_IDS` at startup, but
Account generates account IDs at creation. A test cannot blocklist an account it has not
created yet, so the blocklist becomes data that can be changed at runtime.

- **A new `fraud` database**, following the pattern of the other databases:
  - `fraud` and `fraud_service` in `docker/postgres/init-db.sh`
  - `FRAUD_DB_PASSWORD` in `.env.example` (and in each developer's own `.env`)
  - `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` and `depends_on: postgres
    (service_healthy)` on `fraud-service` in Compose
  - JPA with `ddl-auto: update` and `open-in-view: false`
- **Entity `BlockedAccount`**: `accountId` (UUID, primary key), `blockedAt` (`Instant`). It
  follows the Lombok pattern in `account-service`'s `Account.java`.
  `BlockedAccountRepository extends JpaRepository<BlockedAccount, UUID>`.
- **`FraudCheckService.check(accountId)`** becomes `repository.existsById(accountId)`.
  - A database failure surfaces as a plain `500`.
  - Transfer's `FraudClient` maps a 5xx to `FraudServiceUnavailableException`. That is retried
    and recorded by the `fraudService` breaker. Before the debit it fails the transfer cleanly
    (`SOURCE_FRAUD_SERVICE_UNAVAILABLE`); after the debit it strands the transfer for
    compensation (`DESTINATION_FRAUD_SERVICE_UNAVAILABLE`).
  - The screen stays fail-closed: a Fraud database outage never lets a blocked account
    through.
  - (The 5xx mapping is confirmed against `FraudClient` when the plan is written.)
- **`PUT /fraud/blocklist/{accountId}`** blocks an account and returns `204`.
  - It is idempotent: an existing row is left alone.
  - A concurrent duplicate insert's `DataIntegrityViolationException` also counts as success.
- **`DELETE /fraud/blocklist/{accountId}`** unblocks an account. It returns `204`, including
  when the account was not blocked.
- **Security:**
  - Both endpoints require the new realm role `fraud-admin`: `401` without a token, `403`
    without the role.
  - `/fraud-check` keeps requiring `fraud-checker`.
  - Validation and error responses follow Fraud's existing RFC 7807 `ApiExceptionHandler`
    (e.g. a malformed UUID gives a `400` problem with a stable `code`).
- **No Gateway route** to either endpoint, asserted in the existing `GatewayRoutingIT`, as
  `debit`/`credit` already are.
- **Removed:** `FraudBlocklistProperties`, the `fraud.blocklist.account-ids` property, and
  `FRAUD_BLOCKLIST_ACCOUNT_IDS` in Compose, `.env.example` and the README. A new database
  starts with an empty blocklist, and there is no seed data.

### Keycloak

- New realm role `fraud-admin`, held only by the existing `admin` demo user.

### Docs touched by this change

- `docs/microservices-showcase-design.md`: Fraud is no longer "stateless, no database"; it
  owns the `fraud` database and a blocklist API.
- `CLAUDE.md`: the Fraud line in the service list, and Postgres's database-per-service list.
- `docs/phase-5-fraud-service.md`: a note at the top saying Phase 10 replaced the env-var
  blocklist with the `fraud` database. Its code blocks are marked superseded, not rewritten,
  as the springdoc pins in phases 2–4 were.

## E2E harness

### Module

- `e2e-tests/` is listed in the root `pom.xml` **only inside `<profile><id>e2e</id>`**, so the
  default reactor (and therefore CI) never builds it.
- Test classes are named `*E2E` and run through Failsafe in `verify`, so no module's Surefire
  run picks them up by accident.
- Dependencies (test scope):
  - Testcontainers (core, junit-jupiter)
  - `org.wiremock:wiremock` for its admin client only
  - Awaitility
  - AssertJ
  - Jackson, for response bodies

  HTTP goes through the JDK `HttpClient`.

### The stack: `E2EStack`

- A singleton, started once per JVM on first use, that wraps `ComposeContainer` over
  `docker-compose.yml` and `docker-compose.e2e.yml`:
  - `withLocalCompose(true)`, because the override uses `!reset`, which needs Compose ≥ 2.24.
    The local version is 5.1.3.
  - `withBuild(true)`, so the images are built from the working tree
  - a health wait on every application service
- It is torn down at JVM exit, with its volumes, so every run starts from empty databases.
- Compose gives the stack its own random project name, so its volumes and network never touch
  a dev stack's.
- A cold run spends several minutes building six images and starting Keycloak. Later runs
  reuse Docker's build cache.
- Prerequisites: Docker running, and a `.env` in the repo root.

### `docker-compose.e2e.yml`

- `container_name: !reset null` and `ports: !reset []` on every service. The E2E stack then
  holds no fixed names or host ports, and runs beside a dev stack.
- **`account-proxy`**: `wiremock/wiremock`, whose default mapping (loaded from a mounted
  mappings directory) forwards every request to `http://account-service:8081`.
- **`fx-provider`**: `wiremock/wiremock` with stubbed Frankfurter responses for the currency
  pairs the tests use.
- **`transfer-service`** overrides:
  - `ACCOUNT_SERVICE_URL=http://account-proxy:8080`
  - `TRANSFER_COMPENSATION_SWEEP_INTERVAL=5s`
  - `TRANSFER_COMPENSATION_PENDING_STALE_AFTER=20s`
- **`fx-service`**: `FX_PROVIDER_URL=http://fx-provider:8080`. The stack never calls the real
  provider.
- Only Transfer goes through `account-proxy`. The Gateway still reaches Account directly, so
  a test's own balance reads never hit an injected fault.
- The observability containers stay: excluding them is not worth the override complexity,
  and Transfer exports traces to the collector either way.

### Host access

Tests reach these through Testcontainers-mapped random ports:
- the Gateway: everything a customer does
- Keycloak: users and tokens
- Fraud: the blocklist
- Transfer's `/actuator/prometheus`: breaker state
- both WireMock admin APIs

Tokens are issued with `iss=http://localhost:8180/realms/showcase` whatever port Keycloak is
called on, because `KC_HOSTNAME` fixes it. That is what every service's `KEYCLOAK_ISSUER_URI`
validates, so random host ports do not break JWT validation.

### Fixtures

- **`TestUsers`** creates a fresh customer for each test through Keycloak's admin REST API
  (master realm, `admin`/`admin`): a random username, a password, and the `customer`
  composite role. It then gets that user's token with a password grant through `showcase-ui`,
  which already allows direct grants.
  - Fresh users are required: Phase 9 allows one account per owner.
  - They also isolate each test's balances from every other test's.
- **`Bank`**: a thin client over the Gateway.
  - `createAccount(user, currency, initialBalance)`; the initial balance is client-supplied,
    so each test chooses exact starting balances
  - `account(user, id)`
  - `transfer(user, from, to, amount, idempotencyKey)`, returning the status code and body
  - `getTransfer(user, id)`
- **`AccountProxy`** (the WireMock admin API on `account-proxy`):
  - `unreachable()`: every path answers with a `CONNECTION_RESET_BY_PEER` fault
  - `delayDebitResponses(Duration)`: `POST /accounts/{id}/debit` is forwarded to Account and
    its response held for the given time
  - `reset()`: back to the default forwarding mapping
- **`FxProvider`**: `rate(base, quote, rate, date)` and `reset()`.
- **`FraudAdmin`**: `block(accountId)` / `unblock(accountId)` with the `admin` user's token.
- **`TransferMetrics`**: reads
  `resilience4j_circuitbreaker_state{name="accountService",state="..."}` from Transfer's
  Prometheus text.
- **Cleanup:**
  - Every test resets both WireMock instances and unblocks whatever it blocked, in
    `@AfterEach`.
  - The breaker test does not finish until the breaker reads `closed` again.
  - Test order therefore never matters.

## Scenarios

Each scenario is its own test, with fresh users and accounts that start at 1000.00 unless
stated. Every scenario asserts final balances through the Gateway.

| # | Test | Setup and fault | Asserts |
|---|---|---|---|
| 1 | `happyPath` | none | `201`, `COMPLETED`. Source −X, destination +X. `GET /transfers/{id}` returns the same transfer. Resending the same request with the same `Idempotency-Key` returns the same transfer, and the balances do not move again |
| 2 | `sourceBlocked` | `block(source)` | `422` problem, `code: SOURCE_ACCOUNT_BLOCKED`; the transfer reads `FAILED`. Both balances unchanged |
| 3 | `destinationBlockedIsCompensated` | `block(destination)` | `500` problem, `code: COMPENSATION_REQUIRED`; `GET /transfers/{id}` shows `failureCode: DESTINATION_ACCOUNT_BLOCKED`. Awaitility (≤ 60 s) waits for `COMPENSATED`. Source back to 1000.00; destination unchanged |
| 4 | `accountUnreachableTripsBreaker` | `unreachable()` | Each transfer fails cleanly: `503` problem, `code: ACCOUNT_SERVICE_UNAVAILABLE`, transfer `FAILED`, no balance moved. Transfers are sent until the `accountService` breaker reads `open` (bounded at 20 attempts, never an exact count). After `reset()`, within ~30 s a transfer completes and the breaker reads `closed` |
| 5 | `lostDebitResponseSettledBySweep` | `delayDebitResponses(7s)` | The response is `503` with `transferStatus: PENDING`, and the source is **already** debited exactly once (read through the Gateway, which bypasses the proxy). After `reset()`, within ~90 s the stale-`PENDING` sweep settles the transfer as `COMPLETED`. Final balances: source −X once, destination +X once |
| 6 | `crossCurrency` | `FxProvider` stubs EUR→PLN at a fixed rate; EUR source, PLN destination | `COMPLETED`. The response's `rate` equals the stub's and `creditAmount = amount × rate` (`HALF_EVEN`, 2 dp). The destination is credited `creditAmount` |

### Scenario notes

- **Scenario 4, which layer answers.** Before the breaker opens, the failure is a real I/O
  error through three retries. After it opens, it is `CallNotPermittedException`. The test
  asserts only what the API shows (`503`, `ACCOUNT_SERVICE_UNAVAILABLE`), which is the same for
  both.
- **Scenario 4, breaker thresholds.** The window is shared with every earlier call in the run,
  so the test does not rely on the tuned numbers (`sliding-window-size: 30`,
  `minimum-number-of-calls: 15`). It sends until the metric flips. With pre-validation making
  3 physical calls per transfer, that takes at most about 10 transfers.
- **Scenario 5, why a delay after forwarding.** WireMock forwards the debit to Account, then
  holds the response past Transfer's 5 s read timeout. Account commits, and Transfer sees an
  I/O error it cannot tell apart from non-delivery. That is exactly the case the saga
  invariant is about.
  - Each of the three retries does the same under the key `<transferId>:debit`, and Account's
    `AccountOperation` ledger turns the second and third into replays.
  - While the stub is still active, a sweep may replay once more and time out. The transfer
    stays `PENDING`, which is correct; the test resets the stub straight after the first
    response.
- **Scenario 5, the Gateway's own timeout.** The request takes about 15 s (3 × 5 s plus
  backoff). The plan confirms the Gateway does not time out first. If it does, the e2e
  override raises it, because the test depends on the saga's `503`, not the Gateway's.
- **Scenario 6** also shows that FX Service works end to end against the stub. Its Redis
  cache is empty at stack start, so the first quote goes to the stub.

## Design Decisions

- **Local only, not CI.** A full stack build on every PR adds several minutes per run and a
  heavy Docker workload to CI, for flows `./mvnw test` already covers with mocks. The E2E
  suite is run before merging changes that touch the saga, the compensator or cross-service
  contracts.
- **The tests boot their own stack, rather than using a running one.** Every run starts clean
  with the config the tests need: the proxy, a short stale-`PENDING` threshold, the FX stub.
  A developer's dev stack has none of these, and state builds up in it.
- **`ComposeContainer` over the real `docker-compose.yml`, not containers defined in Java.**
  Defining each service in Java would duplicate the Compose config and drift from it. The
  override changes only what the tests need.
- **WireMock, not Toxiproxy.** Toxiproxy faults apply to the whole connection, whatever the
  request. The saga pre-validates both accounts with `GET`s over the same link before the
  debit, so delaying every response makes pre-validation time out, and the debit never
  happens. Scenario 5 needs a fault on the debit alone, which a WireMock path stub gives.
  - The cost: a WireMock connection reset simulates a network failure rather than being a
    refused socket. At the Resilience4j layer both are an I/O error and become
    `AccountServiceUnavailableException`.
  - One tool covers both the proxy and the FX stub.
- **A persistent Fraud blocklist, not a test-only hook.** Three options were considered:
  - seeding a fixed-ID account over JDBC: couples the tests to Account's schema
  - restarting Fraud with a new env var: 20–40 s per restart, and awkward inside a
    `ComposeContainer`
  - a runtime blocklist: chosen

  Keeping the blocklist in memory would have lost runtime changes on every restart. So it
  moves to Fraud's own database, and the env var goes, leaving one source of truth rather
  than a seed plus overrides.
- **Balances are the assertion, not status alone.** A transfer can read `COMPLETED` while
  money moved twice. Every scenario checks both legs' balances through the Gateway.
- **Scenarios left out:**
  - *A credit leg that fails after the debit*: scenarios 3 and 5 already exercise the
    stranded-money and replay machinery end to end.
  - *The outbox → Kafka → Notification row*: Phase 11's tests cover it, and checking it here
    would need JDBC access to Notification's database.

## Scope Boundary

- **CI integration** of the E2E suite.
- **A blocklist UI, or listing the blocklist** (`GET /fraud/blocklist`): the two write
  endpoints are enough for tests and operators. A listing is easy to add later.
- **Blocklist audit history** (who blocked, why, unblock history): `blockedAt` only.
- **Chaos against Postgres, Kafka, Redis or Keycloak.** Faults are injected only on the
  Transfer → Account link. Each of those dependencies already has module-level failure tests.
- **Performance or load testing.**
- **Running the E2E stack in parallel** (more than one E2E run at a time).

## Delivery

Every task branch starts from, and its PR targets, `feature/phase-10` (see Global
Constraints).

| Task | Branch | Scope |
|---|---|---|
| 1 | `feature/phase-10-task-1-fraud-blocklist-db` | The `fraud` database, `BlockedAccount`, the blocklist endpoints, `fraud-admin`, removing the env var, Fraud tests, the Gateway no-route test, and the docs listed under "Docs touched by this change" |
| 2 | `feature/phase-10-task-2-e2e-harness` | The `e2e-tests` module and `e2e` profile, `docker-compose.e2e.yml`, `E2EStack`, the fixtures, scenarios 1 and 6 |
| 3 | `feature/phase-10-task-3-fraud-scenarios` | Scenarios 2 and 3 |
| 4 | `feature/phase-10-task-4-fault-injection` | Scenarios 4 and 5; the closing docs |

Closing docs (Task 4):
- `docs/roadmap.md`: the Phase 10 row set to Done, linking this doc
- `docs/open-items.md`: item 1 removed, and the Scope Boundary items above added
- `docs/microservices-showcase-design.md` §6: the end-to-end and fault-injection bullets
  rewritten to describe what was built
- `CLAUDE.md`: project status, and how to run the E2E suite under Local environment
- `README.md`: how to run the E2E suite

Task 1 is ordinary production code covered by `./mvnw test`, and runs no Docker stack.
Tasks 2–4 each end by running the full E2E suite.

## Testing

- **Fraud Service** (Testcontainers Postgres, as Account and Notification already use):
  - `existsById`-backed check: a blocked account gives `403 ACCOUNT_BLOCKED`, an unblocked one
    `200`
  - `PUT` twice gives `204` both times and one row
  - `DELETE` of an absent ID gives `204`
  - a block then an unblock makes `/fraud-check` pass again
  - the blocklist endpoints: `401` without a token, `403` without `fraud-admin`, `400` with a
    stable `code` for a malformed UUID
  - `/fraud-check` still requires `fraud-checker`
  - `OpenApiDocsIT` still passes, and the spec includes the new endpoints
- **Gateway:** no route exists for `/fraud/blocklist/**`.
- **E2E:** scenarios 1–6 above. The suite passing twice in a row from a cold stack is the
  acceptance bar for Task 4, which guards against timing flakiness in scenarios 3–5.
