# Transfer Service + Synchronous Saga Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a working Transfer Service that orchestrates a money transfer across two synchronous calls into Account Service (pre-validate → debit → credit), records every outcome in its own database, and is reachable via `docker compose up` alongside Account Service.

**Architecture:** A second Maven module, `transfer-service`, laid out like `account-service` (`domain` / `service` / `api`, plus a new `client` package). `TransferService` is the saga orchestrator: it is deliberately **not** `@Transactional`, so no database transaction is ever held open across an HTTP call; each state change commits on its own via `TransferRepository.save`. Outbound calls go through `AccountClient`, a hand-written wrapper over a blocking `RestClient` that maps HTTP failures onto exactly two domain exceptions — "the downstream rejected this" and "the downstream is broken" — which is the branch the saga makes decisions on. Both services move to RFC 7807 `ProblemDetail` so that branch keys off a stable machine-readable `code` rather than prose.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, PostgreSQL 16, Spring `RestClient` (blocking), springdoc-openapi 2.6.0, Testcontainers, JUnit 5 + AssertJ + Mockito, `MockRestServiceServer`, Docker Compose.

**Spec:** [docs/microservices-showcase-design.md](microservices-showcase-design.md) §2, §3, §4, §6, §7

## Global Constraints

- Java 21 floor; Spring Boot 3.3.4. The default `java` on PATH is not the right version — use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` to it before running Maven. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads enabled via `spring.threads.virtual.enabled=true`. WebClient and reactive types are out of bounds. (spec §3)
- Spring Data JPA + PostgreSQL, one logical database per stateful service — Transfer never queries Account's database, only its HTTP API. (spec §2, §3)
- Integration-level tests use Testcontainers against real Postgres — never H2. (spec §6)
- Lombok for entity boilerplate: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic rather than forcing it through Lombok. (CLAUDE.md)
- No Kubernetes/service mesh; local deployment is Docker Compose only. (spec §7)
- Never commit directly to `master`; work happens on a `feature/plan-2-*` branch, merged via a subagent-reviewed PR. (CLAUDE.md)
- springdoc-openapi is pinned to `2.6.0` in the root POM's `springdoc-openapi.version` property — the version that matches Spring Boot 3.3.4. Do not bump it. (docs/roadmap.md)

## Scope Boundary

**In scope:** Transfer entity + repository + REST API; the synchronous saga and its failure paths; `AccountClient` with timeouts; RFC 7807 `ProblemDetail` on both services; Actuator + Compose healthchecks on both services; Compose wiring for a second database and service.

**Explicitly out of scope** — each is a named follow-up, not an oversight:

| Deferred | Why not now | Lands in |
|---|---|---|
| Resilience4j (CircuitBreaker/Retry/TimeLimiter) | The saga's structure and the client's error mapping have to exist before there is anything to wrap | Plan 3 |
| Compensation (credit-back on a failed credit) | This plan deliberately ends with a visible, recorded inconsistency so the next plan's compensator has a concrete state to drain. **Three blocking preconditions, all discovered during Task 5's review — read them before writing the compensator.** (1) *Reconcile before crediting, on either leg.* An `ACCOUNT_SERVICE_UNAVAILABLE` failure means the call's outcome is UNKNOWN, not that it did not happen — a timeout cannot be told from a non-delivery. A debit that times out records `FAILED` but may have committed; a credit that times out records `COMPENSATION_REQUIRED` but may also have committed. Blind compensation invents money in the first case and duplicates it in the second. The compensator must query Account for the real state first. (2) *Sweep stale `PENDING` rows too, not just `COMPENSATION_REQUIRED`.* If the final `save` fails after both legs committed, the orchestrator rethrows rather than mask the cause, leaving a row that says nothing happened while money moved both ways. That is the worst state in the system and nothing else will ever revisit it. (3) `COMPENSATION_REQUIRED` is terminal under `requirePending()`, so the compensator needs a new transition that accepts it as a pre-state. | Plan 3 |
| Idempotency keys on debit/credit | Only matters once a client automatically retries a call that may already have succeeded; this plan has no retries | Plan 3 |
| Transactional outbox + Kafka publisher | A distinct subsystem with its own container and test infrastructure | Plan 4 |
| Fraud Service (the saga's third call) | The saga is built to accept another step; adding one is additive | Plan 5 |
| Flyway migrations | Would require baselining Account's existing table too — real work outside this slice; `ddl-auto: update` stays, consistent with Account | its own plan |
| Spring Cloud Contract; dedicated e2e module | Cross-service test infrastructure, not saga logic | Plan 7 |
| Auth / JWT | No security on any service yet | Plan 6 |

## Design Decisions Worth Knowing Before You Start

**The saga is not a transaction.** `TransferService.execute` has no `@Transactional`. Each state write goes through `TransferRepository.save`, and Spring Data's `save` is itself transactional, so every write commits independently. This is the entire point: a database transaction spanning HTTP calls would hold a connection and row locks for the duration of two network round-trips, and would silently roll back local work that the remote service has already committed. If you find yourself adding `@Transactional` to the orchestrator, stop — that is precisely the bug this design exists to avoid.

**Three terminal states, not two.**

| Status | Meaning | Money |
|---|---|---|
| `COMPLETED` | both legs succeeded | debited + credited |
| `FAILED` | rejected before or during the debit | nothing moved |
| `COMPENSATION_REQUIRED` | debit succeeded, credit did not | **stranded at source** |

`COMPENSATION_REQUIRED` is named for what happens next rather than for the symptom, so Plan 3 turns it into a real transient state that a compensator drains — no rename, no data migration. Every entry into it logs at ERROR and is listable via `GET /transfers?status=COMPENSATION_REQUIRED`.

**Pre-validation is an optimisation, not a guarantee.** Step 3 of the saga `GET`s both accounts so the common mistyped-destination case costs nothing. An account can still be deleted or drained between that check and the debit — so step 4 handles every rejection on its own regardless. Do not read step 3 as a correctness mechanism, and do not delete the step-4 handling on the grounds that step 3 already checked.

**Error handling is duplicated per service, not extracted to a shared module.** A common DTO/exception jar is how a microservices repo quietly becomes a distributed monolith: every contract change becomes a lockstep redeploy of every service. `Problems.java` and the exception handler exist twice, once per service, on purpose.

## File Structure

**Account Service (modified):**

| File | Responsibility |
|---|---|
| `api/Problems.java` (new) | One static factory building an RFC 7807 `ProblemDetail` with `code` and `timestamp` properties |
| `api/ApiExceptionHandler.java` (rewritten) | Extends `ResponseEntityExceptionHandler`; maps domain exceptions → `ProblemDetail` |
| `api/ErrorResponse.java` | **Deleted** — replaced by `ProblemDetail` |

**Transfer Service (new module `transfer-service`, port 8082, package `com.showcase.transfer`):**

| File | Responsibility |
|---|---|
| `TransferServiceApplication.java` | Boot entry point + `@OpenAPIDefinition` |
| `domain/Transfer.java` | Entity + state machine; guards its own invariants |
| `domain/TransferStatus.java` | `PENDING`, `COMPLETED`, `FAILED`, `COMPENSATION_REQUIRED` |
| `domain/TransferFailureCode.java` | Why a transfer failed, in Transfer's own vocabulary |
| `domain/TransferRepository.java` | Spring Data repository + `findByStatus` |
| `domain/SameAccountTransferException.java` | Thrown when source == destination |
| `domain/TransferNotFoundException.java` | Thrown on an unknown transfer id |
| `client/AccountClient.java` | Blocking `RestClient` wrapper; HTTP status → domain exception |
| `client/AccountView.java` | The subset of Account's response Transfer actually reads |
| `client/AccountProblem.java` | Minimal Jackson binding for Account's error body |
| `client/AccountRejectedException.java` | Account said no (4xx), carrying Account's `code` |
| `client/AccountServiceUnavailableException.java` | Account is broken or unreachable (5xx, timeout, connection refused) |
| `client/AccountClientProperties.java` | `base-url`, `connect-timeout`, `read-timeout` |
| `client/AccountClientConfig.java` | Builds the `RestClient` with timeouts |
| `service/TransferService.java` | The saga orchestrator — the heart of this plan |
| `service/TransferFailedException.java` | Carries the persisted `Transfer` out to the API layer |
| `service/TransferPersistenceException.java` | Carries the transfer's id out when persisting a terminal state fails, so the 500 still tells the caller which row to look at |
| `api/CreateTransferRequest.java` | Request DTO + bean validation |
| `api/TransferResponse.java` | Response DTO |
| `api/TransferController.java` | `POST /transfers`, `GET /transfers/{id}`, `GET /transfers` |
| `api/Problems.java` | Same factory as Account's, deliberately duplicated |
| `api/ApiExceptionHandler.java` | Maps transfer outcomes → HTTP status + `ProblemDetail` |

**Infrastructure (modified):** root `pom.xml`, `docker/postgres/init-db.sh`, `docker-compose.yml`, `.env.example`, `.env`, `account-service/Dockerfile`, `README.md`, `docs/roadmap.md`.

## Error Code Contract

Both services emit `application/problem+json` with a stable `code` property. `type` is an identifier, not a URL anyone dereferences.

```json
{ "type": "https://showcase.example/errors/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Account 3f2a... has insufficient funds: requested 120.00, available 50.00",
  "code": "INSUFFICIENT_FUNDS",
  "timestamp": "2026-09-10T12:00:00Z" }
