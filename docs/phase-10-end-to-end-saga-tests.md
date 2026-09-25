# End-to-End Saga Tests (Phase 10)

**Goal:** Prove, against the real containers and through the Gateway, what the rest of the suite
proves only with mocks. `MockRestServiceServer` stands in for Account, Fraud and FX in Transfer
Service's tests. Here, the saga, compensation, Resilience4j and the saga invariant from
`CLAUDE.md` run across the real network, with real Keycloak tokens, Postgres, Kafka and Redis.
The measure throughout is that **money moves exactly once**, checked against balances rather
than against a transfer's status alone.

**Architecture:** A new `e2e-tests` Maven module, built only under an `e2e` profile. It boots
its own copy of the stack from `docker-compose.yml` plus a `docker-compose.e2e.yml` override,
by driving the `docker compose` CLI from Java. Two WireMock containers inject faults. One sits
between Transfer and Account as a programmable proxy, and the other stands in for the FX rate
provider. The tests are a black-box HTTP client: none of the services' code is on their
classpath.

To make a blocked account reachable from a test, Fraud Service gains a persistent blocklist in
its own `fraud` database, managed through two new `fraud-admin` endpoints. This replaces the
`FRAUD_BLOCKLIST_ACCOUNT_IDS` env var.

**Tech Stack:** The `docker compose` CLI (driven through `ProcessBuilder`), `wiremock/wiremock`
containers controlled through WireMock's admin REST API, Awaitility, AssertJ, Jackson, JDK
`HttpClient`. Fraud Service adds `spring-boot-starter-data-jpa` and the Postgres driver. All
versions come from Boot's BOM or the root `pom.xml` pins, and any new pin is added there.

**Spec:** This document, brainstormed with the user on 2026-09-25. The task-by-task
implementation plan follows the spec, under "Implementation Plan".

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
  - It is idempotent: an existing row, and its `blockedAt`, are left alone.
  - The insert is `INSERT … ON CONFLICT (account_id) DO NOTHING`, so two concurrent blocks
    of the same account both succeed without an exception to catch.
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
- Dependencies (test scope), all managed by Boot's BOM:
  - JUnit Jupiter
  - Awaitility
  - AssertJ
  - Jackson, for request and response bodies

  HTTP goes through the JDK `HttpClient`. WireMock is driven through its admin REST API, so
  no WireMock library is needed. Its Java client would also bring Jetty 11 into a build
  whose parent manages Jetty 12.

### The stack: `E2EStack`

- A singleton, started once per JVM on first use. It runs the local `docker compose` CLI
  over `docker-compose.yml` and `docker-compose.e2e.yml`, under a random project name
  (`showcase-e2e-<8 hex>`):
  - `build <service>` for each of the six services, one at a time, then `up --detach --wait`,
    which returns once every service with a healthcheck is healthy. Six parallel builds
    exhausted Docker Desktop's build daemon (see Implementation Notes).
  - a warm-up: one same-currency and one cross-currency transfer, retried until both complete,
    before the first test. A cold stack's first saga took ~20 s.
  - `port <service> <port>`: resolves each mapped host port
  - `down --volumes --remove-orphans` from a JVM shutdown hook, so every run starts from empty
    databases
- Its own project name keeps its volumes and network apart from a dev stack's. The override
  uses `!reset` and `!override`, which need Compose ≥ 2.24.4; the local version is 5.1.3.
- A run killed before its shutdown hook fires (an IDE stop, Ctrl-C twice) leaves its stack
  behind. `docker compose ls` shows it as `showcase-e2e-…`, and
  `docker compose -p <name> down -v` removes it.
- `-De2e.keepStack=true` skips the teardown on purpose, so a failed run's service logs can be
  read with `docker compose -p <name> logs <service>`.
- A cold run spends several minutes building six images and starting Keycloak. Later runs
  reuse Docker's build cache.
- Prerequisites: Docker running, and a `.env` in the repo root.

### `docker-compose.e2e.yml`

- `container_name: !reset null` on every service, and `ports: !reset []` on every service
  the tests do not reach. The E2E stack then holds no fixed names or fixed host ports, and runs
  beside a dev stack.
- `ports: !override ["<container port>"]` (a random host port) on the services the tests do
  reach: `gateway-service`, `keycloak`, `fraud-service`, `transfer-service` and
  `account-proxy`.
- **`account-proxy`**: `wiremock/wiremock`, whose default mapping (loaded from a mounted
  mappings directory) forwards every request to `http://account-service:8081`.
- **`fx-provider`**: `wiremock/wiremock` with one static mapping: Frankfurter's
  `GET /latest?base=EUR` at fixed rates dated 2026-09-24 (`PLN` 4.2537, `USD` 1.1123, `GBP`
  0.8412). No test changes it. FX Service caches the table in Redis, so a stub changed
  mid-run would not be seen anyway.
- **`transfer-service`** overrides:
  - `ACCOUNT_SERVICE_URL=http://account-proxy:8080`
  - `TRANSFER_COMPENSATION_SWEEP_INTERVAL=5s`
  - `TRANSFER_COMPENSATION_PENDING_STALE_AFTER=70s` (above the ~62 s worst-case live saga, so
    the sweep never races a merely slow saga)
- **`fx-service`**: `FX_PROVIDER_URL=http://fx-provider:8080`. The stack never calls the real
  provider.
- Only Transfer goes through `account-proxy`. The Gateway still reaches Account directly, so
  a test's own balance reads never hit an injected fault.
- The observability containers stay: excluding them is not worth the override complexity,
  and Transfer exports traces to the collector either way.

### Host access

Tests reach these through the random host ports Compose maps:
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
- **`FraudAdmin`**: `block(accountId)` / `unblock(accountId)` with the `admin` user's token.
- **`TransferMetrics`**: reads
  `resilience4j_circuitbreaker_state{name="accountService",state="..."}` from Transfer's
  Prometheus text.
- **Cleanup:**
  - Every test resets `account-proxy` and unblocks whatever it blocked, in `@AfterEach`.
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
| 5 | `lostDebitResponseSettledBySweep` | `delayDebitResponses(7s)` | The response is `503` with `transferStatus: PENDING`, and the source is **already** debited exactly once (read through the Gateway, which bypasses the proxy). After `reset()`, within ~150 s the stale-`PENDING` sweep settles the transfer as `COMPLETED`. Final balances: source −X once, destination +X once |
| 6 | `crossCurrency` | The static `fx-provider` stub (EUR→PLN 4.2537); EUR source, PLN destination | `COMPLETED`. The response's `rate` equals the stub's and `creditAmount = amount × rate` (`HALF_EVEN`, 2 dp). The destination is credited `creditAmount` |

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
  - The sweep settles the transfer in two steps (`CompensationScheduler`). The stale-`PENDING`
    sweep replays `<transferId>:debit`, learns the debit landed, and promotes the row to
    `COMPENSATION_REQUIRED`. The next `COMPENSATION_REQUIRED` drain screens the destination and
    credits it with `<transferId>:credit`, which settles it as `COMPLETED`. The test waits for
    the end state only.
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
- **The real `docker-compose.yml` through the `docker compose` CLI, not containers defined in
  Java.** Defining each service in Java would duplicate the Compose config and drift from it.
  The override changes only what the tests need.
- **The CLI rather than Testcontainers' `ComposeContainer`.** `ComposeContainer` parses each
  compose file on its own and throws `IllegalStateException` for any service with
  `container_name` (`ParsedDockerComposeFile.validateNoContainerNameSpecified` in 1.21.4).
  `docker-compose.yml` sets one on every service, and an override cannot prevent the check,
  which runs on each file. Removing `container_name` from the base file would change every
  dev container's name for a test's sake. Three CLI calls (`up --wait`, `port`, `down -v`)
  do everything the tests need.
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
  - `existsById`-backed check: a blocked account gives `422 ACCOUNT_BLOCKED`, an unblocked
    one `200`
  - a database failure during a check gives `500 INTERNAL_ERROR`, never `200`
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

---

# Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fraud's blocklist becomes persisted data behind a `fraud-admin` API. A local-only
`e2e-tests` module then drives six saga scenarios through the Gateway against the real stack,
with WireMock injecting faults between Transfer and Account.

**Architecture:** See the spec above. Task 1 is ordinary production code. Tasks 2–4 add
black-box tests in a module the default reactor never builds, plus one Compose override file
and two WireMock mapping directories.