```

| Account code | HTTP | Transfer code | HTTP |
|---|---|---|---|
| `ACCOUNT_NOT_FOUND` | 404 | `TRANSFER_NOT_FOUND` | 404 |
| `INSUFFICIENT_FUNDS` | 422 | `ACCOUNT_NOT_FOUND` | 422 |
| `CONCURRENT_MODIFICATION` | 409 | `INSUFFICIENT_FUNDS` | 422 |
| `VALIDATION_FAILED` | 400 | `CONCURRENT_MODIFICATION` | 422 |
| `MALFORMED_REQUEST` | 400 | `SAME_ACCOUNT_TRANSFER` | 400 |
| `REQUEST_REJECTED` | other 4xx | `VALIDATION_FAILED` | 400 |
| `INTERNAL_ERROR` | 500 | `REQUEST_REJECTED` | other 4xx |
| | | `MALFORMED_REQUEST` | 400 |
| | | `ACCOUNT_SERVICE_UNAVAILABLE` | 503 |
| | | `COMPENSATION_REQUIRED` | 500 |
| | | `UNEXPECTED_ERROR` | 500 |
| | | `INTERNAL_ERROR` | 500 |

A referenced account being missing is `422` at Transfer, not `404` — the transfer resource is not what is missing.

Every error response carries a `code`, including the ones Spring's own MVC layer raises
(405, 415, 406, `NoResourceFoundException`, and the rest). Un-enumerated 4xx statuses get
`REQUEST_REJECTED`, un-enumerated 5xx get `INTERNAL_ERROR`, stamped in one place by
overriding `handleExceptionInternal`. Without that, a caller reading `code` gets `null` on
exactly the paths a mis-wired client hits most.

Transfer has two distinct 500s. `UNEXPECTED_ERROR` means the saga ran and failed with a code from Account that this service does not recognise — the transfer record exists and says so. `INTERNAL_ERROR` is the catch-all handler firing on a bug, with no transfer outcome to report.

---
### Task 1: Migrate Account Service to RFC 7807 ProblemDetail

Transfer needs to tell "Account rejected this" from "Account is broken" without parsing English. This task makes Account emit a stable `code` on every error. Nothing in Transfer exists yet, so this task stands alone and is verified entirely by Account's own tests.

**Files:**
- Create: `account-service/src/main/java/com/showcase/account/api/Problems.java`
- Modify: `account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java` (full rewrite)
- Delete: `account-service/src/main/java/com/showcase/account/api/ErrorResponse.java`
- Test: `account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java`

**Interfaces:**
- Consumes: `AccountNotFoundException`, `InsufficientFundsException` (existing, unchanged).
- Produces: every Account error response is `application/problem+json` carrying a `code` string property from the table above. Task 4's `AccountClient` reads exactly that property.

- [ ] **Step 1: Write the failing test**

Replace the three `ErrorResponse`-typed tests in `AccountControllerIT` with `ProblemDetail`-typed ones. `TestRestTemplate` deserializes `ProblemDetail` out of the box — Spring Boot registers `ProblemDetailJacksonMixin` on the auto-configured `ObjectMapper`, and custom properties land in `getProperties()`.

```java
// account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java
// Add these imports:
//   org.springframework.http.MediaType
//   org.springframework.http.ProblemDetail

    @Test
    void returns404ForUnknownAccount() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/accounts/" + UUID.randomUUID(), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getProperties()).containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(response.getBody().getProperties()).containsKey("timestamp");
    }

    @Test
    void rejectsDebitWithInsufficientFunds() {
        UUID id = createAccount(new BigDecimal("10.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("40.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getProperties()).containsEntry("code", "INSUFFICIENT_FUNDS");
    }

    @Test
    void rejectsNegativeInitialBalance() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("-5.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void returns400ForMalformedAccountId() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/accounts/not-a-uuid", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "MALFORMED_REQUEST");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl account-service test -Dtest=AccountControllerIT`
Expected: FAIL — compilation still succeeds, but `getProperties()` is `null` because the current handler returns `ErrorResponse`, so `containsEntry` fails with a null-map assertion error.

- [ ] **Step 3: Create the ProblemDetail factory**

```java
// account-service/src/main/java/com/showcase/account/api/Problems.java
package com.showcase.account.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/**
 * Builds RFC 7807 problem responses. The {@code type} URI is an identifier, not a
 * dereferenceable URL; the {@code code} property is the stable, machine-readable
 * discriminator that clients (notably Transfer Service) switch on.
 */
final class Problems {

    private static final URI TYPE_BASE = URI.create("https://showcase.example/errors/");

    private Problems() {
    }

    static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
```

- [ ] **Step 4: Rewrite the exception handler**

`ResponseEntityExceptionHandler` already renders Spring's own MVC exceptions as `ProblemDetail`, so overriding its hooks replaces three hand-written handlers. Leave `spring.mvc.problemdetails.enabled` unset — it configures a competing auto-handler.

```java
// account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java
package com.showcase.account.api;

import com.showcase.account.domain.AccountNotFoundException;
import com.showcase.account.domain.InsufficientFundsException;

import java.time.Instant;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleNotFound(AccountNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds", ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConflict(ObjectOptimisticLockingFailureException ex) {
        return Problems.of(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Concurrent modification",
                "Account was modified concurrently, please retry");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", detail));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Malformed request body"));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Invalid value for parameter: " + ex.getPropertyName()));
    }

    /**
     * Stamps the {@code code}/{@code timestamp} invariant onto the ~14 MVC exception types
     * this class does not override explicitly (405, 415, 406, unmapped paths, and the rest).
     * Without it they render a ProblemDetail with NO code at all, and a consumer reading
     * {@code code} gets null on exactly the paths a mis-wired caller hits most. The handler
     * is duplicated per service rather than shared, so every service carries its own copy
     * of this guard.
     */
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
}
```

`MethodArgumentTypeMismatchException` extends `TypeMismatchException`, so the `handleTypeMismatch` override covers the "not a UUID in the path" case that had its own handler before.

- [ ] **Step 5: Delete the old error DTO**

```bash
rm account-service/src/main/java/com/showcase/account/api/ErrorResponse.java
```

- [ ] **Step 6: Run the whole Account test suite**

Run: `./mvnw -pl account-service test`
Expected: PASS, all tests. If anything still references `ErrorResponse`, compilation fails and names the file — fix those references to use `ProblemDetail`.

- [ ] **Step 7: Commit**

```bash
git add account-service/src/main/java/com/showcase/account/api account-service/src/test/java/com/showcase/account/api
git commit -m "feat(account): return RFC 7807 ProblemDetail with a stable error code"
```

---

### Task 2: Actuator health endpoints and Compose healthchecks

Transfer Service will declare `depends_on: account-service: condition: service_healthy`, which requires Account to actually report health to Docker. This task adds that to Account and is verifiable on its own with `docker compose up`.

**Files:**
- Modify: `account-service/pom.xml`
- Modify: `account-service/src/main/resources/application.yml`
- Modify: `account-service/Dockerfile`
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces: `GET /actuator/health` on port 8081 returning `{"status":"UP"}`; a Compose healthcheck that Task 7's `transfer-service` depends on.

- [ ] **Step 1: Add the Actuator dependency**

```xml
<!-- account-service/pom.xml: add inside <dependencies> -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 2: Expose only the health endpoint**

```yaml
# account-service/src/main/resources/application.yml: add at top level
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 3: Install curl in the runtime image**

`eclipse-temurin:21-jre` ships neither `curl` nor `wget`, so a `HEALTHCHECK` using either would fail silently forever and the container would sit permanently `unhealthy`. Install curl explicitly.

```dockerfile
# account-service/Dockerfile: replace the runtime stage
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/account-service/target/account-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 4: Add the Compose healthcheck**

```yaml
# docker-compose.yml: add under services.account-service
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 40s
```

`start_period: 40s` matters — a Spring Boot container needs tens of seconds to become ready, and without it the retries are consumed during normal startup and the container is declared unhealthy before it ever had a chance.

- [ ] **Step 5: Verify against a real container**

Run:
```bash
docker compose up --build -d
docker compose ps
```
Expected: `showcase-account-service` shows `(healthy)` within about a minute. Then:
```bash
curl -f http://localhost:8081/actuator/health
```
Expected: `{"status":"UP","groups":["liveness","readiness"]}` — the `groups` array is
emitted precisely because Step 2 sets `probes.enabled: true`. Any later assertion on this
endpoint must target `$.status`, not the whole document. Tear down with `docker compose down`.

- [ ] **Step 6: Commit**

```bash
git add account-service/pom.xml account-service/src/main/resources/application.yml account-service/Dockerfile docker-compose.yml
git commit -m "feat(account): add actuator health endpoint and compose healthcheck"
```

---
### Task 3: transfer-service module, Transfer entity, and repository

**Files:**
- Modify: `pom.xml` (root reactor — add the module)
- Modify: `account-service/Dockerfile` (see Step 2 — adding the module breaks it)
- Create: `transfer-service/pom.xml`
- Create: `transfer-service/src/main/java/com/showcase/transfer/TransferServiceApplication.java`
- Create: `transfer-service/src/main/resources/application.yml`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferStatus.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferFailureCode.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/SameAccountTransferException.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferNotFoundException.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/Transfer.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferRepository.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/domain/TransferTest.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/domain/TransferRepositoryTest.java`

**Interfaces:**
- Produces: `Transfer` with constructor `Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount)` and state-transition methods `markCompleted()`, `markFailed(TransferFailureCode, String)`, `markCompensationRequired(TransferFailureCode, String)`; getters `getId()`, `getFromAccountId()`, `getToAccountId()`, `getAmount()`, `getStatus()`, `getFailureCode()`, `getFailureReason()`, `getCreatedAt()`, `getSettledAt()`. `TransferRepository extends JpaRepository<Transfer, UUID>` with `List<Transfer> findByStatus(TransferStatus status)`. Tasks 5 and 6 use all of these.

- [ ] **Step 1: Register the module in the root POM**

```xml
<!-- pom.xml: replace the <modules> block -->
<modules>
  <module>account-service</module>
  <module>transfer-service</module>
</modules>
```

- [ ] **Step 2: Fix account-service/Dockerfile before it breaks**

Adding a second module to the reactor breaks Account's Docker build. Its Dockerfile copies only `account-service/pom.xml`, but Maven has to parse **every** module listed in the aggregator to build the reactor graph — even with `-pl account-service`. Without this change the build fails with "Child module .../transfer-service/pom.xml does not exist".

```dockerfile
# account-service/Dockerfile: in the build stage, after the existing
# "COPY account-service/pom.xml account-service/pom.xml" line, add:
COPY transfer-service/pom.xml transfer-service/pom.xml
```

- [ ] **Step 3: Create the module POM**

Mirrors `account-service/pom.xml`, plus Actuator from the start.

```xml
<!-- transfer-service/pom.xml -->
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

  <artifactId>transfer-service</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
      <version>${springdoc-openapi.version}</version>
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
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-test-autoconfigure</artifactId>
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
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
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

- [ ] **Step 4: Create the application class and configuration**

```java
// transfer-service/src/main/java/com/showcase/transfer/TransferServiceApplication.java
package com.showcase.transfer;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@OpenAPIDefinition(
        info = @Info(
                title = "Transfer Service API",
                version = "v1",
                description = "Orchestrates the transfer saga across Account Service."
        )
)
@SpringBootApplication
@ConfigurationPropertiesScan
public class TransferServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}
```

```yaml
# transfer-service/src/main/resources/application.yml
server:
  port: 8082

spring:
  application:
    name: transfer-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:transfer}
    username: ${DB_USER:transfer_service}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true

account-service:
  base-url: ${ACCOUNT_SERVICE_URL:http://localhost:8081}
  connect-timeout: 2s
  read-timeout: 5s
```

- [ ] **Step 5: Write the failing entity test**

```java
// transfer-service/src/test/java/com/showcase/transfer/domain/TransferTest.java
package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal TEN = new BigDecimal("10.00");

    @Test
    void newTransferStartsPending() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getFromAccountId()).isEqualTo(FROM);
        assertThat(transfer.getToAccountId()).isEqualTo(TO);
        assertThat(transfer.getAmount()).isEqualByComparingTo("10.00");
        assertThat(transfer.getCreatedAt()).isNotNull();
        assertThat(transfer.getSettledAt()).isNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> new Transfer(FROM, FROM, TEN))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void rejectsANonPositiveAmount() {
        assertThatThrownBy(() -> new Transfer(FROM, TO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(FROM, TO, new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markCompletedSettlesTheTransfer() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markCompleted();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getSettledAt()).isNotNull();
        assertThat(transfer.getFailureCode()).isNull();
    }

    @Test
    void markFailedRecordsTheReason() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(transfer.getFailureReason()).isEqualTo("not enough money");
        assertThat(transfer.getSettledAt()).isNotNull();
    }

    @Test
    void markCompensationRequiredRecordsTheUnderlyingCause() {
        Transfer transfer = new Transfer(FROM, TO, TEN);

        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        assertThat(transfer.getFailureReason()).isEqualTo("credit leg timed out");
    }

    @Test
    void aSettledTransferCannotBeSettledAgain() {
        Transfer transfer = new Transfer(FROM, TO, TEN);
        transfer.markCompleted();

        assertThatThrownBy(transfer::markCompleted).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=TransferTest`
Expected: FAIL — compilation error, `Transfer`, `TransferStatus`, `TransferFailureCode` and `SameAccountTransferException` do not exist.

- [ ] **Step 7: Create the enums and exceptions**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferStatus.java
package com.showcase.transfer.domain;

public enum TransferStatus {
    /** Created, no leg attempted or the outcome is not yet known. */
    PENDING,
    /** Source debited and destination credited. */
    COMPLETED,
    /** Rejected before or during the debit. No money moved. */
    FAILED,
    /**
     * Source was debited but the destination credit did not succeed, so funds are
     * stranded at the source. Plan 3 adds a compensator that drains this state by
     * crediting the source back.
     */
    COMPENSATION_REQUIRED
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferFailureCode.java
package com.showcase.transfer.domain;

/** Why a transfer did not complete, in Transfer Service vocabulary. */
public enum TransferFailureCode {
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    CONCURRENT_MODIFICATION,
    ACCOUNT_SERVICE_UNAVAILABLE,
    UNEXPECTED_ERROR;