**Tech Stack:** Spring Boot 3.5.16, Spring Data JPA on Postgres 16 (Fraud), Testcontainers
1.21.4 with `@ServiceConnection` (Fraud's module tests), the `docker compose` CLI,
`wiremock/wiremock:3.13.1`, JUnit 5, AssertJ, Awaitility, Jackson, JDK `HttpClient`.

**Spec:** The top half of this document.

## Global Constraints

- Java 21; `JAVA_HOME=C:\dev\openjdk-21.0.2` before any `./mvnw` call.
- **Branches:** each task branches off the current `feature/phase-10` and opens its PR
  **into `feature/phase-10`**, then stops. Never target `master`, and never merge.
- `./mvnw test` and CI are unchanged: `e2e-tests` is listed only under `<profile><id>e2e`.
  None of the six Dockerfiles copy `e2e-tests/pom.xml`, which is why the module must never
  appear in the default `<modules>`. If it did, every image build would fail to resolve the
  reactor.
- The saga invariant holds unchanged. No task touches `TransferService` or
  `CompensationScheduler` logic (Task 1 edits one comment in `CompensationScheduler`).
- The E2E suite runs with `./mvnw -Pe2e -pl e2e-tests verify`, from the repo root.
- **Before Tasks 2, 3 and 4 (and Task 1's live check), the coordinator runs
  `docker compose ls` and brings down every stack from another worktree.** Implementer
  subagents never stop or remove containers they did not start.
- `.env` is gitignored. After Task 1, every checkout needs `FRAUD_DB_PASSWORD=fraud_service`
  in its `.env`, and the coordinator adds it by hand.

## Review Focus

1. **The Fraud database is down during a check.** Expected: `/fraud-check` answers `500`, so
   Transfer reads Fraud as unavailable and the screen fails closed. It must never answer
   `200`. Pinned by `FraudCheckControllerTest.returns500WhenTheBlocklistCannotBeRead` (Task
   1).
2. **Two concurrent blocks of the same account.** Expected: both succeed, one row, no `500`.
   Pinned by `BlockedAccountRepositoryTest.concurrentBlocksOfTheSameAccountBothSucceed`
   (Task 1).
3. **Blocking an ID that Account has never seen.** Expected: `204`, and the block takes
   effect if such an account is created later. Fraud does not call Account. Pinned by
   `BlocklistControllerIT.blockingAnUnknownAccountIdIsAccepted` (Task 1).
4. **A customer token, which carries `fraud-checker`, calling the blocklist API.** Expected:
   `403`. Pinned by `BlocklistControllerIT.returns403ForACustomerToken` (Task 1).
5. **The E2E suite run while a dev stack is up, and after an aborted run.** Expected: no
   container-name or port collision, and a leftover `showcase-e2e-*` stack is visible in
   `docker compose ls`. Pinned by Task 2 Step 9, which runs the suite with the dev stack up.

---

## Task 1: Fraud blocklist in its own database, behind a `fraud-admin` API

**Branch:** `feature/phase-10-task-1-fraud-blocklist-db`, off `feature/phase-10`.

**Files:**
- Modify: `fraud-service/pom.xml`
- Modify: `fraud-service/src/main/resources/application.yml`
- Create: `fraud-service/src/main/java/com/showcase/fraud/domain/BlockedAccount.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/domain/BlockedAccountRepository.java`
- Modify: `fraud-service/src/main/java/com/showcase/fraud/service/FraudCheckService.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/service/BlocklistService.java`
- Delete: `fraud-service/src/main/java/com/showcase/fraud/service/FraudBlocklistProperties.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/api/BlocklistController.java`
- Modify: `fraud-service/src/main/java/com/showcase/fraud/config/SecurityConfig.java`
- Modify: `fraud-service/src/main/java/com/showcase/fraud/FraudServiceApplication.java` (the OpenAPI description)
- Test: `fraud-service/src/test/java/com/showcase/fraud/domain/BlockedAccountRepositoryTest.java` (create)
- Test: `fraud-service/src/test/java/com/showcase/fraud/api/BlocklistControllerIT.java` (create)
- Test: `fraud-service/src/test/java/com/showcase/fraud/service/FraudCheckServiceTest.java` (rewrite)
- Test: `fraud-service/src/test/java/com/showcase/fraud/api/FraudCheckControllerTest.java` (add Postgres, one test)
- Test: `fraud-service/src/test/java/com/showcase/fraud/{BuildInfoIT,OpenApiDocsIT,TracingBridgeIT}.java` (add Postgres)
- Test: `gateway-service/src/test/java/com/showcase/gateway/GatewayRoutingIT.java` (one test)
- Modify: `docker/postgres/init-db.sh`, `docker-compose.yml`, `.env.example`
- Modify: `docker/keycloak/showcase-realm.json`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java` (one comment)
- Modify: `README.md`, `CLAUDE.md`, `docs/microservices-showcase-design.md`, `docs/phase-5-fraud-service.md`

**Interfaces:**
- Produces (used by Tasks 3–4 over HTTP):
  - `PUT /fraud/blocklist/{accountId}` returns `204`; `DELETE /fraud/blocklist/{accountId}`
    returns `204`. Both need a JWT whose `realm_access.roles` contains `fraud-admin`.
  - The realm's `admin` user (password `password`) holds `fraud-admin`.
  - `/fraud-check?accountId=` is unchanged: `200` clear, `422 ACCOUNT_BLOCKED` blocked.

- [ ] **Step 1: Add the JPA, Postgres and Testcontainers dependencies**

In `fraud-service/pom.xml`, insert after the `spring-boot-starter-oauth2-resource-server`
dependency:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
```

and after `spring-security-test`:

```xml
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
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Configure the datasource and drop the blocklist property**

In `fraud-service/src/main/resources/application.yml`, replace the `spring:` block with:

```yaml
spring:
  application:
    name: fraud-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:fraud}
    username: ${DB_USER:fraud_service}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

Delete the whole `fraud:` block at the end of the file (the `blocklist.account-ids`
property and its comment).

- [ ] **Step 3: Write the failing repository test**

Create `fraud-service/src/test/java/com/showcase/fraud/domain/BlockedAccountRepositoryTest.java`:

```java
package com.showcase.fraud.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class BlockedAccountRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BlockedAccountRepository repository;

    @Test
    void blockingIsIdempotentAndKeepsTheFirstTimestamp() {
        UUID accountId = UUID.randomUUID();
        Instant first = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(repository.blockIfAbsent(accountId, first)).isEqualTo(1);
        assertThat(repository.blockIfAbsent(accountId, first.plusSeconds(60))).isEqualTo(0);

        assertThat(repository.existsById(accountId)).isTrue();
        assertThat(repository.findById(accountId)).get()
                .extracting(BlockedAccount::getBlockedAt).isEqualTo(first);
    }

    @Test
    void unblockingRemovesTheRowAndIsIdempotent() {
        UUID accountId = UUID.randomUUID();
        repository.blockIfAbsent(accountId, Instant.now());

        assertThat(repository.unblock(accountId)).isEqualTo(1);
        assertThat(repository.unblock(accountId)).isEqualTo(0);
        assertThat(repository.existsById(accountId)).isFalse();
    }

    // Outside the test transaction: each call must commit on its own, as it does in production,
    // or the two inserts would never actually race.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentBlocksOfTheSameAccountBothSucceed() throws Exception {
        UUID accountId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Integer>> calls = List.of(
                    CompletableFuture.supplyAsync(() -> blockAfter(start, accountId), pool),
                    CompletableFuture.supplyAsync(() -> blockAfter(start, accountId), pool));
            start.countDown();

            List<Integer> inserted = calls.stream().map(CompletableFuture::join).toList();

            assertThat(inserted).containsExactlyInAnyOrder(0, 1);
            assertThat(repository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            repository.unblock(accountId);
        }
    }

    private int blockAfter(CountDownLatch start, UUID accountId) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return repository.blockIfAbsent(accountId, Instant.now());
    }
}
```

`concurrentBlocksOfTheSameAccountBothSucceed` asserts `count() == 1` over the whole table. It
holds because it runs outside a transaction and cleans up after itself, and the other two
tests roll back.

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw -pl fraud-service -Dtest=BlockedAccountRepositoryTest test`
Expected: compilation FAILURE, `cannot find symbol: class BlockedAccountRepository`.

- [ ] **Step 5: Create the entity and repository**

`fraud-service/src/main/java/com/showcase/fraud/domain/BlockedAccount.java`:

```java
package com.showcase.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One blocklisted account. Rows are written only through BlockedAccountRepository's two
 * queries, never through save(): the id is assigned rather than generated, so save() would
 * merge (a SELECT, then an UPDATE of blockedAt) instead of inserting.
 */
@Entity
@Table(name = "blocked_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlockedAccount {

    @Id
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private Instant blockedAt;
}
```

`fraud-service/src/main/java/com/showcase/fraud/domain/BlockedAccountRepository.java`:

```java
package com.showcase.fraud.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface BlockedAccountRepository extends JpaRepository<BlockedAccount, UUID> {

    /**
     * Returns 1 if this call blocked the account, 0 if it was already blocked. ON CONFLICT makes
     * two concurrent blocks of the same account both succeed, with no constraint violation to
     * catch, and leaves the first blockedAt in place.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO blocked_accounts (account_id, blocked_at) VALUES (:accountId, :blockedAt) "
            + "ON CONFLICT (account_id) DO NOTHING", nativeQuery = true)
    int blockIfAbsent(@Param("accountId") UUID accountId, @Param("blockedAt") Instant blockedAt);

    /** Returns 1 if the account was blocked, 0 if it was not. */
    @Modifying
    @Transactional
    @Query("DELETE FROM BlockedAccount b WHERE b.accountId = :accountId")
    int unblock(@Param("accountId") UUID accountId);
}
```

- [ ] **Step 6: Run the repository test to verify it passes**

Run: `./mvnw -pl fraud-service -Dtest=BlockedAccountRepositoryTest test`
Expected: PASS, 3 tests. If `blockedAt` comes back with different precision, check that the
test truncates to micros (Postgres `timestamp(6)`). Do not loosen the assertion.

- [ ] **Step 7: Rewrite the check-service test against the repository**

Replace `fraud-service/src/test/java/com/showcase/fraud/service/FraudCheckServiceTest.java`:

```java
package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.fraud.domain.BlockedAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudCheckServiceTest {

    private static final UUID BLOCKED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLEAR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final BlockedAccountRepository repository = mock(BlockedAccountRepository.class);
    private final FraudCheckService service = new FraudCheckService(repository);

    @Test
    void throwsForABlockedAccount() {
        when(repository.existsById(BLOCKED)).thenReturn(true);

        assertThatThrownBy(() -> service.check(BLOCKED))
                .isInstanceOf(AccountBlockedException.class)
                .hasMessageContaining(BLOCKED.toString());
    }

    @Test
    void passesForAnUnlistedAccount() {
        when(repository.existsById(CLEAR)).thenReturn(false);

        assertThatCode(() -> service.check(CLEAR)).doesNotThrowAnyException();
    }

    // Fail closed: an unreadable blocklist must never read as "not blocked".
    @Test
    void propagatesADatabaseFailureRatherThanPassingTheAccount() {
        when(repository.existsById(BLOCKED)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.check(BLOCKED)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
```

- [ ] **Step 8: Run it to verify it fails**

Run: `./mvnw -pl fraud-service -Dtest=FraudCheckServiceTest test`
Expected: compilation FAILURE: `FraudCheckService(BlockedAccountRepository)` does not exist.

- [ ] **Step 9: Point the check at the database and add the blocklist service**

Replace `fraud-service/src/main/java/com/showcase/fraud/service/FraudCheckService.java`:

```java
package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.fraud.domain.BlockedAccountRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FraudCheckService {

    private final BlockedAccountRepository repository;

    public FraudCheckService(BlockedAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * A database failure propagates as a 500. Transfer's FraudClient reads any 5xx as "Fraud
     * unavailable", so an unreadable blocklist fails the screen closed, never open.
     */
    public void check(UUID accountId) {
        if (repository.existsById(accountId)) {
            throw new AccountBlockedException(accountId);
        }
    }
}
```

Create `fraud-service/src/main/java/com/showcase/fraud/service/BlocklistService.java`:

```java
package com.showcase.fraud.service;

import com.showcase.fraud.domain.BlockedAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Adds and removes blocklist entries. Both operations are idempotent. Neither checks that the
 * account exists: Fraud never calls Account, and a block placed before an account is created
 * still applies once it is.
 */
@Service
public class BlocklistService {

    private static final Logger log = LoggerFactory.getLogger(BlocklistService.class);

    private final BlockedAccountRepository repository;

    public BlocklistService(BlockedAccountRepository repository) {
        this.repository = repository;
    }

    public void block(UUID accountId) {
        if (repository.blockIfAbsent(accountId, Instant.now()) == 1) {
            log.info("Account {} added to the blocklist", accountId);
        }
    }

    public void unblock(UUID accountId) {
        if (repository.unblock(accountId) == 1) {
            log.info("Account {} removed from the blocklist", accountId);
        }
    }
}
```

Delete `fraud-service/src/main/java/com/showcase/fraud/service/FraudBlocklistProperties.java`.

- [ ] **Step 10: Run the service test to verify it passes**

Run: `./mvnw -pl fraud-service -Dtest=FraudCheckServiceTest test`
Expected: PASS, 3 tests.

- [ ] **Step 11: Give every full-context test a Postgres**

The service now needs a datasource to start, so every `@SpringBootTest` in `fraud-service`
gets a container. In each of `FraudCheckControllerTest`, `BuildInfoIT`, `OpenApiDocsIT` and
`TracingBridgeIT`, add `@Testcontainers` to the class and this field as its first member:

```java
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
```

with the imports:

```java
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
```

In `FraudCheckControllerTest`, update the class comment's last sentence ("Fraud Service has
no database/Kafka, so a full context boot here needs no Testcontainers/Docker.") to: "Fraud
Service owns a database since Phase 10, so the full context needs the Postgres container
below." Then add this test (imports:
`org.springframework.dao.DataAccessResourceFailureException`):

```java
    // Review Focus 1: an unreadable blocklist fails closed. Transfer reads a 5xx as "Fraud
    // unavailable", never as "clear".
    @Test
    void returns500WhenTheBlocklistCannotBeRead() throws Exception {
        doThrow(new DataAccessResourceFailureException("db down")).when(fraudCheckService).check(ACCOUNT_ID);

        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString())
                        .with(jwt().authorities(() -> "fraud-checker")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
```

In `OpenApiDocsIT`, add to the existing chain:

```java
                .andExpect(jsonPath("$.paths['/fraud/blocklist/{accountId}'].put").exists())
                .andExpect(jsonPath("$.paths['/fraud/blocklist/{accountId}'].delete").exists())
```

- [ ] **Step 12: Write the failing controller test**

Create `fraud-service/src/test/java/com/showcase/fraud/api/BlocklistControllerIT.java`:

```java
package com.showcase.fraud.api;

import com.showcase.fraud.domain.BlockedAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The blocklist API against a real database, through the real SecurityConfig. The block and
 * unblock tests read the effect back through /fraud-check, the endpoint Transfer actually calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BlocklistControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RequestPostProcessor FRAUD_ADMIN = jwt().authorities(() -> "fraud-admin");
    private static final RequestPostProcessor FRAUD_CHECKER = jwt().authorities(() -> "fraud-checker");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BlockedAccountRepository repository;

    @AfterEach
    void clearBlocklist() {
        repository.deleteAllInBatch();
    }

    @Test
    void aBlockedAccountFailsTheFraudCheck() throws Exception {
        UUID accountId = UUID.randomUUID();

        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/fraud-check").param("accountId", accountId.toString()).with(FRAUD_CHECKER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void blockingTwiceIsIdempotent() throws Exception {
        UUID accountId = UUID.randomUUID();

        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN)).andExpect(status().isNoContent());
        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN)).andExpect(status().isNoContent());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void anUnblockedAccountPassesTheFraudCheckAgain() throws Exception {
        UUID accountId = UUID.randomUUID();
        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN)).andExpect(status().isNoContent());

        mockMvc.perform(delete("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/fraud-check").param("accountId", accountId.toString()).with(FRAUD_CHECKER))
                .andExpect(status().isOk());
    }

    @Test
    void unblockingAnAccountThatIsNotBlockedIsAccepted() throws Exception {
        mockMvc.perform(delete("/fraud/blocklist/{id}", UUID.randomUUID()).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());
    }

    // Review Focus 3: Fraud never asks Account whether the id exists.
    @Test
    void blockingAnUnknownAccountIdIsAccepted() throws Exception {
        UUID neverCreated = UUID.randomUUID();

        mockMvc.perform(put("/fraud/blocklist/{id}", neverCreated).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());

        assertThat(repository.existsById(neverCreated)).isTrue();
    }

    @Test
    void returns401WithNoToken() throws Exception {
        mockMvc.perform(put("/fraud/blocklist/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/fraud/blocklist/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    // Review Focus 4: a customer's token carries fraud-checker (via the customer composite),
    // which must not be enough to change the blocklist.
    @Test
    void returns403ForACustomerToken() throws Exception {
        mockMvc.perform(put("/fraud/blocklist/{id}", UUID.randomUUID()).with(FRAUD_CHECKER))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/fraud/blocklist/{id}", UUID.randomUUID()).with(FRAUD_CHECKER))
                .andExpect(status().isForbidden());
        assertThat(repository.count()).isZero();
    }

    @Test
    void returns400ForAMalformedAccountId() throws Exception {
        mockMvc.perform(put("/fraud/blocklist/{id}", "not-a-uuid").with(FRAUD_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
```

- [ ] **Step 13: Run it to verify it fails**

Run: `./mvnw -pl fraud-service -Dtest=BlocklistControllerIT test`
Expected: FAIL. No handler exists yet, so a request that passes `anyRequest().authenticated()`
gets `404` where the test expects `204`. The tests that expect `401`/`403` may already pass;
that is fine.

- [ ] **Step 14: Add the controller and its security rule**

Create `fraud-service/src/main/java/com/showcase/fraud/api/BlocklistController.java`:

```java
package com.showcase.fraud.api;

import com.showcase.fraud.service.BlocklistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator API for the blocklist, gated by fraud-admin (see SecurityConfig). No Gateway route
 * reaches it: callers talk to Fraud Service directly.
 */
@RestController
@RequestMapping("/fraud/blocklist")
public class BlocklistController {

    private final BlocklistService blocklistService;

    public BlocklistController(BlocklistService blocklistService) {
        this.blocklistService = blocklistService;
    }

    @PutMapping("/{accountId}")
    public ResponseEntity<Void> block(@PathVariable UUID accountId) {
        blocklistService.block(accountId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> unblock(@PathVariable UUID accountId) {
        blocklistService.unblock(accountId);
        return ResponseEntity.noContent().build();
    }
}
```

In `SecurityConfig`, add after the `/fraud-check` matcher:

```java
                        .requestMatchers("/fraud/blocklist/**").hasAuthority("fraud-admin")
```

and extend the class javadoc with one sentence: "fraud-admin gates the blocklist API, and is
held only by the realm's admin user."

In `FraudServiceApplication`, change the OpenAPI description to
`"Account-blocklist screen for the transfer saga, and the operator API that maintains the blocklist."`

- [ ] **Step 15: Run the whole Fraud module**

Run: `./mvnw -pl fraud-service test`
Expected: PASS: every class, including `BlocklistControllerIT` (8 tests),
`BlockedAccountRepositoryTest` (3), `FraudCheckServiceTest` (3), `FraudCheckControllerTest`
(7), `OpenApiDocsIT`, `BuildInfoIT` and `TracingBridgeIT`.

- [ ] **Step 16: Assert the Gateway has no route to the blocklist**

In `gateway-service/src/test/java/com/showcase/gateway/GatewayRoutingIT.java`, add after
`creditPathIsUnreachableThroughTheGateway` (imports: `org.springframework.http.HttpMethod`,
`java.util.UUID`):

```java
    @Test
    void fraudBlocklistIsUnreachableThroughTheGateway() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/fraud/blocklist/" + UUID.randomUUID(), HttpMethod.PUT, null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
```

Run: `./mvnw -pl gateway-service -Dtest=GatewayRoutingIT test`
Expected: PASS. It passes immediately, because no route exists; it guards against one being
added.

- [ ] **Step 17: Create the database, wire Compose, add the role**

`docker/postgres/init-db.sh`: append

```bash

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE fraud;
    CREATE USER fraud_service WITH PASSWORD '$FRAUD_DB_PASSWORD';
    GRANT ALL PRIVILEGES ON DATABASE fraud TO fraud_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "fraud" <<-EOSQL
    GRANT ALL PRIVILEGES ON SCHEMA public TO fraud_service;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO fraud_service;
EOSQL
```

`.env.example`: append `FRAUD_DB_PASSWORD=fraud_service`.

`docker-compose.yml`:
- `postgres.environment`: add `FRAUD_DB_PASSWORD: ${FRAUD_DB_PASSWORD}` after `NOTIFICATION_DB_PASSWORD`.
- `fraud-service.environment`: replace `FRAUD_BLOCKLIST_ACCOUNT_IDS: ${FRAUD_BLOCKLIST_ACCOUNT_IDS:-}` with

```yaml
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: fraud
      DB_USER: fraud_service
      DB_PASSWORD: ${FRAUD_DB_PASSWORD}
```

- `fraud-service.depends_on`: add, before `keycloak`,

```yaml
      postgres:
        condition: service_healthy
```

`docker/keycloak/showcase-realm.json`:
- In `roles.realm`, after the `fraud-checker` entry, add:

```json
      {
        "name": "fraud-admin",
        "description": "Can add and remove accounts on Fraud Service's blocklist (PUT/DELETE /fraud/blocklist/{accountId}) -- held only by the admin demo user, never by the customer composite; no Gateway route"
      },
```

- The `admin` user's `realmRoles`: `["account-admin", "transfer-admin", "fraud-admin"]`.

Match the file's existing indentation. Validate the JSON:
`python -c "import json; json.load(open('docker/keycloak/showcase-realm.json'))"`.

- [ ] **Step 18: Update the one stale code comment and the docs**

- `CompensationScheduler.reconcileDebit`: the comment's last sentence currently reads
  "Reaching this needs the source blocklisted after the live saga's own screen passed, i.e. a
  Fraud reconfiguration and restart." Change the tail to "...own screen passed, i.e. a
  `PUT /fraud/blocklist/{id}` in between." Comment only; no logic changes.
- `README.md`:
  - Under "Running locally", after the Phase 11 `.env` sentence, add: "An `.env` copied
    before Phase 10 lacks `FRAUD_DB_PASSWORD`; add that line too, and run
    `docker compose down -v` so the init script creates the `fraud` database."
  - Replace the "Trigger a blocked-source rejection" comment block (currently lines
    ~210–215) with:

```
    # Blocklist an account (Fraud Service directly -- there is no Gateway route; needs the
    # admin token, which holds fraud-admin), then transfer FROM it for a clean rejection
    # (no money moves), or TO it for a compensation (money moves, then reverses
    # automatically). DELETE the same URL to lift the block.
    curl -X PUT http://localhost:8084/fraud/blocklist/<id> -H "Authorization: Bearer $ADMIN_TOKEN"
```

  - Grep for any other `FRAUD_BLOCKLIST` or "stateless" Fraud mention and update it.
- `CLAUDE.md`:
  - The Fraud line becomes "**Fraud Service** (`fraud-service/`, port 8084) — account-blocklist
    screen; the blocklist lives in its own `fraud` database (JPA on Postgres) and is
    maintained through a `fraud-admin`-only `PUT`/`DELETE /fraud/blocklist/{id}` with no
    Gateway route."
  - In the Gateway line, "all of Fraud/Notification" still holds; leave it.
- `docs/microservices-showcase-design.md`:
  - Component table row: Fraud "Screens each account in a transfer against a blocklist it
    stores (no amount/velocity rules); `fraud-admin` maintains it" | "`fraud` database
    (Postgres)".
  - Mention the blocklist API wherever the design doc lists Fraud's endpoints or Postgres's
    databases (grep `fraud` and `database-per-service`).
- `docs/phase-5-fraud-service.md`: insert after the title line:

```markdown
> **Superseded in part by Phase 10** (`docs/phase-10-end-to-end-saga-tests.md`): the blocklist
> is no longer the `FRAUD_BLOCKLIST_ACCOUNT_IDS` env var. It lives in Fraud's own `fraud`
> database and is maintained through `PUT`/`DELETE /fraud/blocklist/{accountId}` (role
> `fraud-admin`). Code blocks below that use `FraudBlocklistProperties` describe the Phase 5
> design and must not be copied.
```

- [ ] **Step 19: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS across all six modules. Report the reactor-wide test count from the
Maven summary, not one module's.

- [ ] **Step 20: Live check against a fresh local stack**

The coordinator first brings down other worktrees' stacks. Then, with
`FRAUD_DB_PASSWORD=fraud_service` in `.env`:

```bash
docker compose down -v
docker compose up -d --build
```

Once every service is healthy, get the admin token (the README's `ADMIN_TOKEN` curl, with
`username=admin`). Create two accounts as `ada` and `bob` (README), then:

```bash
curl -i -X PUT http://localhost:8084/fraud/blocklist/<ada-account-id> -H "Authorization: Bearer $ADMIN_TOKEN"
# expect 204
curl -i -X PUT http://localhost:8084/fraud/blocklist/<ada-account-id> -H "Authorization: Bearer $ADA_TOKEN"
# expect 403
# transfer 10.00 from ada's account to bob's through the Gateway (README) -> expect 422 SOURCE_ACCOUNT_BLOCKED
curl -i -X DELETE http://localhost:8084/fraud/blocklist/<ada-account-id> -H "Authorization: Bearer $ADMIN_TOKEN"
# expect 204; the same transfer with a new Idempotency-Key -> expect 201 COMPLETED
docker compose restart fraud-service
# block ada again, restart fraud-service, transfer again -> still 422: the block survived the restart
```

Record each actual status in the PR description.

- [ ] **Step 21: Commit and open the PR**

```bash
git add -A fraud-service gateway-service/src/test docker/postgres/init-db.sh docker-compose.yml .env.example \
  docker/keycloak/showcase-realm.json transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java \
  README.md CLAUDE.md docs/microservices-showcase-design.md docs/phase-5-fraud-service.md
git commit -m "feat(fraud): persist the blocklist in a fraud database behind a fraud-admin API"
git push -u origin feature/phase-10-task-1-fraud-blocklist-db
gh pr create --base feature/phase-10 --title "Phase 10 Task 1: Fraud blocklist database and API" --body "<summary, test counts, the Step 20 statuses>"
```

Stop. Do not merge.

---

## Task 2: E2E harness and the happy paths (scenarios 1 and 6)

**Branch:** `feature/phase-10-task-2-e2e-harness`, off `feature/phase-10` **after Task 1 is
merged into it**. The harness assumes Task 1's `fraud` database exists.

**Files:**
- Modify: `pom.xml` (the `e2e` profile)
- Create: `e2e-tests/pom.xml`
- Create: `docker-compose.e2e.yml`
- Create: `docker/e2e/account-proxy/mappings/forward-to-account.json`
- Create: `docker/e2e/fx-provider/mappings/latest-eur.json`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/E2EStack.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/Http.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/HttpResult.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/TestUsers.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/TestUser.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/Bank.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/OpenedAccount.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/E2ETestBase.java`
- Test: `e2e-tests/src/test/java/com/showcase/e2e/TransferHappyPathE2E.java`

**Interfaces:**
- Consumes: Task 1's `fraud` database (Compose must start with it).
- Produces (Tasks 3 and 4 use these exact names):
  - `E2EStack.get()`, with `URI gateway()`, `URI keycloak()`, `URI fraud()`,
    `URI transfer()`, `URI accountProxy()`
  - `Http.send(HttpRequest.Builder) : HttpResult`, `Http.json(Object) : BodyPublisher`,
    `Http.form(Map<String,String>) : BodyPublisher`
  - `record HttpResult(int status, String raw)` with `JsonNode json()`,
    `String text(String field)`, `UUID uuid(String field)`,
    `BigDecimal decimal(String field)`, `HttpResult expect(int status)`
  - `TestUsers(URI keycloak)` with `TestUser create()` and `String showcaseAdminToken()`
  - `record TestUser(String username, String accessToken)`
  - `Bank(URI gateway)` with `OpenedAccount openAccount(TestUser, String currency, String initialBalance)`,
    `BigDecimal balance(OpenedAccount)`,
    `HttpResult transfer(TestUser, OpenedAccount from, OpenedAccount to, String amount)`,
    `HttpResult transfer(TestUser, OpenedAccount from, OpenedAccount to, String amount, String idempotencyKey)`,
    `HttpResult getTransfer(TestUser, UUID transferId)`
  - `record OpenedAccount(UUID id, TestUser owner)`
  - `abstract class E2ETestBase` with `protected static final E2EStack STACK`,
    `protected final TestUsers users`, `protected final Bank bank`

- [ ] **Step 1: Add the profile and the module POM**

In the root `pom.xml`, after `</properties>`:

```xml
  <!-- Local-only end-to-end suite (docs/phase-10-end-to-end-saga-tests.md). Never in the
       default <modules>: CI must not build it, and none of the service Dockerfiles copy its
       pom.xml, so listing it there would break every image build. -->
  <profiles>
    <profile>
      <id>e2e</id>
      <modules>
        <module>e2e-tests</module>
      </modules>
    </profile>
  </profiles>
```

Create `e2e-tests/pom.xml`:

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

  <artifactId>e2e-tests</artifactId>
  <description>Black-box saga tests against the real Compose stack. Run: ./mvnw -Pe2e -pl e2e-tests verify</description>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/*E2E.java</include>
          </includes>
          <systemPropertyVariables>
            <e2e.repoRoot>${project.basedir}/..</e2e.repoRoot>
          </systemPropertyVariables>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Check: `./mvnw -q validate` (no profile) must not list `e2e-tests`, and
`./mvnw -Pe2e -q validate` must.

- [ ] **Step 2: Write the WireMock mappings**

`docker/e2e/account-proxy/mappings/forward-to-account.json`: the default every test returns to.

```json
{
  "priority": 10,
  "request": { "urlPattern": ".*" },
  "response": { "proxyBaseUrl": "http://account-service:8081" }
}
```

`docker/e2e/fx-provider/mappings/latest-eur.json`: the only rates the stack ever sees.

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/latest",
    "queryParameters": { "base": { "equalTo": "EUR" } }
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "amount": 1.0,
      "base": "EUR",
      "date": "2026-09-24",
      "rates": { "GBP": 0.8412, "PLN": 4.2537, "USD": 1.1123 }
    }
  }
}
```

- [ ] **Step 3: Write the Compose override**

`docker-compose.e2e.yml`:

```yaml
# End-to-end overlay: used only by e2e-tests' E2EStack, always together with docker-compose.yml
# and under its own project name. Never run it with a plain `docker compose up`. See
# docs/phase-10-end-to-end-saga-tests.md.
#
# !reset/!override need Compose >= 2.24.4. Every fixed container_name is reset so this stack
# never clashes with a dev stack's names; host ports are either reset or, for the services the
# tests reach, replaced by a random host port (container port only). Running both at once also
# needs the memory for two stacks (~10 GB or more for Docker).
#
# Memory caps live in docker-compose.yml and apply to both stacks.

services:
  postgres:
    container_name: !reset null
    ports: !reset []
  account-service:
    container_name: !reset null
    ports: !reset []
  transfer-service:
    container_name: !reset null
    ports: !override ["8082"]
    environment:
      # Every Transfer -> Account call goes through the fault-injection proxy. The Gateway still
      # reaches Account directly, so the tests' own balance reads never meet an injected fault.
      ACCOUNT_SERVICE_URL: http://account-proxy:8080
      # Shorter than the default 120 s, so the lost-debit scenario settles within a couple of
      # minutes, but still above the slowest possible live saga: four calls, each up to 3 x 5 s
      # read timeouts plus backoff, ~62 s. Below that, the stale-PENDING sweep races a live saga
      # that is merely slow (seen at 20 s on a cold stack), a race the real config never allows.
      TRANSFER_COMPENSATION_SWEEP_INTERVAL: 5s
      TRANSFER_COMPENSATION_PENDING_STALE_AFTER: 70s
    depends_on:
      account-proxy:
        condition: service_started
  kafka:
    container_name: !reset null
    ports: !reset []
  notification-service:
    container_name: !reset null
    ports: !reset []
  fraud-service:
    container_name: !reset null
    ports: !override ["8084"]
  redis:
    container_name: !reset null
    ports: !reset []
  fx-service:
    container_name: !reset null
    ports: !reset []
    environment:
      FX_PROVIDER_URL: http://fx-provider:8080
    depends_on:
      fx-provider:
        condition: service_started
  gateway-service:
    container_name: !reset null
    ports: !override ["8080"]
  web-ui:
    container_name: !reset null
    ports: !reset []
  keycloak:
    container_name: !reset null
    ports: !override ["8080"]
  tempo:
    container_name: !reset null
    ports: !reset []
  otel-collector:
    container_name: !reset null
    ports: !reset []
  prometheus:
    container_name: !reset null
    ports: !reset []
  grafana:
    container_name: !reset null
    ports: !reset []

  account-proxy:
    image: wiremock/wiremock:3.13.1
    command: ["--disable-banner"]
    volumes:
      - ./docker/e2e/account-proxy/mappings:/home/wiremock/mappings:ro
    ports: ["8080"]
  fx-provider:
    image: wiremock/wiremock:3.13.1
    command: ["--disable-banner"]
    volumes:
      - ./docker/e2e/fx-provider/mappings:/home/wiremock/mappings:ro
```

Verify it before writing Java. From the repo root, run
`docker compose -f docker-compose.yml -f docker-compose.e2e.yml config` and check:
- no `container_name`
- `transfer-service` has `ACCOUNT_SERVICE_URL: http://account-proxy:8080`
- the four exposed services show `published: ""` (random) with the right target port
- `ports` is absent for everything else

If the `wiremock/wiremock:3.13.1` tag does not exist, use the newest `3.x` tag and say so in
the PR.

- [ ] **Step 4: Write the stack and HTTP support classes**

`e2e-tests/src/test/java/com/showcase/e2e/support/E2EStack.java`:

```java
package com.showcase.e2e.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * One copy of the whole Compose stack per JVM, started on first use and removed (with its
 * volumes) at JVM exit. Drives the docker compose CLI directly: Testcontainers' ComposeContainer
 * rejects any compose file that sets container_name, which docker-compose.yml does on every
 * service (see docs/phase-10-end-to-end-saga-tests.md, Design Decisions).
 *
 * <p>A run killed before the shutdown hook fires leaves its stack behind: `docker compose ls`
 * lists it as showcase-e2e-*, and `docker compose -p <name> down -v` removes it. Run with
 * -De2e.keepStack=true to keep the stack on purpose, e.g. to read a failed run's service logs.
 */
public final class E2EStack {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("e2e.repoRoot", "..")).toAbsolutePath().normalize();
    private static final Duration UP_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration WARM_UP_TIMEOUT = Duration.ofMinutes(5);
    // The services docker-compose.yml builds from source. Built one at a time: six parallel
    // Maven dependency downloads and compiles exhausted Docker Desktop's build daemon (a DNS
    // failure mid-download on one run, the BuildKit connection dropping on the next).
    private static final List<String> BUILT_SERVICES = List.of(
            "account-service", "transfer-service", "notification-service",
            "fraud-service", "gateway-service", "fx-service");

    private static E2EStack instance;
    private static RuntimeException startFailure;

    private final String project = "showcase-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    private URI gateway;
    private URI keycloak;
    private URI fraud;
    private URI transfer;
    private URI accountProxy;

    private E2EStack() {
    }

    public static synchronized E2EStack get() {
        if (startFailure != null) {
            throw startFailure;
        }
        if (instance == null) {
            E2EStack stack = new E2EStack();
            Runtime.getRuntime().addShutdownHook(new Thread(stack::down, "e2e-stack-down"));
            try {
                stack.up();
            } catch (RuntimeException ex) {
                startFailure = ex;
                throw ex;
            }
            instance = stack;
        }
        return instance;
    }

    public URI gateway() {
        return gateway;
    }

    public URI keycloak() {
        return keycloak;
    }

    public URI fraud() {
        return fraud;
    }

    public URI transfer() {
        return transfer;
    }

    public URI accountProxy() {
        return accountProxy;
    }

    private void up() {
        System.out.println("[e2e] starting stack " + project + " from " + REPO_ROOT);
        for (String service : BUILT_SERVICES) {
            compose(true, "build", service);
        }
        compose(true, "up", "--detach", "--wait", "--wait-timeout", String.valueOf(UP_TIMEOUT.toSeconds()));
        gateway = hostUrl("gateway-service", 8080);
        keycloak = hostUrl("keycloak", 8080);
        fraud = hostUrl("fraud-service", 8084);
        transfer = hostUrl("transfer-service", 8082);
        accountProxy = hostUrl("account-proxy", 8080);
        System.out.println("[e2e] stack " + project + " is healthy; warming up through the gateway at " + gateway);
        warmUp();
        System.out.println("[e2e] stack " + project + " is up; gateway at " + gateway);
    }

    /**
     * Healthy is not the same as fast. Every service's first requests (JIT, the JWKS fetch from
     * Keycloak, connection pools) made the first saga on a cold stack take ~20 s, where one slow
     * call past Transfer's 5 s read timeout, three times over, fails a test on noise rather than
     * on the saga. One same-currency and one cross-currency transfer, retried until both complete,
     * warm every hop the scenarios use before the first test runs. The setup is retried too: a
     * cold Keycloak's first token request has taken over a minute on a loaded Docker VM.
     */
    private void warmUp() {
        await().atMost(WARM_UP_TIMEOUT).pollInterval(Duration.ofSeconds(5)).ignoreExceptions()
                .until(this::warmUpTransfersComplete);
    }

    private boolean warmUpTransfersComplete() {
        TestUsers users = new TestUsers(keycloak);
        Bank bank = new Bank(gateway);
        TestUser payer = users.create();
        OpenedAccount euros = bank.openAccount(payer, "EUR", "1000.00");
        OpenedAccount otherEuros = bank.openAccount(users.create(), "EUR", "0.00");
        OpenedAccount zlotys = bank.openAccount(users.create(), "PLN", "0.00");
        return bank.transfer(payer, euros, otherEuros, "1.00").status() == 201
                && bank.transfer(payer, euros, zlotys, "1.00").status() == 201;
    }

    private void down() {
        if (Boolean.getBoolean("e2e.keepStack")) {
            System.out.println("[e2e] -De2e.keepStack=true: leaving " + project + " running. Inspect with `docker compose -p "
                    + project + " logs <service>`, remove with `docker compose -p " + project + " down -v`.");
            return;
        }
        try {
            compose(false, "down", "--volumes", "--remove-orphans");
        } catch (RuntimeException ex) {
            System.err.println("[e2e] could not remove stack " + project + "; remove it with `docker compose -p "
                    + project + " down -v`: " + ex.getMessage());
        }
    }

    /** `docker compose port` prints e.g. "0.0.0.0:55012"; only the port is kept. */
    private URI hostUrl(String service, int containerPort) {
        String mapped = compose(false, "port", service, String.valueOf(containerPort)).strip().lines()
                .findFirst().orElseThrow(() -> new IllegalStateException(service + ":" + containerPort + " is not published"));
        return URI.create("http://localhost:" + mapped.substring(mapped.lastIndexOf(':') + 1));
    }

    private String compose(boolean echo, String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose",
                "--project-name", project,
                "--file", REPO_ROOT.resolve("docker-compose.yml").toString(),
                "--file", REPO_ROOT.resolve("docker-compose.e2e.yml").toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(REPO_ROOT.toFile()).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (echo) {
                        System.out.println("[e2e] " + line);
                    }
                }
            }
            if (process.waitFor() != 0) {
                throw new IllegalStateException("`" + String.join(" ", command) + "` failed:\n" + output);
            }
            return output.toString();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
```

`e2e-tests/src/test/java/com/showcase/e2e/support/HttpResult.java`:

```java
package com.showcase.e2e.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

/** A response as the tests see it: the status and the raw body, parsed as JSON on demand. */
public record HttpResult(int status, String raw) {

    public JsonNode json() {
        if (raw.isBlank()) {
            return Http.JSON.missingNode();
        }
        try {
            return Http.JSON.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new AssertionError("Response body is not JSON: " + raw, ex);
        }
    }

    public String text(String field) {
        return json().path(field).asText(null);
    }

    public UUID uuid(String field) {
        return UUID.fromString(json().path(field).asText());
    }

    public BigDecimal decimal(String field) {
        return json().path(field).decimalValue();
    }

    public HttpResult expect(int expected) {
        if (status != expected) {
            throw new AssertionError("Expected HTTP " + expected + " but got " + status + ": " + raw);
        }
        return this;
    }
}
```

`e2e-tests/src/test/java/com/showcase/e2e/support/Http.java`:

```java
package com.showcase.e2e.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

public final class Http {

    // BigDecimal for every JSON decimal, so a balance of 425.37 is compared exactly, never as a double.
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    // Longer than the slowest single request the suite makes: a lost-debit transfer takes ~15 s.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private Http() {
    }

    public static HttpResult send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = CLIENT.send(request.timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public static HttpRequest.BodyPublisher json(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    public static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")));
    }
}
```

- [ ] **Step 5: Write the user and bank fixtures**

`e2e-tests/src/test/java/com/showcase/e2e/support/TestUser.java`:

```java
package com.showcase.e2e.support;

public record TestUser(String username, String accessToken) {
}
```

`e2e-tests/src/test/java/com/showcase/e2e/support/TestUsers.java`:

```java
package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fresh Keycloak users, one per role in a test. Fresh because Account allows one account per
 * owner (Phase 9), and because it isolates every test's balances. New users get the realm's
 * default roles, which include the customer composite. Tokens come from a password grant
 * through showcase-ui, which allows direct grants.
 */
public final class TestUsers {

    private static final String PASSWORD = "e2e-password";

    private final URI keycloak;

    public TestUsers(URI keycloak) {
        this.keycloak = keycloak;
    }

    public TestUser create() {
        String username = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        Http.send(HttpRequest.newBuilder(keycloak.resolve("/admin/realms/showcase/users"))
                        .header("Authorization", "Bearer " + masterAdminToken())
                        .header("Content-Type", "application/json")
                        .POST(Http.json(Map.of(
                                "username", username,
                                "enabled", true,
                                // Keycloak 26's user profile requires these before a password grant
                                // will issue a token ("Account is not fully set up").
                                "email", username + "@e2e.example",
                                "emailVerified", true,
                                "firstName", "E2E",
                                "lastName", username,
                                "credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false))))))
                .expect(201);
        return new TestUser(username, token("showcase", "showcase-ui", username, PASSWORD));
    }

    /** The realm's `admin` demo user: account-admin, transfer-admin and fraud-admin. */
    public String showcaseAdminToken() {
        return token("showcase", "showcase-ui", "admin", "password");
    }

    /** Keycloak's own administrator (KEYCLOAK_ADMIN in docker-compose.yml), for the admin REST API. */
    private String masterAdminToken() {
        return token("master", "admin-cli", "admin", "admin");
    }

    private String token(String realm, String clientId, String username, String password) {
        return Http.send(HttpRequest.newBuilder(keycloak.resolve("/realms/" + realm + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(Http.form(Map.of(
                                "grant_type", "password",
                                "client_id", clientId,
                                "username", username,
                                "password", password))))
                .expect(200)
                .text("access_token");
    }
}
```

`e2e-tests/src/test/java/com/showcase/e2e/support/OpenedAccount.java`:

```java
package com.showcase.e2e.support;

import java.util.UUID;

public record OpenedAccount(UUID id, TestUser owner) {
}
```

`e2e-tests/src/test/java/com/showcase/e2e/support/Bank.java`:

```java
package com.showcase.e2e.support;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.UUID;

/** A customer's view of the system: everything goes through the Gateway with the user's own token. */
public final class Bank {

    private final URI gateway;

    public Bank(URI gateway) {
        this.gateway = gateway;
    }

    public OpenedAccount openAccount(TestUser owner, String currency, String initialBalance) {
        HttpResult created = Http.send(as(owner, "/accounts")
                        .header("Content-Type", "application/json")
                        .POST(Http.json(Map.of(
                                "ownerName", owner.username(),
                                "initialBalance", new BigDecimal(initialBalance),
                                "currency", currency))))
                .expect(201);
        return new OpenedAccount(created.uuid("id"), owner);
    }

    /** Read by the account's owner, through the Gateway, which reaches Account directly (never the fault proxy). */
    public BigDecimal balance(OpenedAccount account) {
        return Http.send(as(account.owner(), "/accounts/" + account.id()).GET()).expect(200).decimal("balance");
    }

    public HttpResult transfer(TestUser initiator, OpenedAccount from, OpenedAccount to, String amount) {
        return transfer(initiator, from, to, amount, UUID.randomUUID().toString());
    }

    public HttpResult transfer(TestUser initiator, OpenedAccount from, OpenedAccount to, String amount,
                               String idempotencyKey) {
        return Http.send(as(initiator, "/transfers")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(Http.json(Map.of(
                        "fromAccountId", from.id(),
                        "toAccountId", to.id(),
                        "amount", new BigDecimal(amount)))));
    }

    public HttpResult getTransfer(TestUser user, UUID transferId) {
        return Http.send(as(user, "/transfers/" + transferId).GET()).expect(200);
    }

    private HttpRequest.Builder as(TestUser user, String path) {
        return HttpRequest.newBuilder(gateway.resolve(path)).header("Authorization", "Bearer " + user.accessToken());
    }
}
```

`e2e-tests/src/test/java/com/showcase/e2e/E2ETestBase.java`:

```java
package com.showcase.e2e;

import com.showcase.e2e.support.Bank;
import com.showcase.e2e.support.E2EStack;
import com.showcase.e2e.support.TestUsers;

/** Every E2E class shares the one stack; fixtures are per test instance and hold no state across tests. */
abstract class E2ETestBase {

    protected static final E2EStack STACK = E2EStack.get();

    protected final TestUsers users = new TestUsers(STACK.keycloak());
    protected final Bank bank = new Bank(STACK.gateway());
}
```

- [ ] **Step 6: Write the happy-path tests**

`e2e-tests/src/test/java/com/showcase/e2e/TransferHappyPathE2E.java`:

```java
package com.showcase.e2e;

import com.showcase.e2e.support.HttpResult;
import com.showcase.e2e.support.OpenedAccount;
import com.showcase.e2e.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferHappyPathE2E extends E2ETestBase {

    // Scenario 1
    @Test
    void happyPathMovesTheMoneyExactlyOnceEvenWhenTheRequestIsRepeated() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        String idempotencyKey = UUID.randomUUID().toString();

        HttpResult first = bank.transfer(ada, from, to, "40.00", idempotencyKey);

        assertThat(first.status()).as(first.raw()).isEqualTo(201);
        assertThat(first.text("status")).isEqualTo("COMPLETED");
        UUID transferId = first.uuid("id");
        assertThat(bank.balance(from)).isEqualByComparingTo("960.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1040.00");
        assertThat(bank.getTransfer(ada, transferId).text("status")).isEqualTo("COMPLETED");

        HttpResult repeated = bank.transfer(ada, from, to, "40.00", idempotencyKey);

        assertThat(repeated.status()).as(repeated.raw()).isEqualTo(201);
        assertThat(repeated.uuid("id")).isEqualTo(transferId);
        assertThat(bank.balance(from)).isEqualByComparingTo("960.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1040.00");
    }

    // Scenario 6: rates come from docker/e2e/fx-provider/mappings/latest-eur.json.
    @Test
    void crossCurrencyTransferCreditsTheAmountAtTheLockedRate() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount euros = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount zlotys = bank.openAccount(bob, "PLN", "1000.00");

        HttpResult result = bank.transfer(ada, euros, zlotys, "100.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(201);
        assertThat(result.text("status")).isEqualTo("COMPLETED");
        assertThat(result.text("sourceCurrency")).isEqualTo("EUR");
        assertThat(result.text("destinationCurrency")).isEqualTo("PLN");
        assertThat(result.decimal("rate")).isEqualByComparingTo("4.2537");
        assertThat(result.text("rateAsOf")).isEqualTo("2026-09-24");
        // 100.00 x 4.2537 = 425.37 exactly, so HALF_EVEN rounding has nothing to do here.
        assertThat(result.decimal("creditAmount")).isEqualByComparingTo("425.37");
        assertThat(bank.balance(euros)).isEqualByComparingTo("900.00");
        assertThat(bank.balance(zlotys)).isEqualByComparingTo("1425.37");
    }
}
```

These tests describe behaviour that already exists, so there is no red phase. What "failing
first" means here is that the harness must fail loudly when it is wrong. Step 7 checks that.

- [ ] **Step 7: Prove the harness fails loudly**

Temporarily change the expected `creditAmount` to `"425.38"` and run
`./mvnw -Pe2e -pl e2e-tests verify`. Expected: the stack builds (several minutes when
cold), `happyPath…` passes, and `crossCurrency…` FAILS on `creditAmount`, 425.37 vs 425.38.
The build ends `BUILD FAILURE` from failsafe's `verify` goal, and `docker compose ls` shows no
`showcase-e2e-*` project left afterwards. Revert the change.

- [ ] **Step 8: Run the suite green**

Run: `./mvnw -Pe2e -pl e2e-tests verify`
Expected: `Tests run: 2, Failures: 0`, `BUILD SUCCESS`, and no `showcase-e2e-*` project left.
If the stack fails to come up, the exception carries the full `docker compose` output. Common
causes:
- a missing `FRAUD_DB_PASSWORD` in `.env`
- Keycloak exceeding the healthcheck's retries on a slow machine

Report the cause; do not loosen timeouts silently.

- [ ] **Step 9: Run it beside a dev stack (Review Focus 5)**

With `docker compose up -d` (the dev stack, fixed names and ports) running, run the suite
again. Expected: PASS, with no name or port conflict, and the dev stack's containers untouched
(`docker ps` before and after). Then `docker compose down` the dev stack.

- [ ] **Step 10: Confirm the default build is untouched**

Run: `./mvnw -q -DskipTests package`, then check that `e2e-tests/target` does not exist.
(If an earlier `-Pe2e` run created it, delete it first.)

- [ ] **Step 11: Commit and open the PR**

```bash
git add pom.xml e2e-tests docker-compose.e2e.yml docker/e2e
git commit -m "test(e2e): Compose-driven end-to-end harness with the happy-path scenarios"
git push -u origin feature/phase-10-task-2-e2e-harness
gh pr create --base feature/phase-10 --title "Phase 10 Task 2: E2E harness and happy paths" --body "<how to run, cold/warm timings, Step 7/9 results>"
```

Stop. Do not merge.

---

## Task 3: Fraud scenarios (2 and 3)

**Branch:** `feature/phase-10-task-3-fraud-scenarios`, off `feature/phase-10` after Task 2 is
merged into it.

**Files:**
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/FraudAdmin.java`
- Modify: `e2e-tests/src/test/java/com/showcase/e2e/E2ETestBase.java`
- Test: `e2e-tests/src/test/java/com/showcase/e2e/FraudScreeningE2E.java`

**Interfaces:**
- Consumes: Task 1's blocklist API and `admin` user; Task 2's `E2EStack.fraud()`,
  `TestUsers.showcaseAdminToken()`, `Http`, `HttpResult`, `Bank`, `OpenedAccount`.
- Produces: `FraudAdmin(URI fraud, TestUsers users)` with `void block(OpenedAccount)` and
  `void unblockAll()`; `E2ETestBase.fraudAdmin` (protected, final).

- [ ] **Step 1: Write the Fraud fixture and wire its cleanup**

`e2e-tests/src/test/java/com/showcase/e2e/support/FraudAdmin.java`:

```java
package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The operator's blocklist API, called on Fraud Service directly (it has no Gateway route). */
public final class FraudAdmin {

    private final URI fraud;
    private final TestUsers users;
    private final Set<UUID> blocked = new HashSet<>();

    public FraudAdmin(URI fraud, TestUsers users) {
        this.fraud = fraud;
        this.users = users;
    }

    public void block(OpenedAccount account) {
        send(account.id(), "PUT");
        blocked.add(account.id());
    }

    /** Lifts every block this instance placed, so no test leaves a blocked account behind. */
    public void unblockAll() {
        for (UUID accountId : blocked) {
            send(accountId, "DELETE");
        }
        blocked.clear();
    }

    private void send(UUID accountId, String method) {
        Http.send(HttpRequest.newBuilder(fraud.resolve("/fraud/blocklist/" + accountId))
                        .header("Authorization", "Bearer " + users.showcaseAdminToken())
                        .method(method, HttpRequest.BodyPublishers.noBody()))
                .expect(204);
    }
}
```

In `E2ETestBase`, add the field and the cleanup (imports:
`com.showcase.e2e.support.FraudAdmin`, `org.junit.jupiter.api.AfterEach`):

```java
    protected final FraudAdmin fraudAdmin = new FraudAdmin(STACK.fraud(), users);

    @AfterEach
    void liftBlocks() {
        fraudAdmin.unblockAll();
    }
```

- [ ] **Step 2: Write the scenarios**

`e2e-tests/src/test/java/com/showcase/e2e/FraudScreeningE2E.java`:

```java
package com.showcase.e2e;

import com.showcase.e2e.support.HttpResult;
import com.showcase.e2e.support.OpenedAccount;
import com.showcase.e2e.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FraudScreeningE2E extends E2ETestBase {

    // Scenario 2: screened before the debit, so nothing moves.
    @Test
    void aBlockedSourceFailsCleanly() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        fraudAdmin.block(from);

        HttpResult result = bank.transfer(ada, from, to, "40.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(422);
        assertThat(result.text("code")).isEqualTo("SOURCE_ACCOUNT_BLOCKED");
        assertThat(result.text("transferStatus")).isEqualTo("FAILED");
        HttpResult stored = bank.getTransfer(ada, result.uuid("transferId"));
        assertThat(stored.text("status")).isEqualTo("FAILED");
        assertThat(stored.text("failureCode")).isEqualTo("SOURCE_ACCOUNT_BLOCKED");
        assertThat(bank.balance(from)).isEqualByComparingTo("1000.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1000.00");
    }

    // Scenario 3: screened after the debit, so money moves and CompensationScheduler puts it back.
    @Test
    void aBlockedDestinationIsDebitedThenCompensated() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        fraudAdmin.block(to);

        HttpResult result = bank.transfer(ada, from, to, "40.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(500);
        assertThat(result.text("code")).isEqualTo("COMPENSATION_REQUIRED");
        assertThat(result.text("transferStatus")).isEqualTo("COMPENSATION_REQUIRED");
        UUID transferId = result.uuid("transferId");
        assertThat(bank.getTransfer(ada, transferId).text("failureCode")).isEqualTo("DESTINATION_ACCOUNT_BLOCKED");

        // The e2e override sweeps every 5 s. The destination stays blocked, so the drain
        // compensates the source rather than retrying the credit.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> bank.getTransfer(ada, transferId).text("status"), "COMPENSATED"::equals);

        assertThat(bank.balance(from)).isEqualByComparingTo("1000.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1000.00");
    }
}
```

Scenario 3 deliberately does not assert the source's intermediate `960.00`. A sweep can
compensate between the `500` and the first balance read, so that assertion would be a race.
`COMPENSATED` itself records that the debit happened.

- [ ] **Step 3: Run the suite**

Run: `./mvnw -Pe2e -pl e2e-tests verify`
Expected: `Tests run: 4, Failures: 0`. If scenario 3 times out, rerun with
`-De2e.keepStack=true` and read `docker compose -p <name> logs transfer-service` before
changing any timeout.

- [ ] **Step 4: Commit and open the PR**

```bash
git add e2e-tests
git commit -m "test(e2e): fraud screening scenarios, clean failure and compensation"
git push -u origin feature/phase-10-task-3-fraud-scenarios
gh pr create --base feature/phase-10 --title "Phase 10 Task 3: E2E fraud scenarios" --body "<summary, run results>"
```

Stop. Do not merge.

---

## Task 4: Fault-injection scenarios (4 and 5) and closing docs

**Branch:** `feature/phase-10-task-4-fault-injection`, off `feature/phase-10` after Task 3 is
merged into it.

**Files:**
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/AccountProxy.java`
- Create: `e2e-tests/src/test/java/com/showcase/e2e/support/TransferMetrics.java`
- Modify: `e2e-tests/src/test/java/com/showcase/e2e/E2ETestBase.java`
- Test: `e2e-tests/src/test/java/com/showcase/e2e/AccountFaultE2E.java`
- Modify: `docs/roadmap.md`, `docs/open-items.md`, `docs/microservices-showcase-design.md`, `CLAUDE.md`, `README.md`, this document (Implementation Notes)

**Interfaces:**
- Consumes: Task 2's `E2EStack.accountProxy()`, `E2EStack.transfer()`, `Http`, `HttpResult`,
  `Bank`; Task 3's `E2ETestBase` (it adds to the same `@AfterEach` pattern).
- Produces: `AccountProxy(URI admin)` with `unreachable()`, `delayDebitResponses(Duration)`,
  `reset()`; `TransferMetrics(URI transfer)` with `String circuitBreakerState(String name)`.

- [ ] **Step 1: Write the proxy and metrics fixtures**

`e2e-tests/src/test/java/com/showcase/e2e/support/AccountProxy.java`:

```java
package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/**
 * Faults on the Transfer -> Account link, through WireMock's admin API on account-proxy. Every
 * fault is a priority-1 stub over the file-backed priority-10 "forward everything" mapping, and
 * reset() drops back to that mapping alone.
 */
public final class AccountProxy {

    private static final String ACCOUNT_SERVICE = "http://account-service:8081";

    private final URI admin;

    public AccountProxy(URI admin) {
        this.admin = admin;
    }

    /** Every call from Transfer to Account fails as a reset connection: an I/O error, as for a crashed peer. */
    public void unreachable() {
        stub(Map.of(
                "priority", 1,
                "request", Map.of("urlPattern", ".*"),
                "response", Map.of("fault", "CONNECTION_RESET_BY_PEER")));
    }

    /**
     * Debits reach Account and commit, but their responses are held back for {@code delay}.
     * Longer than Transfer's 5 s read timeout, this is the case the saga invariant is about: the
     * money moved and Transfer cannot tell. Every other call is forwarded normally, so
     * pre-validation still succeeds.
     */
    public void delayDebitResponses(Duration delay) {
        stub(Map.of(
                "priority", 1,
                "request", Map.of("method", "POST", "urlPathPattern", "/accounts/[^/]+/debit"),
                "response", Map.of("proxyBaseUrl", ACCOUNT_SERVICE, "fixedDelayMilliseconds", delay.toMillis())));
    }

    public void reset() {
        Http.send(HttpRequest.newBuilder(admin.resolve("/__admin/mappings/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody()))
                .expect(200);
    }

    private void stub(Map<String, Object> mapping) {
        Http.send(HttpRequest.newBuilder(admin.resolve("/__admin/mappings"))
                        .header("Content-Type", "application/json")
                        .POST(Http.json(mapping)))
                .expect(201);
    }
}
```

`e2e-tests/src/test/java/com/showcase/e2e/support/TransferMetrics.java`:

```java
package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Transfer Service's circuit-breaker state from its Prometheus scrape (unauthenticated). */
public final class TransferMetrics {

    private static final Pattern STATE = Pattern.compile("state=\"([a-z_]+)\"");

    private final URI transfer;

    public TransferMetrics(URI transfer) {
        this.transfer = transfer;
    }

    /**
     * Resilience4j exports one resilience4j_circuitbreaker_state sample per state and sets the
     * current one to 1. Returns that state ("closed", "open", "half_open", ...), or "absent"
     * before the breaker's first call has registered it.
     */
    public String circuitBreakerState(String name) {
        String scrape = Http.send(HttpRequest.newBuilder(transfer.resolve("/actuator/prometheus")).GET())
                .expect(200).raw();
        return scrape.lines()
                .filter(line -> line.startsWith("resilience4j_circuitbreaker_state{"))
                .filter(line -> line.contains("name=\"" + name + "\""))
                .filter(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) == 1.0)
                .map(STATE::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .findFirst()
                .orElse("absent");
    }
}
```

Before relying on the metric, check its real shape once. With the stack up (a run where you
pause, or a dev stack), run `curl -s localhost:8082/actuator/prometheus | grep
resilience4j_circuitbreaker_state` after one transfer. If the state label or value format
differs (e.g. `1` rather than `1.0`, or states uppercased), fix the parser and note it in the
PR. `Double.parseDouble` already accepts both `1` and `1.0`.

In `E2ETestBase`, add (imports for both fixtures):

```java
    protected final AccountProxy accountProxy = new AccountProxy(STACK.accountProxy());
    protected final TransferMetrics transferMetrics = new TransferMetrics(STACK.transfer());
```

and make the `@AfterEach` reset the proxy too, first:

```java
    @AfterEach
    void cleanUp() {
        accountProxy.reset();
        fraudAdmin.unblockAll();
    }
```

(rename `liftBlocks` to `cleanUp`).

- [ ] **Step 2: Write the fault scenarios**

`e2e-tests/src/test/java/com/showcase/e2e/AccountFaultE2E.java`:

```java
package com.showcase.e2e;

import com.showcase.e2e.support.HttpResult;
import com.showcase.e2e.support.OpenedAccount;
import com.showcase.e2e.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AccountFaultE2E extends E2ETestBase {

    private static final String BREAKER = "accountService";

    // Scenario 4. Pre-validation is the first Account call, so every failure here is clean.
    // The breaker's window is shared with every earlier test in the run, so this sends until it
    // opens rather than counting to the tuned thresholds.
    @Test
    void anUnreachableAccountServiceFailsCleanlyTripsTheBreakerAndRecovers() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        accountProxy.unreachable();

        for (int attempt = 0; attempt < 20 && !"open".equals(transferMetrics.circuitBreakerState(BREAKER)); attempt++) {
            assertCleanUnavailable(bank.transfer(ada, from, to, "1.00"));
        }
        assertThat(transferMetrics.circuitBreakerState(BREAKER)).isEqualTo("open");

        // Open: rejected without trying Account at all, so no retries and no read timeouts.
        long started = System.nanoTime();
        assertCleanUnavailable(bank.transfer(ada, from, to, "1.00"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

        accountProxy.reset();

        // Wait out wait-duration-in-open-state (10 s) before the next call, rather than polling
        // with transfers. A transfer that reached the breaker mid-transition could have its
        // debit refused as not permitted: an unknown outcome, left PENDING, that the sweep
        // would later complete, moving a second 1.00 behind this test's back. After the wait,
        // the next transfer's first three Account calls are the half-open trial calls (3
        // permitted), all succeed, and the breaker closes before its credit.
        sleep(Duration.ofSeconds(12));
        HttpResult recovered = bank.transfer(ada, from, to, "1.00");

        assertThat(recovered.status()).as(recovered.raw()).isEqualTo(201);
        assertThat(transferMetrics.circuitBreakerState(BREAKER)).isEqualTo("closed");
        assertThat(bank.balance(from)).isEqualByComparingTo("999.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1001.00");
    }

    // Scenario 5: the saga invariant. The debit commits at Account, Transfer never hears back.
    @Test
    void aLostDebitResponseIsLeftPendingAndSettledOnceByTheSweep() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        accountProxy.delayDebitResponses(Duration.ofSeconds(7));

        // Three attempts x 5 s read timeout, all under the key <transferId>:debit.
        HttpResult result = bank.transfer(ada, from, to, "40.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(503);
        assertThat(result.text("code")).isEqualTo("ACCOUNT_SERVICE_UNAVAILABLE");
        assertThat(result.text("transferStatus")).isEqualTo("PENDING");
        UUID transferId = result.uuid("transferId");
        accountProxy.reset();

        // The debit landed (the Gateway reads Account directly, not through the proxy), and
        // three deliveries of the same key moved the money once.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(bank.balance(from)).isEqualByComparingTo("960.00"));

        // Stale after 70 s, then two sweep ticks: PENDING -> COMPENSATION_REQUIRED (debit
        // confirmed by replay) -> COMPLETED (credit). Never FAILED.
        await().atMost(Duration.ofSeconds(150)).pollInterval(Duration.ofSeconds(2))
                .until(() -> bank.getTransfer(ada, transferId).text("status"), "COMPLETED"::equals);

        assertThat(bank.balance(from)).isEqualByComparingTo("960.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1040.00");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void assertCleanUnavailable(HttpResult result) {
        assertThat(result.status()).as(result.raw()).isEqualTo(503);
        assertThat(result.text("code")).isEqualTo("ACCOUNT_SERVICE_UNAVAILABLE");
        assertThat(result.text("transferStatus")).isEqualTo("FAILED");
    }
}
```

- [ ] **Step 3: Run the new class alone and verify the timing assumptions**

Run: `./mvnw -Pe2e -pl e2e-tests verify -Dit.test=AccountFaultE2E`
Expected: 2 tests PASS. Check these, and report each one:
- **Scenario 5's first response is the saga's `503`.** It carries `transferStatus`, which only
  Transfer's `ApiExceptionHandler` sets. If the Gateway times out first, you get a `504` or a
  problem without `transferStatus`. The fix is a longer Gateway read timeout in
  `docker-compose.e2e.yml`, under `gateway-service.environment`:
  `SPRING_CLOUD_GATEWAY_SERVER_WEBMVC_HTTP_CLIENT_READ_TIMEOUT: 60s`. Confirm the property name
  against Spring Cloud Gateway Server MVC 4.3's docs before adding it, and only if the
  failure is observed.
- **Scenario 4 opens the breaker within the 20 attempts.** Record how many it took. If the
  recovered transfer is not `201`, or the breaker is not `closed` straight after it, do not
  switch to polling: report the observed states. The fixed wait is what keeps the balance
  assertion exact.
- **Scenario 5's balance check does not see a double debit.** It proves the ledger
  deduplicated the retries. If it ever reads `920.00` or lower, stop and report: that would be
  a real money bug, not a test problem.

- [ ] **Step 4: Run the whole suite twice from cold**

Run `./mvnw -Pe2e -pl e2e-tests verify` twice in a row.
Expected both times: `Tests run: 6, Failures: 0, Errors: 0`, and no leftover `showcase-e2e-*`
project. Two consecutive green runs are this task's acceptance bar (spec, Testing).

- [ ] **Step 5: Closing docs**

- `docs/roadmap.md`: the Phase 10 row becomes
  `| 10 | [End-to-End Saga Tests](phase-10-end-to-end-saga-tests.md) | ✅ Done | <one-line
  scope as built: local-only e2e-tests module (-Pe2e) driving the Compose stack plus an e2e
  override; WireMock fault proxy between Transfer and Account and a stub FX provider; six
  scenarios checked by balances; Fraud's blocklist moved into its own fraud database behind a
  fraud-admin API> |`.
- `docs/open-items.md`: remove item 1 (Phase 10) and renumber that table. Add to the
  appropriate section the spec's Scope Boundary items, each pointing to
  `phase-10-end-to-end-saga-tests.md` Scope Boundary:
  - E2E in CI
  - a blocklist listing/UI
  - blocklist audit history
  - chaos against Postgres/Kafka/Redis/Keycloak
  - load testing
  - parallel E2E runs
- `docs/microservices-showcase-design.md` §6: rewrite the "End-to-end saga tests" and "Fault
  injection" bullets in the present tense, to describe what exists:
  - the `e2e-tests` module under `-Pe2e`, local only
  - the Compose override and the WireMock proxy/stub
  - the six scenarios
  - `MockRestServiceServer` still covering the client-level retry/breaker tests

  Also update line 122's "the one remaining phase (end-to-end saga tests)".
- `CLAUDE.md`:
  - Project status: Phase 10 is implemented, along with the others.
  - Under "Local environment", add a bullet: "End-to-end suite: `./mvnw -Pe2e -pl e2e-tests
    verify` (local only, not in CI or `./mvnw test`). It builds and starts its own copy of
    the stack (`docker-compose.yml` + `docker-compose.e2e.yml`, project `showcase-e2e-*`,
    random host ports, so it runs beside a dev stack) and removes it at exit. A killed run can
    leave one behind: `docker compose ls`, then `docker compose -p <name> down -v`."
- `README.md`: a short "End-to-end tests" section after the test instructions, with the same
  command, prerequisites (Docker, `.env` including `FRAUD_DB_PASSWORD`), expected cold-run
  time (measured in Step 4), what the six scenarios cover, `-De2e.keepStack=true` for
  debugging, and the leftover-stack cleanup.
- This document: append `## Implementation Notes (as built)` with every deviation found in
  Tasks 1–4 (e.g. the WireMock tag used, the breaker attempts observed, any Gateway timeout
  change, the metric format).

- [ ] **Step 6: Commit and open the PR**

```bash
git add e2e-tests docker-compose.e2e.yml docs CLAUDE.md README.md
git commit -m "test(e2e): Account fault injection, breaker and lost-debit scenarios; Phase 10 docs"
git push -u origin feature/phase-10-task-4-fault-injection
gh pr create --base feature/phase-10 --title "Phase 10 Task 4: E2E fault injection and closing docs" --body "<summary, Step 3 observations, Step 4 results>"
```

Stop. Do not merge. Once the user merges Task 4 into `feature/phase-10`, the phase PR
(#119, `feature/phase-10` → `master`) is ready for the user's end-of-phase review. The
whole-branch review before that uses a capable model (CLAUDE.md, Subagent model policy).

## Implementation Notes (as built)

Deviations from the plan above, recorded as they were found. Code blocks in the plan have been
re-synced from the merged source where they changed.

### Task 1
- The live check ran against an existing dev stack without `docker compose down -v`: the
  `fraud` database was created by hand with `init-db.sh`'s SQL, and `fraud-admin` was added to
  the running Keycloak through its admin API.
- `FraudCheckControllerTest.returns500WhenTheBlocklistCannotBeRead` passed on its first run: the
  existing catch-all handler already maps any exception to `500 INTERNAL_ERROR`. It stays as a
  guard against the failure ever being swallowed.

### Task 2
- **Images build one at a time.** A single `up --build` of six images in parallel failed twice
  on a 7.8 GB Docker Desktop: once with DNS failing mid-download inside the build, once with the
  BuildKit connection dropping (`rpc error … EOF`). `E2EStack` now runs `build <service>` for
  each service, then `up --wait`. A cold run (after a root `pom.xml` change invalidates every
  image's dependency layer) takes ~16 min; a warm one ~4 min.
- **Memory caps in `docker-compose.yml`, for both stacks** (at the user's direction; nothing is
  disabled). Unbounded, every JVM sized its heap from the whole Docker VM, and the dev stack took
  ~4.4 GB, so a second copy could not start beside it. The six services get
  `-Xmx192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m`, Kafka a
  256 MB heap, Keycloak 512 MB, Tempo and Grafana `GOMEMLIMIT=128MiB`. Measured: dev ~3.1 GB,
  E2E ~3.2 GB; the suite passes beside a running dev stack.
- **Keycloak's healthcheck `start_period` is 120 s** (was 40 s). `start-dev` re-runs Quarkus
  augmentation on every fresh container; under load it took ~130 s plus ~100 s to start and
  import the realm, past the old ~190 s budget.
- **`pending-stale-after` in the E2E override is 70 s, not 20 s.** At 20 s, the stale-`PENDING`
  sweep picked up a live saga that was merely slow (a cold stack's first saga took 20 s) and
  logged `unexpected failure during stale-PENDING recovery`; had it won the race, the live
  request would have answered `500` for a transfer that later completed. The rule it broke:
  the threshold must exceed the slowest possible live saga, four calls of up to 3 × 5 s read
  timeouts plus backoff, ~62 s. The real config's 120 s holds it. Scenario 5's wait is now
  150 s.
- **`E2EStack` warms the stack up** after `up --wait`: one EUR→EUR and one EUR→PLN transfer,
  retried until both complete. Before this, the first test took 90 s, and one run failed with
  `SOURCE_FRAUD_SERVICE_UNAVAILABLE` on first-call latency. With it, the tests take ~6 s and
  ~0.5 s.
- Recreating the dev stack's Keycloak (to apply the caps) re-imports `ada`, `bob` and `admin`
  with new subject IDs, because `showcase-realm.json` gives them none. Their existing dev
  accounts are orphaned rather than deleted. Any Keycloak recreation does this.

### Task 4
- **Two stacks at once do not reliably fit in a ~7.4 GB Docker VM, even capped.** Tasks 2 and 3
  passed beside a running dev stack, but on the next run the VM was down to 211 MB free with
  1.35 GB in swap, and the E2E Keycloak's first token request stalled past the 60 s HTTP
  timeout (its event loop blocked for 3–9 s at a time). Decision (user): keep the caps, and stop
  the dev stack before an E2E run on a machine this size. README, CLAUDE.md and both compose
  files say so; the E2E stack still never clashes with a dev stack's names or ports.
- **The warm-up retries its own setup** (user creation, first tokens) inside its retry loop, with
  a 5-minute budget, so a slow first request is absorbed rather than failing the run.
- Observed: the `accountService` breaker opened after 3 failing transfers (plus the warm-up's
  and the lost-debit test's calls in its window); the fourth was rejected at once. The
  lost-debit transfer went `PENDING` → `COMPENSATION_REQUIRED` about 60 s after the saga's
  `503` (the 70 s threshold plus a 5 s tick), then `COMPLETED` 5 s later, with the source
  debited exactly once. The Gateway did not time out before the saga's ~15 s `503`, so no
  Gateway timeout override was needed.
- The breaker metric's real format matched the parser: `state="closed"` and a `1.0` value.