    /**
     * Translates a code from Account Service into this service vocabulary.
     *
     * <p>A null code is not hypothetical: a well-formed problem document that simply omits
     * {@code code} (a proxy or gateway between the services can produce one) binds to a null
     * here. Failing with an NPE while handling a downstream failure would turn a transfer that
     * should be recorded as FAILED into a 500 with no record of why, so a null maps to
     * {@link #UNEXPECTED_ERROR} like any other unrecognised code.
     */
    public static TransferFailureCode fromAccountCode(String accountCode) {
        if (accountCode == null) {
            return UNEXPECTED_ERROR;
        }
        return switch (accountCode) {
            case "ACCOUNT_NOT_FOUND" -> ACCOUNT_NOT_FOUND;
            case "INSUFFICIENT_FUNDS" -> INSUFFICIENT_FUNDS;
            case "CONCURRENT_MODIFICATION" -> CONCURRENT_MODIFICATION;
            default -> UNEXPECTED_ERROR;
        };
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/SameAccountTransferException.java
package com.showcase.transfer.domain;

import java.util.UUID;

public class SameAccountTransferException extends RuntimeException {

    public SameAccountTransferException(UUID accountId) {
        super("Source and destination must differ, both were: " + accountId);
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferNotFoundException.java
package com.showcase.transfer.domain;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID transferId) {
        super("Transfer not found: " + transferId);
    }
}
```

- [ ] **Step 8: Create the entity**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/Transfer.java
package com.showcase.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID fromAccountId;

    @Column(nullable = false, updatable = false)
    private UUID toAccountId;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TransferFailureCode failureCode;

    @Column(length = 512)
    private String failureReason;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant settledAt;

    public Transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        if (fromAccountId == null || toAccountId == null) {
            throw new IllegalArgumentException("Both account ids are required");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new SameAccountTransferException(fromAccountId);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markCompleted() {
        requirePending();
        this.status = TransferStatus.COMPLETED;
        this.settledAt = Instant.now();
    }

    public void markFailed(TransferFailureCode failureCode, String failureReason) {
        requirePending();
        this.status = TransferStatus.FAILED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    public void markCompensationRequired(TransferFailureCode failureCode, String failureReason) {
        requirePending();
        this.status = TransferStatus.COMPENSATION_REQUIRED;
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason);
        this.settledAt = Instant.now();
    }

    private void requirePending() {
        if (status != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer %s is already %s".formatted(id, status));
        }
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= 512) {
            return reason;
        }
        // Cutting at 512 blindly can split a surrogate pair (an emoji straddling the boundary),
        // leaving an unpaired surrogate that the PostgreSQL driver refuses to encode. That would
        // recreate the exact failure this method exists to prevent: a recorded failure silently
        // becoming an unrecorded one. Drop the lone high surrogate instead.
        int end = Character.isHighSurrogate(reason.charAt(511)) ? 511 : 512;
        return reason.substring(0, end);
    }
}
```

`truncate` is not decoration: `failureReason` carries a downstream exception message, which can be arbitrarily long, and the column is 512 characters. Without it a long message from Account turns a recorded failure into an unrecorded one.

- [ ] **Step 9: Run the entity test to verify it passes**

Run: `./mvnw -pl transfer-service test -Dtest=TransferTest`
Expected: PASS, 7 tests.

- [ ] **Step 10: Write the failing repository test**

```java
// transfer-service/src/test/java/com/showcase/transfer/domain/TransferRepositoryTest.java
package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class TransferRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void savesAndReloadsATransfer() {
        Transfer saved = transferRepository.save(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00")));

        Optional<Transfer> found = transferRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(found.get().getAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void persistsTheFailureCodeAsAString() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        Transfer saved = transferRepository.saveAndFlush(transfer);

        Transfer reloaded = transferRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(reloaded.getFailureReason()).isEqualTo("not enough money");
        assertThat(reloaded.getSettledAt()).isNotNull();
    }

    @Test
    void findsTransfersByStatus() {
        Transfer stranded = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        stranded.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        transferRepository.saveAndFlush(stranded);

        List<Transfer> found = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED);

        assertThat(found).extracting(Transfer::getId).contains(stranded.getId());
    }
}
```

- [ ] **Step 11: Run it to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=TransferRepositoryTest`
Expected: FAIL — compilation error, `TransferRepository` does not exist.

- [ ] **Step 12: Create the repository**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferRepository.java
package com.showcase.transfer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findByStatus(TransferStatus status);
}
```

- [ ] **Step 13: Run the module test suite**

Run: `./mvnw -pl transfer-service test`
Expected: PASS. Docker must be running for the Testcontainers Postgres.

- [ ] **Step 14: Commit**

```bash
git add pom.xml account-service/Dockerfile transfer-service
git commit -m "feat(transfer): add transfer-service module with Transfer entity and repository"
```

---

### Task 4: AccountClient over a blocking RestClient

The saga branches on exactly two questions: did Account reject this, or is Account broken? This task collapses every HTTP outcome onto those two exceptions so `TransferService` never touches an HTTP status.

**Files:**
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/AccountView.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/AccountProblem.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/AccountRejectedException.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/AccountServiceUnavailableException.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/AccountClient.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/AccountClientProperties.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/AccountClientConfig.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/client/AccountClientTest.java`

**Interfaces:**
- Consumes: Account Service HTTP API (`GET /accounts/{id}`, `POST /accounts/{id}/debit`, `POST /accounts/{id}/credit`) and its `ProblemDetail` `code` property from Task 1.
- Produces: `AccountClient` with `AccountView getAccount(UUID id)`, `void debit(UUID id, BigDecimal amount)`, `void credit(UUID id, BigDecimal amount)`. Each throws `AccountRejectedException` (with `getCode()` returning Account's code string, and `getDetail()`) on 4xx, or `AccountServiceUnavailableException` on 5xx / timeout / connection failure. Task 5 catches exactly these two.

- [ ] **Step 1: Write the failing client test**

`MockRestServiceServer.bindTo(RestClient.Builder)` intercepts at the request-factory level — no server, no port, no container. That is why `AccountClient` takes a built `RestClient` in its constructor rather than constructing one itself.

```java
// transfer-service/src/test/java/com/showcase/transfer/client/AccountClientTest.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountClientTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://account-service:8081";

    private MockRestServiceServer server;
    private AccountClient accountClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        accountClient = new AccountClient(builder.build(), new ObjectMapper());
    }

    @Test
    void getAccountReturnsTheBalance() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":100.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        AccountView account = accountClient.getAccount(ACCOUNT_ID);

        assertThat(account.id()).isEqualTo(ACCOUNT_ID);
        assertThat(account.balance()).isEqualByComparingTo("100.00");
        server.verify();
    }

    @Test
    void getAccountThrowsRejectedWithAccountCodeOn404() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + ACCOUNT_ID));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void debitPostsTheAmountAndSucceeds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(40.00))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":60.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"));

        server.verify();
    }

    @Test
    void debitThrowsRejectedOnInsufficientFunds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "not enough money"));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00")))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    void throwsUnavailableOnServerError() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00")))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void throwsUnavailableWhenTheConnectionFails() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andRespond(withException(new java.net.ConnectException("connection refused")));

        assertThatThrownBy(() -> accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00")))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void throwsRejectedWithUnknownCodeWhenTheErrorBodyIsNotAProblem() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway says no</html>"));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("UNKNOWN"));
    }

    private static org.springframework.test.web.client.ResponseCreator problem(
            HttpStatus status, String code, String detail) {
        return withStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"type":"https://showcase.example/errors/x","title":"t","status":%d,
                         "detail":"%s","code":"%s","timestamp":"2026-09-10T12:00:00Z"}
                        """.formatted(status.value(), detail, code));
    }
}
```

The last test matters more than it looks: a proxy, gateway, or load balancer between the services can return HTML or an empty body on an error, and a client that assumes a well-formed problem body throws a Jackson parse exception instead of the domain exception the saga knows how to handle.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=AccountClientTest`
Expected: FAIL — compilation error, none of the `client` package types exist.

- [ ] **Step 3: Create the DTOs and exceptions**

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountView.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** The subset of Account Service's response this service actually reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountView(UUID id, BigDecimal balance) {
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountProblem.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal binding for Account Service's RFC 7807 error body. Deliberately not
 * Spring's ProblemDetail: this parses with a plain ObjectMapper, with no dependency
 * on Boot's Jackson auto-configuration, so it works identically in tests.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AccountProblem(String code, String detail) {
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountRejectedException.java
package com.showcase.transfer.client;

import lombok.Getter;

/** Account Service understood the request and refused it (4xx). Retrying will not help. */
@Getter
public class AccountRejectedException extends RuntimeException {

    private final String code;
    private final String detail;

    public AccountRejectedException(String code, String detail) {
        super("Account Service rejected the request [%s]: %s".formatted(code, detail));
        this.code = code;
        this.detail = detail;
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountServiceUnavailableException.java
package com.showcase.transfer.client;

/** Account Service is broken or unreachable (5xx, timeout, connection failure). */
public class AccountServiceUnavailableException extends RuntimeException {

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Create the client**

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountClient.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls Account Service over blocking HTTP. Every outcome collapses onto two
 * exceptions so the saga can branch on "rejected" versus "broken" without ever
 * seeing an HTTP status.
 */
public class AccountClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AccountClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public AccountView getAccount(UUID accountId) {
        // A 204, or a 200 with Content-Length: 0, makes the message converter return null.
        // Without this guard the saga NPEs on account.balance() instead of branching.
        AccountView account = call(() -> restClient.get()
                .uri("/accounts/{id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(HttpStatusCode::is5xxServerError, this::unavailable)
                .body(AccountView.class));
        if (account == null) {
            throw new AccountServiceUnavailableException(
                    "Account Service returned an empty body for " + accountId);
        }
        return account;
    }

    public void debit(UUID accountId, BigDecimal amount) {
        post(accountId, amount, "debit");
    }

    public void credit(UUID accountId, BigDecimal amount) {
        post(accountId, amount, "credit");
    }

    private void post(UUID accountId, BigDecimal amount, String operation) {
        call(() -> restClient.post()
                .uri("/accounts/{id}/{operation}", accountId, operation)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(HttpStatusCode::is5xxServerError, this::unavailable)
                .toBodilessEntity());
    }

    /**
     * Connection refused, DNS failure and read timeout all surface as
     * ResourceAccessException rather than a status code, so they are translated here
     * instead of in an onStatus handler.
     */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            // Deliberately the broad superclass, not just ResourceAccessException.
            // ResourceAccessException covers connect-refused/DNS/read-timeout, but a 2xx
            // carrying an unreadable body throws UnknownContentTypeException (proxy
            // interstitial HTML) or a plain RestClientException (malformed JSON) instead.
            // Catching only the narrow type lets those escape BOTH domain exceptions and
            // reach the saga unhandled -- which defeats this class entirely. The two
            // domain exceptions do not extend RestClientException, so they still pass
            // through untouched.
            throw new AccountServiceUnavailableException("Account Service call failed: " + ex.getMessage(), ex);
        }
    }

    private void rejected(HttpRequest request, ClientHttpResponse response) throws IOException {
        AccountProblem problem = readProblem(response);
        throw new AccountRejectedException(problem.code(), problem.detail());
    }

    private void unavailable(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new AccountServiceUnavailableException(
                "Account Service returned " + response.getStatusCode().value());
    }

    /**
     * An error body that is not a well-formed problem document (an HTML page from a
     * proxy, an empty body) must still produce a domain exception, never a parse error.
     */
    private AccountProblem readProblem(ClientHttpResponse response) {
        try {
            AccountProblem problem = objectMapper.readValue(response.getBody(), AccountProblem.class);
            if (problem == null || problem.code() == null) {
                return new AccountProblem("UNKNOWN", "Account Service returned an unrecognised error body");
            }
            return problem;
        } catch (Exception ex) {
            return new AccountProblem("UNKNOWN", "Account Service returned an unreadable error body");
        }
    }
}
```

- [ ] **Step 5: Create the properties and configuration**

Timeouts are not optional here. With no circuit breaker yet (Plan 3), a hung Account Service would otherwise block every in-flight transfer indefinitely, and the default `RestClient` request factory has no read timeout at all.

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountClientProperties.java
package com.showcase.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "account-service")
public record AccountClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    // Boot's binder skips a null Duration silently, which would leave the RestClient with
    // NO timeout at all -- restoring exactly the unbounded-block failure these properties
    // exist to prevent, with no startup error to warn anyone. Default rather than trust
    // every future profile and SPRING_APPLICATION_JSON override to set them.
    public AccountClientProperties {
        connectTimeout = (connectTimeout != null) ? connectTimeout : Duration.ofSeconds(2);
        readTimeout = (readTimeout != null) ? readTimeout : Duration.ofSeconds(5);
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/AccountClientConfig.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AccountClientConfig {

    @Bean
    public AccountClient accountClient(RestClient.Builder builder,
                                       AccountClientProperties properties,
                                       ObjectMapper objectMapper) {
        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(properties.connectTimeout())
                                .withReadTimeout(properties.readTimeout())))
                .build();
        return new AccountClient(restClient, objectMapper);
    }
}
```

- [ ] **Step 6: Run the client test to verify it passes**

Run: `./mvnw -pl transfer-service test -Dtest=AccountClientTest`
Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add transfer-service/src/main/java/com/showcase/transfer/client transfer-service/src/test/java/com/showcase/transfer/client
git commit -m "feat(transfer): add AccountClient over blocking RestClient with timeouts"
```

---
### Task 5: The saga orchestrator

**Files:**
- Create: `transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/service/TransferServiceTest.java`

**Interfaces:**
- Consumes: `TransferRepository`, `Transfer`, `TransferStatus`, `TransferFailureCode` (Task 3); `AccountClient`, `AccountRejectedException`, `AccountServiceUnavailableException` (Task 4).
- Produces: `TransferService` with `Transfer execute(UUID fromAccountId, UUID toAccountId, BigDecimal amount)` — returns the persisted `Transfer` in whichever terminal state it reached, and propagates `SameAccountTransferException` for a self-transfer; `Transfer getTransfer(UUID id)`; `List<Transfer> listTransfers(TransferStatus status)` where a null status means all. Task 6's controller calls all three.

- [ ] **Step 1: Write the failing saga test**

Nine tests, one per path through the saga. Note what each asserts about calls that must *not* happen — a saga that debits before it should is the failure mode this test exists to catch.

```java
// transfer-service/src/test/java/com/showcase/transfer/service/TransferServiceTest.java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.client.AccountView;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, accountClient);
    }

    // Called per-test rather than from setUp: the self-transfer test never reaches the
    // repository, and Mockito strict stubbing rightly fails an unused stub. Keeping
    // strict stubbing is worth the extra line.
    private void repositoryEchoesSaves() {
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void bothAccountsExist() {
        when(accountClient.getAccount(FROM)).thenReturn(new AccountView(FROM, new BigDecimal("100.00")));
        when(accountClient.getAccount(TO)).thenReturn(new AccountView(TO, new BigDecimal("5.00")));
    }

    @Test
    void completesWhenBothLegsSucceed() {
        repositoryEchoesSaves();
        bothAccountsExist();

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getSettledAt()).isNotNull();
        assertThat(result.getFailureCode()).isNull();
        verify(accountClient).debit(FROM, AMOUNT);
        verify(accountClient).credit(TO, AMOUNT);
    }

    @Test
    void failsWithoutDebitingWhenTheSourceAccountDoesNotExist() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM))
                .thenThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + FROM));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any());
        verify(accountClient, never()).credit(any(), any());
    }

    @Test
    void failsWithoutDebitingWhenTheDestinationAccountDoesNotExist() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM)).thenReturn(new AccountView(FROM, new BigDecimal("100.00")));
        when(accountClient.getAccount(TO))
                .thenThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient, never()).debit(any(), any());
    }

    @Test
    void failsWhenAccountServiceIsUnreachableDuringPreValidation() {
        repositoryEchoesSaves();
        when(accountClient.getAccount(FROM))
                .thenThrow(new AccountServiceUnavailableException("connection refused"));

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        verify(accountClient, never()).debit(any(), any());
    }

    @Test
    void failsWhenTheDebitIsRejectedForInsufficientFunds() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("INSUFFICIENT_FUNDS", "not enough money"))
                .when(accountClient).debit(FROM, AMOUNT);

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(result.getFailureReason()).isEqualTo("not enough money");
        verify(accountClient, never()).credit(any(), any());
    }

    @Test
    void failsWhenTheDebitCannotReachAccountService() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).debit(FROM, AMOUNT);

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
        verify(accountClient, never()).credit(any(), any());
    }

    @Test
    void requiresCompensationWhenTheCreditIsRejected() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountRejectedException("ACCOUNT_NOT_FOUND", "Account not found: " + TO))
                .when(accountClient).credit(TO, AMOUNT);

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        verify(accountClient).debit(FROM, AMOUNT);
    }

    @Test
    void requiresCompensationWhenTheCreditCannotReachAccountService() {
        repositoryEchoesSaves();
        bothAccountsExist();
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("read timed out"))
                .when(accountClient).credit(TO, AMOUNT);

        Transfer result = transferService.execute(FROM, TO, AMOUNT);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
        assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE);
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> transferService.execute(FROM, FROM, AMOUNT))
                .isInstanceOf(SameAccountTransferException.class);

        verify(accountClient, never()).getAccount(any());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=TransferServiceTest`
Expected: FAIL — compilation error, `TransferService` does not exist.

- [ ] **Step 3: Write the orchestrator**

```java
// transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java
package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
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

    public TransferService(TransferRepository transferRepository, AccountClient accountClient) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
    }

    public Transfer execute(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        // Constructor guards reject a self-transfer before anything is persisted.
        // This save stays OUTSIDE the try below on purpose: SameAccountTransferException
        // must propagate to the caller as a 400, not be swallowed into UNEXPECTED_ERROR.
        Transfer transfer = transferRepository.save(new Transfer(fromAccountId, toAccountId, amount));
        log.info("Transfer {} started: {} -> {} amount {}", transfer.getId(), fromAccountId, toAccountId, amount);

        // Tracks whether the debit leg committed, so the catch-all below knows whether an
        // unexpected failure left money stranded or left everything untouched.
        boolean debited = false;

        try {
            // Step 1: pre-validate both accounts. An optimisation for the common
            // mistyped-id case, NOT a guarantee -- an account can still disappear between
            // here and the debit, which is why step 2 handles every rejection on its own.
            try {
                accountClient.getAccount(fromAccountId);
                accountClient.getAccount(toAccountId);
            } catch (AccountRejectedException ex) {
                return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return fail(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 2: debit the source.
            try {
                accountClient.debit(fromAccountId, amount);
            } catch (AccountRejectedException ex) {
                // Account understood and refused. Nothing moved -- genuinely clean.
                return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                // AMBIGUOUS, and the most dangerous state in this service. A read timeout is
                // indistinguishable from "the request never arrived": Account may have
                // committed the debit and failed to tell us. FAILED here therefore means
                // "debit NOT CONFIRMED", never "debit definitely did not happen".
                //
                // It is deliberately NOT routed to COMPENSATION_REQUIRED. That state means
                // "debit definitely succeeded, credit definitely did not", and the compensator
                // in a later plan credits the source back on the strength of it. Feeding an
                // ambiguous outcome into it would make the compensator invent money whenever
                // the debit never actually landed -- strictly worse than under-reporting.
                //
                // BLOCKING PRECONDITION FOR THE COMPENSATOR PLAN: it must reconcile against
                // Account before crediting anything back, and must not read this combination
                // (FAILED + ACCOUNT_SERVICE_UNAVAILABLE on the debit leg) as "no money moved".
                log.error("Transfer {} debit outcome UNKNOWN for account {} amount {}: {}. "
                                + "Recorded FAILED, but the debit may have committed -- needs reconciliation.",
                        transfer.getId(), fromAccountId, amount, ex.getMessage());
                return fail(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }
            debited = true;

            // Step 3: credit the destination. Past this point the source is already debited,
            // so business rejection and infrastructure failure have identical consequences:
            // funds are stranded and something has to put them back. Plan 3 adds that.
            try {
                accountClient.credit(toAccountId, amount);
            } catch (AccountRejectedException ex) {
                return strand(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            transfer.markCompleted();
            log.info("Transfer {} completed", transfer.getId());
            return transferRepository.save(transfer);
        } catch (RuntimeException ex) {
            // Anything the two client exceptions do not cover -- a DataAccessException or an
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

    public Transfer getTransfer(UUID id) {
        return transferRepository.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
    }

    public List<Transfer> listTransfers(TransferStatus status) {
        return status == null ? transferRepository.findAll() : transferRepository.findByStatus(status);
    }

    private Transfer fail(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markFailed(code, reason);
        log.info("Transfer {} failed [{}]: {}", transfer.getId(), code, reason);
        return transferRepository.save(transfer);
    }

    private Transfer strand(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markCompensationRequired(code, reason);
        log.error("Transfer {} needs compensation: {} was debited {} but {} was not credited [{}]: {}",
                transfer.getId(), transfer.getFromAccountId(), transfer.getAmount(),
                transfer.getToAccountId(), code, reason);
        return transferRepository.save(transfer);
    }
}
```

- [ ] **Step 4: Run the saga test to verify it passes**

Run: `./mvnw -pl transfer-service test -Dtest=TransferServiceTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add transfer-service/src/main/java/com/showcase/transfer/service transfer-service/src/test/java/com/showcase/transfer/service
git commit -m "feat(transfer): orchestrate the transfer saga across Account Service"
```

---

### Task 6: Transfer REST API

**Files:**
- Create: `transfer-service/src/main/java/com/showcase/transfer/api/CreateTransferRequest.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/api/TransferResponse.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/api/TransferFailedException.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/api/Problems.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/api/TransferController.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/api/ApiExceptionHandler.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/api/TransferControllerTest.java`

**Interfaces:**
- Consumes: `TransferService.execute/getTransfer/listTransfers` (Task 5); `Transfer` getters (Task 3).
- Produces: `POST /transfers`, `GET /transfers/{id}`, `GET /transfers?status=`. Task 7's Compose wiring and README exercise these.

`TransferControllerTest` uses `@WebMvcTest` with a mocked `TransferService`: a Spring slice test with `MockMvc`, no server socket, no database, no container. It is the only thing that covers the outcome-to-HTTP-status mapping, which is pure branching logic and easy to get wrong.

- [ ] **Step 1: Write the failing controller test**

```java
// transfer-service/src/test/java/com/showcase/transfer/api/TransferControllerTest.java
package com.showcase.transfer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransferService transferService;

    private String requestBody() throws Exception {
        return objectMapper.writeValueAsString(new CreateTransferRequest(FROM, TO, AMOUNT));
    }

    private Transfer pendingTransfer() {
        return new Transfer(FROM, TO, AMOUNT);
    }

    @Test
    void returns201WhenTheTransferCompletes() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompleted();
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void returns422WhenTheTransferFailsForInsufficientFunds() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.transferStatus").value("FAILED"));
    }

    @Test
    void returns503WhenAccountServiceIsUnavailable() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "connection refused");
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void returns500WhenTheTransferNeedsCompensation() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMPENSATION_REQUIRED"))
                .andExpect(jsonPath("$.transferStatus").value("COMPENSATION_REQUIRED"));
    }

    @Test
    void returns400ForASelfTransfer() throws Exception {
        when(transferService.execute(any(), any(), any())).thenThrow(new SameAccountTransferException(FROM));

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @Test
    void returns400ForANonPositiveAmount() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateTransferRequest(FROM, TO, new BigDecimal("0.00")));

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returns404ForAnUnknownTransfer() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(transferService.getTransfer(eq(unknown))).thenThrow(new TransferNotFoundException(unknown));

        mockMvc.perform(get("/transfers/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }
}
```

`Transfer.id` is null in this test — ids are assigned by JPA on persist and this slice
never touches a database — so the tests assert on `transferStatus` rather than
`transferId`. The handler sets both; `transferId` is verified against a real database in
Task 7, Step 8.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -pl transfer-service test -Dtest=TransferControllerTest`
Expected: FAIL — compilation error, none of the `api` package types exist.

- [ ] **Step 3: Create the DTOs**

```java
// transfer-service/src/main/java/com/showcase/transfer/api/CreateTransferRequest.java
package com.showcase.transfer.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/api/TransferResponse.java
package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        TransferStatus status,
        TransferFailureCode failureCode,
        String failureReason,
        Instant createdAt,
        Instant settledAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getAmount(),
                transfer.getStatus(),
                transfer.getFailureCode(),
                transfer.getFailureReason(),
                transfer.getCreatedAt(),
                transfer.getSettledAt());
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/api/TransferFailedException.java
package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import lombok.Getter;

/** Carries a non-completed transfer to the exception handler, which renders it as a problem response. */
@Getter
public class TransferFailedException extends RuntimeException {

    private final transient Transfer transfer;

    public TransferFailedException(Transfer transfer) {
        super("Transfer %s ended as %s".formatted(transfer.getId(), transfer.getStatus()));
        this.transfer = transfer;
    }
}
```

- [ ] **Step 4: Create the problem factory**

Deliberately duplicated from Account Service rather than shared — see "Design Decisions" above.

```java
// transfer-service/src/main/java/com/showcase/transfer/api/Problems.java
package com.showcase.transfer.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

final class Problems {

    private static final URI TYPE_BASE = URI.create("https://showcase.example/errors/");

    private Problems() {
    }

    static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
```

- [ ] **Step 5: Create the controller**

```java
// transfer-service/src/main/java/com/showcase/transfer/api/TransferController.java
package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferStatus;
import com.showcase.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody CreateTransferRequest request) {
        Transfer transfer = transferService.execute(
                request.fromAccountId(), request.toAccountId(), request.amount());

        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new TransferFailedException(transfer);
        }
        return ResponseEntity.created(URI.create("/transfers/" + transfer.getId()))
                .body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    public TransferResponse getTransfer(@PathVariable UUID id) {
        return TransferResponse.from(transferService.getTransfer(id));
    }

    @GetMapping
    public List<TransferResponse> listTransfers(@RequestParam(required = false) TransferStatus status) {
        return transferService.listTransfers(status).stream()
                .map(TransferResponse::from)
                .toList();
    }
}
```

- [ ] **Step 6: Create the exception handler**

```java
// transfer-service/src/main/java/com/showcase/transfer/api/ApiExceptionHandler.java
package com.showcase.transfer.api;

import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferStatus;
import com.showcase.transfer.service.TransferPersistenceException;

import java.time.Instant;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INTERNAL_FAILURE_DETAIL =
            "The transfer could not be completed due to an internal error";

    @ExceptionHandler(TransferFailedException.class)
    public ProblemDetail handleTransferFailed(TransferFailedException ex) {
        Transfer transfer = ex.getTransfer();

        HttpStatus status;
        String code;
        String title;
        if (transfer.getStatus() == TransferStatus.COMPENSATION_REQUIRED) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "COMPENSATION_REQUIRED";
            title = "Transfer needs compensation";
        } else if (transfer.getFailureCode() != null) {
            code = transfer.getFailureCode().name();
            title = "Transfer failed";
            status = switch (transfer.getFailureCode()) {
                case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS, CONCURRENT_MODIFICATION ->
                        HttpStatus.UNPROCESSABLE_ENTITY;
                case ACCOUNT_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                case UNEXPECTED_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        } else {
            // A non-terminal transfer (PENDING) reached the controller, so there is no failure
            // code to read. Unreachable while TransferService holds to its contract, but reading
            // the code unguarded would NPE inside this method -- and an exception thrown from an
            // @ExceptionHandler is not routed to another one: the resolver gives up and the
            // container renders its own error page, with no code and, worse, no transferId.
            // Degrade to a well-formed problem instead, so the id survives.
            logger.error("Transfer %s reached the API in non-terminal state %s"
                    .formatted(transfer.getId(), transfer.getStatus()));
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = "UNEXPECTED_ERROR";
            title = "Transfer failed";
        }

        ProblemDetail problem = Problems.of(status, code, title,
                detailFor(transfer.getFailureCode(), transfer.getFailureReason()));
        // Always hand back the id: a caller that got a 500 still needs to be able to
        // fetch the record and see what state the transfer ended in.
        problem.setProperty("transferId", transfer.getId());
        problem.setProperty("transferStatus", transfer.getStatus());
        return problem;
    }

    /**
     * Chooses what the caller is allowed to read. A business rejection's recorded reason was
     * written for them and says what to do next, so it goes out as-is. The other two codes carry
     * operational text -- {@code ex.toString()} for an unexpected error, and a client message
     * that can name Account Service's internal host and port for an outage -- which is for the
     * log and the persisted row, not for an HTTP response. A null code reaches here only from
     * the non-terminal fallback above.
     */
    private static String detailFor(TransferFailureCode failureCode, String failureReason) {
        if (failureCode == null) {
            return INTERNAL_FAILURE_DETAIL;
        }
        return switch (failureCode) {
            case ACCOUNT_NOT_FOUND, INSUFFICIENT_FUNDS, CONCURRENT_MODIFICATION -> failureReason;
            case ACCOUNT_SERVICE_UNAVAILABLE -> "Account Service is currently unavailable";
            case UNEXPECTED_ERROR -> INTERNAL_FAILURE_DETAIL;
        };
    }

    @ExceptionHandler(TransferPersistenceException.class)
    public ProblemDetail handlePersistenceFailure(TransferPersistenceException ex) {
        logger.error("Transfer %s could not be persisted".formatted(ex.getTransferId()), ex);

        ProblemDetail problem = Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Internal error", INTERNAL_FAILURE_DETAIL);
        // The id, and only the id. The saga's outcome was never written, so the row's status is
        // whatever the last successful save left -- reporting the in-memory state as
        // transferStatus would tell the caller something the database does not agree with.
        // GET /transfers/{id} is the honest answer, and needs exactly this.
        problem.setProperty("transferId", ex.getTransferId());
        return problem;
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleNotFound(TransferNotFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found", ex.getMessage());
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ProblemDetail handleSameAccount(SameAccountTransferException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT_TRANSFER", "Same account", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", detail));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Malformed request body"));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Invalid value for parameter: " + ex.getPropertyName()));
    }

    /**
     * Stamps the {@code code}/{@code timestamp} invariant onto the ~14 MVC exception types
     * this class does not override explicitly (405, 415, 406, unmapped paths, and the rest).
     * Without it they render a ProblemDetail with NO code at all, and a consumer reading
     * {@code code} gets null on exactly the paths a mis-wired caller hits most. The handler
     * is duplicated per service rather than shared, so every service carries its own copy
     * of this guard.
     */
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
}
```

- [ ] **Step 7: Run the whole module test suite**

Run: `./mvnw -pl transfer-service test`
Expected: PASS — 75 tests across the reactor (account-service 23, transfer-service 52). Do not match per-class counts against this line: earlier tasks grew several of these classes during their review rounds, so per-class figures drift. The reactor total is the check.

- [ ] **Step 8: Commit**

```bash
git add transfer-service/src/main/java/com/showcase/transfer/api transfer-service/src/test/java/com/showcase/transfer/api
git commit -m "feat(transfer): add REST API with RFC 7807 problem responses"
```

---
### Task 7: Docker Compose wiring, README, and roadmap

**Files:**
- Modify: `docker/postgres/init-db.sh`
- Modify: `.env.example`
- Modify: `.env` (local only, gitignored)
- Create: `transfer-service/Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: `docker compose up --build` bringing up Postgres (two databases), Account Service on 8081, and Transfer Service on 8082.

- [ ] **Step 1: Provision the second database**

```bash
# docker/postgres/init-db.sh: append after the existing account blocks
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE transfer;
    CREATE USER transfer_service WITH PASSWORD '$TRANSFER_DB_PASSWORD';
    GRANT ALL PRIVILEGES ON DATABASE transfer TO transfer_service;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "transfer" <<-EOSQL
    GRANT ALL PRIVILEGES ON SCHEMA public TO transfer_service;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO transfer_service;
EOSQL
```

**Postgres runs `/docker-entrypoint-initdb.d` scripts only when the data directory is empty.** The `postgres-data` volume already exists from Plan 1, so this edit does nothing on an existing checkout until the volume is destroyed. Step 8 handles that. Expect to lose any locally created accounts.

- [ ] **Step 2: Add the password variable**

```bash
# .env.example: append
TRANSFER_DB_PASSWORD=transfer_service
```

```bash
# .env is gitignored -- add the same line to your local copy
echo "TRANSFER_DB_PASSWORD=transfer_service" >> .env
```

- [ ] **Step 3: Create the Transfer Dockerfile**

Copies both module POMs for the same reason Account's does — Maven parses the whole aggregator even with `-pl`.

```dockerfile
# transfer-service/Dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
RUN chmod +x mvnw && ./mvnw -pl transfer-service -am dependency:go-offline -B
COPY transfer-service/src transfer-service/src
RUN ./mvnw -pl transfer-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/transfer-service/target/transfer-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 4: Wire up Compose**

```yaml
# docker-compose.yml: add TRANSFER_DB_PASSWORD to the postgres service environment
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      ACCOUNT_DB_PASSWORD: ${ACCOUNT_DB_PASSWORD}
      TRANSFER_DB_PASSWORD: ${TRANSFER_DB_PASSWORD}
```

```yaml
# docker-compose.yml: add under services:
  transfer-service:
    build:
      context: .
      dockerfile: transfer-service/Dockerfile
    container_name: showcase-transfer-service
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: transfer
      DB_USER: transfer_service
      DB_PASSWORD: ${TRANSFER_DB_PASSWORD}
      ACCOUNT_SERVICE_URL: http://account-service:8081
    ports:
      - "8082:8082"
    depends_on:
      postgres:
        condition: service_healthy
      account-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8082/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 40s
```

- [ ] **Step 5: Run the full build and test suite**

Run: `./mvnw -B test`
Expected: PASS for both modules. This is the same command CI runs.

- [ ] **Step 6: Bring the stack up from a clean volume**

```bash
docker compose down -v
docker compose up --build -d
docker compose ps
```
Expected: `showcase-postgres`, `showcase-account-service` and `showcase-transfer-service` all `(healthy)` within about two minutes. `-v` is what makes the init script re-run and create the `transfer` database.

- [ ] **Step 7: Verify the happy path by hand**

```bash
# Two accounts
FROM_ID=$(curl -s -X POST http://localhost:8081/accounts -H "Content-Type: application/json" \
  -d '{"ownerName":"Ada Lovelace","initialBalance":100.00}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
TO_ID=$(curl -s -X POST http://localhost:8081/accounts -H "Content-Type: application/json" \
  -d '{"ownerName":"Alan Turing","initialBalance":0.00}' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

# Transfer
curl -i -X POST http://localhost:8082/transfers -H "Content-Type: application/json" \
  -d "{\"fromAccountId\":\"$FROM_ID\",\"toAccountId\":\"$TO_ID\",\"amount\":40.00}"
```
Expected: `201 Created`, body `status` is `COMPLETED`. Then `curl http://localhost:8081/accounts/$FROM_ID` shows `60.00` and `curl http://localhost:8081/accounts/$TO_ID` shows `40.00`.

- [ ] **Step 8: Verify the failure paths by hand**

```bash
# Insufficient funds -> 422, nothing moves
curl -i -X POST http://localhost:8082/transfers -H "Content-Type: application/json" \
  -d "{\"fromAccountId\":\"$FROM_ID\",\"toAccountId\":\"$TO_ID\",\"amount\":10000.00}"
```
Expected: `422`, `application/problem+json`, `"code":"INSUFFICIENT_FUNDS"`, and a `transferId`. `GET /accounts/$FROM_ID` still shows `60.00`.

```bash
# Unknown destination -> 422, no debit
curl -i -X POST http://localhost:8082/transfers -H "Content-Type: application/json" \
  -d "{\"fromAccountId\":\"$FROM_ID\",\"toAccountId\":\"00000000-0000-0000-0000-000000000000\",\"amount\":10.00}"
```
Expected: `422`, `"code":"ACCOUNT_NOT_FOUND"`. Balance unchanged.

```bash
# Account Service down -> 503
docker compose stop account-service
curl -i -X POST http://localhost:8082/transfers -H "Content-Type: application/json" \
  -d "{\"fromAccountId\":\"$FROM_ID\",\"toAccountId\":\"$TO_ID\",\"amount\":10.00}"
docker compose start account-service
```
Expected: `503`, `"code":"ACCOUNT_SERVICE_UNAVAILABLE"`, returned within a few seconds rather than hanging — that is the connect timeout doing its job.

```bash
# The recorded history, including anything stranded
curl -s http://localhost:8082/transfers
curl -s "http://localhost:8082/transfers?status=COMPENSATION_REQUIRED"
```
Expected: the completed and failed transfers above; the `COMPENSATION_REQUIRED` list is empty (reaching that state needs the credit leg to fail specifically, which the steps above do not produce).

- [ ] **Step 9: Update the README**

```markdown
<!-- README.md: replace the "This starts Postgres and Account Service" line -->
This starts Postgres, Account Service (8081) and Transfer Service (8082).
More services land in later plans.

<!-- README.md: replace the Swagger section -->
## Try it (Swagger UI)

Both APIs are browsable and callable straight from a browser:

- Account Service — http://localhost:8081/swagger-ui.html
- Transfer Service — http://localhost:8082/swagger-ui.html

<!-- README.md: append after the existing curl section -->
## Transfers

A transfer is a saga: Transfer Service checks both accounts, debits the source, then
credits the destination, recording the outcome in its own database at each step. There
is no distributed transaction — each step commits independently, which is why the
failure states below exist.

    # Transfer money (replace the ids with two accounts you created)
    curl -X POST http://localhost:8082/transfers \
      -H "Content-Type: application/json" \
      -d '{"fromAccountId": "<from>", "toAccountId": "<to>", "amount": 40.00}'

    # Fetch one transfer
    curl http://localhost:8082/transfers/<id>

    # List transfers, optionally by status
    curl http://localhost:8082/transfers
    curl "http://localhost:8082/transfers?status=COMPENSATION_REQUIRED"

Errors are RFC 7807 problem documents with a stable `code`:

    # Insufficient funds -> 422 INSUFFICIENT_FUNDS, no money moves
    # Unknown account    -> 422 ACCOUNT_NOT_FOUND, no money moves
    # Account down       -> 503 ACCOUNT_SERVICE_UNAVAILABLE, no money moves

### Known gap: COMPENSATION_REQUIRED

If the debit succeeds and the credit then fails, the money is stranded at the source.
This release records that as `COMPENSATION_REQUIRED`, logs it at ERROR, and makes it
listable — but does not fix it. Compensation (crediting the source back) is Plan 3.
The gap is deliberate: it makes visible exactly why saga compensation exists.
```

- [ ] **Step 10: Record the error contract in the design spec**

`CLAUDE.md` makes `docs/microservices-showcase-design.md` the source of truth for
architecture and service boundaries, and says to update it — not just the plan — when an
implementation decision changes the actual architecture. The inter-service error contract
is exactly that, and the spec currently says nothing about it. Add a short subsection to
§3 (Tech Stack), after the Resilience bullet:

```markdown
- **Error contract:** every service returns RFC 7807 `application/problem+json` on error,
  carrying a stable machine-readable `code` property alongside the standard `type`,
  `title`, `status` and `detail` fields. Consumers branch on `code`, never on the prose in
  `detail`, and never on the `type` URI (which is an identifier, not a dereferenceable
  URL). Un-enumerated 4xx statuses carry `REQUEST_REJECTED`, un-enumerated 5xx carry
  `INTERNAL_ERROR`, so the property is never absent. The handler and its codes are
  duplicated per service rather than shared through a common module: a shared DTO jar
  turns every contract change into a lockstep redeploy of every service.
```

Do not touch `docs/plan-1-foundation-account-service.md` — it is a historical record of
what Plan 1 built, and its `ErrorResponse` references are correct as history.

- [ ] **Step 11: Update the roadmap**

Replace the plan table in `docs/roadmap.md` with the reordered sequence — Plan 2 shrank during brainstorming, and what it dropped became Plans 3 and 4.

```markdown
| # | Plan | Status | Scope |
|---|------|--------|-------|
| 1 | [Foundation + Account Service](plan-1-foundation-account-service.md) | ✅ Done | Project scaffolding, Account entity/repo with optimistic locking, REST API, Docker Compose + Postgres |
| 2 | [Transfer Service + Synchronous Saga](plan-2-transfer-service-saga.md) | 🚧 In progress | Transfer entity/ledger, sync saga (pre-validate → debit → credit) over RestClient, RFC 7807 ProblemDetail on both services, Actuator + Compose healthchecks. No resilience, no compensation, no Kafka |
| 3 | Resilience4j + Compensation + Idempotency | Not started | CircuitBreaker/Retry/TimeLimiter on Transfer → Account; compensating credit-back that drains `COMPENSATION_REQUIRED`, with `COMPENSATION_FAILED` as the manual-review terminal state; idempotency keys on debit/credit so a retry cannot double-move money |
| 4 | Transactional outbox + Kafka + Notification | Not started | Outbox table written in the same local transaction as the transfer's terminal state, scheduled polling publisher, Kafka in KRaft mode, Notification Service consuming `TransferCompleted`/`TransferFailed` |
| 5 | Fraud Service | Not started | Stateless rule-based risk check (amount/velocity thresholds), wired into the saga as Transfer's second sync call, with fraud rejection driving compensation |
| 6 | API Gateway + Auth | Not started | Keycloak (pre-configured realm), JWT validation at the Gateway and via Spring Security Resource Server in each service |
| 7 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; Spring Cloud Contract tests; end-to-end saga test module; final `docker compose up` bringing up all services + infra |
```

Then update the deferred-items list: mark Actuator + healthchecks and the `ErrorResponse` wire-contract decision as done (resolved in Plan 2 — ProblemDetail with a `code` property, duplicated per service), move idempotency keys under Plan 3, and leave Flyway as the one still-unassigned item, noting it now has two schemas to baseline rather than one.

- [ ] **Step 12: Commit**

```bash
git add docker/postgres/init-db.sh .env.example transfer-service/Dockerfile docker-compose.yml README.md docs/roadmap.md
git commit -m "feat: run transfer-service in docker compose alongside account-service"
```

- [ ] **Step 13: Open the pull request**

Per `CLAUDE.md`: push the branch, open a PR, get a subagent review, then **stop**. Do not merge — the user reviews and merges.

```bash
git push -u origin feature/plan-2-transfer-service-saga
gh pr create --base master --title "feat: Transfer Service and the synchronous saga (Plan 2)" --body "$(cat <<'BODY'
Implements [Plan 2](docs/plan-2-transfer-service-saga.md).

## What this adds
- `transfer-service` module (port 8082, own `transfer` database)
- Synchronous transfer saga: pre-validate both accounts, debit the source, credit the
  destination. Not a distributed transaction — each state change commits independently.
- `AccountClient` over blocking `RestClient`, with connect/read timeouts, collapsing every
  HTTP outcome onto "Account rejected this" or "Account is broken"
- RFC 7807 `ProblemDetail` with a stable `code` property on **both** services
  (`ErrorResponse` is removed from Account Service — this is a wire-contract change)
- Actuator `/actuator/health` and Docker Compose healthchecks on both services

## Deliberately not included
Resilience4j, compensation, idempotency keys, the transactional outbox and Kafka, and the
Fraud Service. A transfer whose debit succeeds and whose credit fails is recorded as
`COMPENSATION_REQUIRED`, logged at ERROR, and left stranded — that gap is the starting
point for Plan 3, not an oversight. See the plan doc for the full deferral table.

## Testing
Unit tests for the entity, the saga (all nine paths), the client (`MockRestServiceServer`)
and the controller (`@WebMvcTest`); Testcontainers Postgres for persistence. Failure paths
verified by hand against a running Compose stack — see the plan's verification checklist.
BODY
)"
```

---

## Verification Checklist

Before calling this plan done, confirm each of these by running the command and reading the output — not by assuming:

- [ ] `./mvnw -B test` passes for both modules (this is what CI runs)
- [ ] `docker compose down -v && docker compose up --build -d` brings all three containers to `(healthy)`
- [ ] A transfer between two funded accounts returns `201` with `status: COMPLETED` and both balances change correctly
- [ ] An over-balance transfer returns `422` / `INSUFFICIENT_FUNDS` as `application/problem+json` and no balance changes
- [ ] With `account-service` stopped, a transfer returns `503` / `ACCOUNT_SERVICE_UNAVAILABLE` within seconds rather than hanging
- [ ] `GET /transfers` lists every attempt, successful and failed
- [ ] Account Service errors carry a `code` property (`curl -i http://localhost:8081/accounts/00000000-0000-0000-0000-000000000000`)
- [ ] Both Swagger UIs load: `http://localhost:8081/swagger-ui.html` and `http://localhost:8082/swagger-ui.html`
- [ ] The orchestrator is not transactional. `grep -rnE "^\s*@Transactional" transfer-service/src/main/java/com/showcase/transfer/service/` returns nothing. (A plain `grep "Transactional"` is the wrong check — it also matches the class Javadoc, which deliberately *mentions* `@Transactional` to explain why it is absent. Read the hits; a non-empty result from the loose grep is not itself a failure.)
