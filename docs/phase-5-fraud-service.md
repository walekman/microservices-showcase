# Fraud Service Implementation Phase (Phase 5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Fraud Service — a stateless account-blocklist screen — as the saga's second and third synchronous calls (once before the debit, once before the credit), giving the compensation machinery Phase 3 built a reliable, on-demand way to trigger through normal API calls, instead of requiring fault injection.

**Architecture:** A new stateless `fraud-service` module exposes one read-only endpoint, `GET /fraud-check?accountId=`, backed by static blocklist config. Transfer Service gets a `FraudClient` (same two-exception, CircuitBreaker+Retry shape as its existing `AccountClient`) and calls it twice per transfer: once on the source account before the debit, once on the destination account before the credit. `CompensationScheduler`'s two existing sweeps each gain a matching fraud-check gate so crash-recovery paths replay the same gate the live saga enforces.

**Tech Stack:** Same as the existing services — Spring Boot 3.3.4 / Java 21, Resilience4j (CircuitBreaker + Retry) for the new client, springdoc-openapi for the new module's Swagger UI. No new dependency versions — `fraud-service`'s `pom.xml` reuses the parent's managed versions exactly as `account-service`/`notification-service` do. No database, no Kafka.

**Spec:** [docs/phase-5-fraud-service.md](phase-5-fraud-service.md) (this document's Design Decisions and outcome table above — brainstormed with the user on 2026-09-16) and [docs/microservices-showcase-design.md](microservices-showcase-design.md) §2–§4 (component table, tech stack, saga — describes Fraud generically; this phase's Design Decisions supersede the "one call between debit and credit" framing there, reconciled by Task 5).

## Global Constraints

- Java 21 floor; Spring Boot 3.3.4. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads enabled — `fraud-service` follows the same `spring.threads.virtual.enabled: true` convention as the other two services.
- No new `TransferStatus` values and no new schema surface (see Design Decisions above) — do not add a database, a migration, or a new column anywhere in this phase.
- Lombok for entity boilerplate: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic. `fraud-service` has no entities, so this applies only to any Transfer Service changes. (CLAUDE.md)
- No Kubernetes/service mesh; local deployment is Docker Compose only, single instance per service.
- Never commit directly to `master`; work happens on `feature/phase-5-task-<N>-*` branches, one PR per task, stop after each. Subagent review is manual, on request — not automatic. (CLAUDE.md)
- springdoc-openapi stays pinned to `2.6.0`; resilience4j stays pinned to `2.4.0`. (docs/roadmap.md)
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review. Always name the model explicitly. (CLAUDE.md)

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

| Deferred | Why not now                                                                                                                                                             | Lands in |
|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---|
| Amount/velocity-threshold rules | Reconsidered during brainstorming in favor of a blocklist-only rule (see Design Decisions)                                                                              | Not currently planned; revisit only if a concrete need emerges |
| `docs/microservices-showcase-design.md` §4 prose update (single generic "Fraud rejects" framing → the two-position source/destination design) | Real doc-sync work, but separate from the code change itself                                                                                                            | This phase, as its own task, before closing |
~~`docs/microservices-showcase-design.md` §4/§8 stale `TransferCompleted`/`TransferFailed` event-pair naming~~ | Already fixed as of this brainstorm (§4 already has a "Settled in Phase 4" callout) — Task 5 removes the now-stale roadmap.md bullet about it instead of redoing the fix | N/A |
| Fraud Service admin API / persisted blocklist | Static config is sufficient for a demo; no requirement for runtime edits                                                                                                | Not currently planned |
| Auth / JWT | No security on any service yet                                                                                                                                          | Phase 6 |
| Trace propagation across the new sync hop | No OTel Collector yet                                                                                                                                                   | Phase 7 |
| `transfers fraud-rejected` business-metric counter | Full business metrics land with Prometheus/Grafana wiring                                                                                                               | Phase 7 |

---

## Tasks

### Task 1: Fraud Service module

**Files:**
- Create: `fraud-service/pom.xml`
- Modify: `pom.xml:19-23` (add `<module>fraud-service</module>`)
- Create: `fraud-service/src/main/java/com/showcase/fraud/FraudServiceApplication.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/service/FraudBlocklistProperties.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/service/FraudCheckService.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/domain/AccountBlockedException.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/api/FraudCheckController.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/api/Problems.java`
- Create: `fraud-service/src/main/java/com/showcase/fraud/api/ApiExceptionHandler.java`
- Create: `fraud-service/src/main/resources/application.yml`
- Test: `fraud-service/src/test/java/com/showcase/fraud/service/FraudCheckServiceTest.java`
- Test: `fraud-service/src/test/java/com/showcase/fraud/api/FraudCheckControllerTest.java`

**Interfaces:**
- Produces: `GET /fraud-check?accountId={uuid}` — `200` empty body if not blocked; `422 application/problem+json` with `code: ACCOUNT_BLOCKED` if blocked; `400` with `code: VALIDATION_FAILED`/`MALFORMED_REQUEST` for a missing/malformed `accountId`. Task 2's `FraudClient` is this endpoint's only consumer.

- [ ] **Step 1: Scaffold the module**

```xml
<!-- fraud-service/pom.xml -->
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

  <artifactId>fraud-service</artifactId>

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
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
      <version>${springdoc-openapi.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
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

Add the module to the root reactor:

```xml
<!-- pom.xml -- inside <modules> -->
<modules>
  <module>account-service</module>
  <module>transfer-service</module>
  <module>notification-service</module>
  <module>fraud-service</module>
</modules>
```

```java
// fraud-service/src/main/java/com/showcase/fraud/FraudServiceApplication.java
package com.showcase.fraud;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@OpenAPIDefinition(
        info = @Info(
                title = "Fraud Service API",
                version = "v1",
                description = "Stateless account-blocklist screen for the transfer saga."
        )
)
@SpringBootApplication
@ConfigurationPropertiesScan
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
```

```yaml
# fraud-service/src/main/resources/application.yml
server:
  port: 8084

spring:
  application:
    name: fraud-service
  threads:
    virtual:
      enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true

fraud:
  blocklist:
    # Comma-separated account ids, e.g. "11111111-1111-1111-1111-111111111111,22222222-...".
    # Boot's relaxed binding converts a single comma-delimited env var into this List<UUID>.
    account-ids: ${FRAUD_BLOCKLIST_ACCOUNT_IDS:}
```

Run: `./mvnw -pl fraud-service -am validate -B`
Expected: BUILD SUCCESS (no source files yet, just confirms the module is recognized by the reactor).

- [ ] **Step 2: Write the failing tests for the blocklist rule**

```java
// fraud-service/src/test/java/com/showcase/fraud/service/FraudCheckServiceTest.java
package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCheckServiceTest {

    private static final UUID BLOCKED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLEAR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final FraudCheckService service = new FraudCheckService(new FraudBlocklistProperties(List.of(BLOCKED)));

    @Test
    void throwsForABlockedAccount() {
        assertThatThrownBy(() -> service.check(BLOCKED))
                .isInstanceOf(AccountBlockedException.class)
                .hasMessageContaining(BLOCKED.toString());
    }

    @Test
    void passesForAnUnlistedAccount() {
        assertThatCode(() -> service.check(CLEAR)).doesNotThrowAnyException();
    }

    @Test
    void aNullConfiguredListDefaultsToEmptyRatherThanThrowing() {
        FraudCheckService noBlocklist = new FraudCheckService(new FraudBlocklistProperties(null));

        assertThatCode(() -> noBlocklist.check(BLOCKED)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -pl fraud-service test -Dtest=FraudCheckServiceTest`
Expected: FAIL — compilation error, none of `FraudCheckService`/`FraudBlocklistProperties`/`AccountBlockedException` exist yet.

- [ ] **Step 4: Implement the blocklist rule**

```java
// fraud-service/src/main/java/com/showcase/fraud/domain/AccountBlockedException.java
package com.showcase.fraud.domain;

import java.util.UUID;

public class AccountBlockedException extends RuntimeException {

    public AccountBlockedException(UUID accountId) {
        super("Account is blocklisted: " + accountId);
    }
}
```

```java
// fraud-service/src/main/java/com/showcase/fraud/service/FraudBlocklistProperties.java
package com.showcase.fraud.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.UUID;

@ConfigurationProperties(prefix = "fraud.blocklist")
public record FraudBlocklistProperties(List<UUID> accountIds) {

    // Boot's binder leaves this null when the property is unset entirely (no accounts
    // blocklisted) -- default to an empty list so FraudCheckService.check() never NPEs.
    public FraudBlocklistProperties {
        accountIds = (accountIds != null) ? List.copyOf(accountIds) : List.of();
    }
}
```

```java
// fraud-service/src/main/java/com/showcase/fraud/service/FraudCheckService.java
package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FraudCheckService {

    private final FraudBlocklistProperties properties;

    public FraudCheckService(FraudBlocklistProperties properties) {
        this.properties = properties;
    }

    public void check(UUID accountId) {
        if (properties.accountIds().contains(accountId)) {
            throw new AccountBlockedException(accountId);
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl fraud-service test -Dtest=FraudCheckServiceTest`
Expected: PASS

- [ ] **Step 6: Write the failing tests for the HTTP contract**

```java
// fraud-service/src/test/java/com/showcase/fraud/api/FraudCheckControllerTest.java
package com.showcase.fraud.api;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.fraud.service.FraudCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCheckController.class)
class FraudCheckControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCheckService fraudCheckService;

    @Test
    void returns200WhenTheAccountIsClear() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void returns422WithAccountBlockedCodeWhenTheServiceRejects() throws Exception {
        doThrow(new AccountBlockedException(ACCOUNT_ID)).when(fraudCheckService).check(ACCOUNT_ID);

        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void returns400WhenAccountIdIsMissing() throws Exception {
        mockMvc.perform(get("/fraud-check"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returns400WhenAccountIdIsMalformed() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
```

- [ ] **Step 7: Run the tests to verify they fail**

Run: `./mvnw -pl fraud-service test -Dtest=FraudCheckControllerTest`
Expected: FAIL — compilation error, `FraudCheckController`/`ApiExceptionHandler`/`Problems` don't exist yet.

- [ ] **Step 8: Implement the controller and error handling**

```java
// fraud-service/src/main/java/com/showcase/fraud/api/FraudCheckController.java
package com.showcase.fraud.api;

import com.showcase.fraud.service.FraudCheckService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class FraudCheckController {

    private final FraudCheckService fraudCheckService;

    public FraudCheckController(FraudCheckService fraudCheckService) {
        this.fraudCheckService = fraudCheckService;
    }

    @GetMapping("/fraud-check")
    public ResponseEntity<Void> check(@RequestParam UUID accountId) {
        fraudCheckService.check(accountId);
        return ResponseEntity.ok().build();
    }
}
```

```java
// fraud-service/src/main/java/com/showcase/fraud/api/Problems.java
package com.showcase.fraud.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/** Builds RFC 7807 problem responses -- see account-service's Problems.java for the identical pattern. */
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

```java
// fraud-service/src/main/java/com/showcase/fraud/api/ApiExceptionHandler.java
package com.showcase.fraud.api;

import com.showcase.fraud.domain.AccountBlockedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountBlockedException.class)
    public ProblemDetail handleBlocked(AccountBlockedException ex) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_BLOCKED", "Account blocked", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        logger.error("Unhandled exception", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred");
    }

    /** The only required query parameter is accountId -- a missing one always means this. */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed",
                        "Missing required parameter: accountId"));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(Problems.of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                        "Invalid value for parameter: " + ex.getPropertyName()));
    }

    /** Same "every error carries a code" backstop as account-service's ApiExceptionHandler. */
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

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./mvnw -pl fraud-service test -Dtest=FraudCheckServiceTest,FraudCheckControllerTest`
Expected: PASS

- [ ] **Step 10: Run the whole module's test suite**

Run: `./mvnw -pl fraud-service test`
Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git checkout -b feature/phase-5-task-1-fraud-service-module
git add pom.xml fraud-service/
git commit -m "feat(fraud): add Fraud Service module with account-blocklist check (Phase 5, Task 1)"
```

---

### Task 2: `FraudClient` in Transfer Service

**Files:**
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/FraudRejectedException.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/FraudServiceUnavailableException.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/FraudProblem.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/FraudClientProperties.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/FraudClient.java`
- Create: `transfer-service/src/main/java/com/showcase/transfer/client/FraudClientConfig.java`
- Modify: `transfer-service/src/main/resources/application.yml` (add `fraud-service.*` and the `fraudService` resilience4j instance)
- Test: `transfer-service/src/test/java/com/showcase/transfer/client/FraudClientPropertiesTest.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/client/FraudClientTest.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/client/FraudClientFallbackIT.java`

**Interfaces:**
- Consumes: Task 1's `GET /fraud-check?accountId=` contract.
- Produces: `FraudClient.check(UUID accountId)` — `void`; throws `FraudRejectedException(String detail)` (business rejection, never retried) or `FraudServiceUnavailableException(String message[, Throwable cause])` (infra trouble, retried). Task 3 (`TransferService`) and Task 4 (`CompensationScheduler`) both call this.

**Testing scope note:** this task mirrors `AccountClientTest` (request contract) and `AccountClientFallbackIT` (the AOP fallback-passthrough behavior a mocked `FraudClient` in Tasks 3/4 can never exercise — see that class's javadoc for the real bug this caught for `AccountClient`). It does not add a `FraudClientResilienceTest`/`AccountClientResilienceConfigMatchesYamlIT` equivalent (hand-decorated retry/circuit-breaker-state assertions and a config-vs-yaml cross-check): the `fraudService` resilience4j instance reuses `accountService`'s exact tuning (see Step 4 below), so there is no new magic-number derivation to independently re-verify. Named here deliberately, not an oversight — matches this project's convention of naming trimmed scope rather than leaving it implicit.

- [ ] **Step 1: Write the failing tests**

```java
// transfer-service/src/test/java/com/showcase/transfer/client/FraudClientPropertiesTest.java
package com.showcase.transfer.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FraudClientPropertiesTest {

    @Test
    void defaultsTimeoutsWhenNotConfigured() {
        FraudClientProperties properties = new FraudClientProperties("http://fraud-service:8084", null, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void keepsExplicitTimeouts() {
        FraudClientProperties properties = new FraudClientProperties(
                "http://fraud-service:8084", Duration.ofMillis(500), Duration.ofSeconds(1));

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(1));
    }
}
```

```java
// transfer-service/src/test/java/com/showcase/transfer/client/FraudClientTest.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FraudClientTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://fraud-service:8084";

    private MockRestServiceServer server;
    private FraudClient fraudClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        fraudClient = new FraudClient(builder.build(), new ObjectMapper());
    }

    @Test
    void checkSucceedsWhenTheAccountIsClear() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess());

        assertThatCode(() -> fraudClient.check(ACCOUNT_ID)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void checkThrowsRejectedWithDetailWhenTheAccountIsBlocked() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_BLOCKED",
                        "Account is blocklisted: " + ACCOUNT_ID));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudRejectedException.class)
                .satisfies(thrown -> org.assertj.core.api.Assertions.assertThat(((FraudRejectedException) thrown).getDetail())
                        .isEqualTo("Account is blocklisted: " + ACCOUNT_ID));
        server.verify();
    }

    @Test
    void checkThrowsUnavailableOnServerError() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableOn3xxRedirect() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableWhenTheConnectionFails() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withException(new java.net.ConnectException("connection refused")));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableWhenTheErrorBodyIsNotAProblem() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway says no</html>"));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableWhenTheProblemCodeIsNotAccountBlocked() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(problem(HttpStatus.NOT_FOUND, "SOME_OTHER_CODE", "not what we expected"));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    private static ResponseCreator problem(HttpStatus status, String code, String detail) {
        return withStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"type":"https://showcase.example/errors/x","title":"t","status":%d,
                         "detail":"%s","code":"%s","timestamp":"2026-09-16T12:00:00Z"}
                        """.formatted(status.value(), detail, code));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl transfer-service test -Dtest=FraudClientPropertiesTest,FraudClientTest`
Expected: FAIL — compilation error, none of the new classes exist yet.

- [ ] **Step 3: Implement the client**

```java
// transfer-service/src/main/java/com/showcase/transfer/client/FraudRejectedException.java
package com.showcase.transfer.client;

import lombok.Getter;

/** Fraud Service understood the request and blocked the account (4xx). Retrying will not help. */
@Getter
public class FraudRejectedException extends RuntimeException {

    private final String detail;

    public FraudRejectedException(String detail) {
        super("Fraud Service rejected the account: " + detail);
        this.detail = detail;
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/FraudServiceUnavailableException.java
package com.showcase.transfer.client;

/** Fraud Service is broken or unreachable (5xx, timeout, connection failure). */
public class FraudServiceUnavailableException extends RuntimeException {

    public FraudServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public FraudServiceUnavailableException(String message) {
        super(message);
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/FraudProblem.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Minimal binding for Fraud Service's RFC 7807 error body -- see AccountProblem's javadoc. */
@JsonIgnoreProperties(ignoreUnknown = true)
record FraudProblem(String code, String detail) {
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/FraudClientProperties.java
package com.showcase.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "fraud-service")
public record FraudClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public FraudClientProperties {
        connectTimeout = (connectTimeout != null) ? connectTimeout : Duration.ofSeconds(2);
        readTimeout = (readTimeout != null) ? readTimeout : Duration.ofSeconds(5);
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/FraudClient.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls Fraud Service's account-blocklist check over blocking HTTP. Same two-exception
 * shape as {@link AccountClient}: {@link FraudRejectedException} is an ignored exception
 * (never retried, never trips the breaker -- a block is not infrastructure trouble),
 * {@link FraudServiceUnavailableException} is retried. See application.yml's
 * resilience4j.*.instances.fraudService.
 *
 * <p>Same "verified live, not just assumed" caution as {@link AccountClient}'s javadoc:
 * once a fallbackMethod is specified, Resilience4j routes every exception the decorated
 * method throws through it, including ones listed in ignoreExceptions -- see
 * {@code checkFallback} below and {@code FraudClientFallbackIT}.
 */
public class FraudClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public FraudClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "fraudService")
    @Retry(name = "fraudService", fallbackMethod = "checkFallback")
    public void check(UUID accountId) {
        call(() -> restClient.get()
                .uri("/fraud-check?accountId={id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
                .toBodilessEntity());
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            throw new FraudServiceUnavailableException("Fraud Service call failed: " + ex.getMessage(), ex);
        }
    }

    private void rejected(HttpRequest request, ClientHttpResponse response) {
        FraudProblem problem = readProblem(response);
        if (!"ACCOUNT_BLOCKED".equals(problem.code())) {
            // Any 4xx that isn't a definitive block -- an unrecognised code, no code at all,
            // a misconfigured fraud-service.base-url producing a 404, a gateway/proxy
            // interstitial, or Fraud Service's own defensive 400s (VALIDATION_FAILED/
            // MALFORMED_REQUEST) -- is NOT a business verdict. Treating it as one would let
            // a misconfiguration or a future auth hop (Phase 6's Gateway) silently reverse a
            // good transfer or terminate a transfer's recovery on a wrong answer.
            throw new FraudServiceUnavailableException(
                    "Fraud Service returned an unrecognised rejection [" + problem.code() + "]: " + problem.detail());
        }
        throw new FraudRejectedException(problem.detail());
    }

    private void unavailable(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new FraudServiceUnavailableException("Fraud Service returned " + response.getStatusCode().value());
    }

    /**
     * An error body that is not a well-formed problem document (an HTML page from a proxy,
     * an empty body) must still produce a domain exception, never a parse error -- mirrors
     * AccountClient.readProblem() exactly.
     */
    private FraudProblem readProblem(ClientHttpResponse response) {
        try {
            FraudProblem problem = objectMapper.readValue(response.getBody(), FraudProblem.class);
            if (problem == null || problem.code() == null) {
                return new FraudProblem("UNKNOWN", "Fraud Service returned an unrecognised error body");
            }
            return (problem.detail() != null) ? problem : new FraudProblem(problem.code(), "");
        } catch (Exception ex) {
            return new FraudProblem("UNKNOWN", "Fraud Service returned an unreadable error body");
        }
    }

    /** Invoked for every exception check() throws, FraudRejectedException included -- see class javadoc. */
    private void checkFallback(UUID accountId, Throwable t) {
        throw rethrow(t);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof FraudRejectedException rejected) {
            return rejected;
        }
        if (t instanceof FraudServiceUnavailableException already) {
            return already;
        }
        return new FraudServiceUnavailableException("Fraud Service call failed: " + t.getMessage(), t);
    }
}
```

```java
// transfer-service/src/main/java/com/showcase/transfer/client/FraudClientConfig.java
package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class FraudClientConfig {

    @Bean
    public FraudClient fraudClient(RestClient.Builder builder,
                                    FraudClientProperties properties,
                                    ObjectMapper objectMapper) {
        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(properties.connectTimeout())
                                .withReadTimeout(properties.readTimeout())))
                .build();
        return new FraudClient(restClient, objectMapper);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass, then wire `application.yml`**

Run: `./mvnw -pl transfer-service test -Dtest=FraudClientPropertiesTest,FraudClientTest`
Expected: PASS

```yaml
# transfer-service/src/main/resources/application.yml -- add alongside the existing account-service block
fraud-service:
  base-url: ${FRAUD_SERVICE_URL:http://localhost:8084}
  connect-timeout: 2s
  read-timeout: 5s
```

```yaml
# transfer-service/src/main/resources/application.yml -- extend the existing resilience4j block
resilience4j:
  circuitbreaker:
    instances:
      accountService:
        # ... unchanged ...
      fraudService:
        # Same tuning as accountService (see that instance's comment for the x3 retry-attempts
        # multiplier reasoning) -- reused rather than re-derived, since the shape is identical.
        sliding-window-size: 30
        minimum-number-of-calls: 15
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        record-exceptions:
          - com.showcase.transfer.client.FraudServiceUnavailableException
        ignore-exceptions:
          - com.showcase.transfer.client.FraudRejectedException
  retry:
    instances:
      accountService:
        # ... unchanged ...
      fraudService:
        max-attempts: 3
        wait-duration: 200ms
        retry-exceptions:
          - com.showcase.transfer.client.FraudServiceUnavailableException
        ignore-exceptions:
          - com.showcase.transfer.client.FraudRejectedException
```

- [ ] **Step 5: Write and run the fallback-passthrough integration test**

```java
// transfer-service/src/test/java/com/showcase/transfer/client/FraudClientFallbackIT.java
package com.showcase.transfer.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real, Spring-managed {@link FraudClient} bean -- @CircuitBreaker/@Retry active,
 * fallback wired -- unlike FraudClientTest, which constructs FraudClient directly and never
 * exercises the AOP proxy. Same reason as AccountClientFallbackIT: TransferServiceTest and
 * CompensationSchedulerTest mock FraudClient entirely, so neither exercises the fallback
 * passthrough that a real business rejection depends on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FraudClientFallbackIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static HttpServer fakeFraudService;

    @DynamicPropertySource
    static void fraudServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeFraudService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeFraudService.createContext("/fraud-check", exchange -> {
            byte[] body = ("""
                    {"type":"https://showcase.example/errors/account-blocked","title":"Account blocked",
                     "status":422,"detail":"Account is blocklisted: %s","code":"ACCOUNT_BLOCKED",
                     "timestamp":"2026-09-16T12:00:00Z"}
                    """.formatted(ACCOUNT_ID)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(422, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeFraudService.start();
        registry.add("fraud-service.base-url", () -> "http://localhost:" + fakeFraudService.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeFraudService() {
        if (fakeFraudService != null) {
            fakeFraudService.stop(0);
        }
    }

    @Autowired
    private FraudClient fraudClient;

    @Test
    void aDefinitiveRejectionPassesThroughTheRealAopProxyUnchanged() {
        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudRejectedException.class)
                .hasMessageContaining("Account is blocklisted");
    }
}
```

Run: `./mvnw -pl transfer-service test -Dtest=FraudClientFallbackIT`
Expected: PASS (requires Docker running, for the Postgres Testcontainer)

- [ ] **Step 6: Run the whole module's test suite**

Run: `./mvnw -pl transfer-service test`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git checkout -b feature/phase-5-task-2-fraud-client
git add transfer-service/
git commit -m "feat(transfer): add FraudClient with CircuitBreaker+Retry (Phase 5, Task 2)"
```

---

### Task 3: Wire the fraud checks into `TransferService`

**Files:**
- Modify: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferFailureCode.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferStatus.java` (javadoc only)
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java`
- Test: Modify `transfer-service/src/test/java/com/showcase/transfer/service/TransferServiceTest.java`

**Interfaces:**
- Consumes: Task 2's `FraudClient.check(UUID)`.
- Produces: `TransferService`'s constructor becomes `(TransferRepository, AccountClient, FraudClient, TransferSaveService)`. `TransferServiceApplication`'s component scan wires this automatically (constructor injection, no XML) — no application-class change needed.

- [ ] **Step 1: Write the failing tests**

Add a `@Mock private FraudClient fraudClient;` field, pass it into the constructor in `setUp()`, and add a `bothAccountsExistAndFraudClear()` helper (fraud clear is Mockito's default no-op for a void mock, so existing tests need no other change). Add these four new tests to `TransferServiceTest`:

```java
// transfer-service/src/test/java/com/showcase/transfer/service/TransferServiceTest.java -- add these imports
import com.showcase.transfer.client.FraudClient;
import com.showcase.transfer.client.FraudRejectedException;
import com.showcase.transfer.client.FraudServiceUnavailableException;

// -- add this field alongside the existing @Mock fields
@Mock
private FraudClient fraudClient;

// -- change setUp() to:
@BeforeEach
void setUp() {
    transferService = new TransferService(transferRepository, accountClient, fraudClient, transferSaveService);
}

// -- add these four tests
@Test
void failsWithoutDebitingWhenTheSourceAccountIsBlocked() {
    repositoryEchoesSaves();
    bothAccountsExist();
    org.mockito.Mockito.doThrow(new FraudRejectedException("Account is blocklisted: " + FROM))
            .when(fraudClient).check(FROM);

    Transfer result = transferService.execute(FROM, TO, AMOUNT);

    assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
    assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.SOURCE_ACCOUNT_BLOCKED);
    verify(accountClient, never()).debit(any(), any(), any());
    verify(fraudClient, never()).check(TO);
}

@Test
void failsWhenFraudServiceIsUnreachableCheckingTheSource() {
    repositoryEchoesSaves();
    bothAccountsExist();
    org.mockito.Mockito.doThrow(new FraudServiceUnavailableException("read timed out"))
            .when(fraudClient).check(FROM);

    Transfer result = transferService.execute(FROM, TO, AMOUNT);

    assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
    assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.SOURCE_FRAUD_SERVICE_UNAVAILABLE);
    assertThat(result.getFailureReason()).contains("read timed out");
    verify(accountClient, never()).debit(any(), any(), any());
}

@Test
void requiresCompensationWhenTheDestinationAccountIsBlocked() {
    repositoryEchoesSaves();
    bothAccountsExist();
    org.mockito.Mockito.doThrow(new FraudRejectedException("Account is blocklisted: " + TO))
            .when(fraudClient).check(TO);

    Transfer result = transferService.execute(FROM, TO, AMOUNT);

    assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
    assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED);
    verify(accountClient).debit(eq(FROM), eq(AMOUNT), any());
    verify(accountClient, never()).credit(any(), any(), any());
}

@Test
void requiresCompensationWhenFraudServiceIsUnreachableCheckingTheDestination() {
    repositoryEchoesSaves();
    bothAccountsExist();
    org.mockito.Mockito.doThrow(new FraudServiceUnavailableException("read timed out"))
            .when(fraudClient).check(TO);

    Transfer result = transferService.execute(FROM, TO, AMOUNT);

    assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
    assertThat(result.getFailureCode()).isEqualTo(TransferFailureCode.DESTINATION_FRAUD_SERVICE_UNAVAILABLE);
    assertThat(result.getFailureReason()).contains("read timed out");
    verify(accountClient, never()).credit(any(), any(), any());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl transfer-service test -Dtest=TransferServiceTest`
Expected: FAIL — compilation error, `TransferService`'s constructor doesn't accept a `FraudClient` yet, and the four new `TransferFailureCode` values don't exist.

- [ ] **Step 3: Add the new failure codes**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferFailureCode.java
public enum TransferFailureCode {
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    CONCURRENT_MODIFICATION,
    ACCOUNT_SERVICE_UNAVAILABLE,
    SOURCE_ACCOUNT_BLOCKED,
    SOURCE_FRAUD_SERVICE_UNAVAILABLE,
    DESTINATION_ACCOUNT_BLOCKED,
    DESTINATION_FRAUD_SERVICE_UNAVAILABLE,
    UNEXPECTED_ERROR;

    // fromAccountCode(...) is unchanged -- the four new values above are never produced from
    // an Account Service response, only assigned directly by TransferService/CompensationScheduler.
    // ... rest of the class unchanged ...
}
```

- [ ] **Step 4: Update `TransferStatus`'s javadoc**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/TransferStatus.java
    /** Rejected before or during the debit, or the source account was blocklisted. No money moved. */
    FAILED,
    /**
     * Source was debited but the destination credit did not succeed or was never attempted
     * (e.g. the destination account was blocklisted), so funds are stranded at the source.
     * CompensationScheduler drains this state by reconciling against Account Service and
     * Fraud Service: it resolves to COMPLETED (the credit had already landed), COMPENSATED
     * (the source was credited back), or COMPENSATION_FAILED (manual review).
     */
    COMPENSATION_REQUIRED,
```

- [ ] **Step 5: Wire the two new saga steps into `TransferService.execute()`**

Replace `execute()`'s body with:

> **Superseded:** the debit leg's `AccountServiceUnavailableException` branch below records `FAILED`. It now leaves the transfer `PENDING` and throws `DebitOutcomeUnknownException`, so the stale-`PENDING` sweep reconciles it. See §4 of `microservices-showcase-design.md` and the current `TransferService` before copying this block.

```java
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
            // here and the debit, which is why step 3 handles every rejection on its own.
            try {
                accountClient.getAccount(fromAccountId);
                accountClient.getAccount(toAccountId);
            } catch (AccountRejectedException ex) {
                return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return fail(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 2: screen the source account before any money moves. A block or an
            // unreachable Fraud Service both fail clean here -- nothing to compensate, same
            // shape as every other pre-debit rejection. See docs/phase-5-fraud-service.md.
            try {
                fraudClient.check(fromAccountId);
            } catch (FraudRejectedException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 3: debit the source.
            try {
                accountClient.debit(fromAccountId, amount, transfer.getId() + ":debit");
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
                // credits the source back on the strength of it. Feeding an ambiguous outcome
                // into it would make the compensator invent money whenever the debit never
                // actually landed -- strictly worse than under-reporting.
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

            // Step 4: screen the destination account. Money has already moved, so a
            // block or an unreachable Fraud Service both strand the transfer for
            // CompensationScheduler rather than failing clean.
            try {
                fraudClient.check(toAccountId);
            } catch (FraudRejectedException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 5: credit the destination. Past this point the source is already debited,
            // so business rejection and infrastructure failure have identical consequences:
            // funds are stranded and something has to put them back. CompensationScheduler
            // resolves that.
            try {
                accountClient.credit(toAccountId, amount, transfer.getId() + ":credit");
            } catch (AccountRejectedException ex) {
                return strand(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            transfer.markCompleted();
            log.info("Transfer {} completed", transfer.getId());
            return transferSaveService.save(transfer);
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
```

And the constructor/fields:

```java
    private final TransferRepository transferRepository;
    private final AccountClient accountClient;
    private final FraudClient fraudClient;
    private final TransferSaveService transferSaveService;

    public TransferService(TransferRepository transferRepository, AccountClient accountClient,
                            FraudClient fraudClient, TransferSaveService transferSaveService) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.fraudClient = fraudClient;
        this.transferSaveService = transferSaveService;
    }
```

Add the matching imports (`com.showcase.transfer.client.FraudClient`, `FraudRejectedException`, `FraudServiceUnavailableException`).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -pl transfer-service test -Dtest=TransferServiceTest`
Expected: PASS — all previous tests plus the four new ones.

- [ ] **Step 7: Run the whole module's test suite**

Run: `./mvnw -pl transfer-service test`
Expected: BUILD SUCCESS (this also confirms `TransferControllerTest` and any other caller of `TransferService`'s constructor were updated — if `TransferServiceApplication` or any other production code constructs `TransferService` directly rather than via Spring injection, update that call site too).

- [ ] **Step 8: Commit**

```bash
git checkout -b feature/phase-5-task-3-transfer-service-saga
git add transfer-service/
git commit -m "feat(transfer): screen source and destination accounts in the transfer saga (Phase 5, Task 3)"
```

---

### Task 4: Fraud gates in `CompensationScheduler`

**Files:**
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java`
- Test: Modify `transfer-service/src/test/java/com/showcase/transfer/service/CompensationSchedulerTest.java`

**Interfaces:**
- Consumes: Task 2's `FraudClient.check(UUID)`.

- [ ] **Step 1: Write the failing tests**

Add a `@Mock private FraudClient fraudClient;` field and pass it into the constructor in `setUp()`:

```java
// transfer-service/src/test/java/com/showcase/transfer/service/CompensationSchedulerTest.java -- add import
import com.showcase.transfer.client.FraudClient;
import com.showcase.transfer.client.FraudRejectedException;
import com.showcase.transfer.client.FraudServiceUnavailableException;

// -- add this field
@Mock
private FraudClient fraudClient;

// -- change setUp()'s scheduler construction to:
scheduler = new CompensationScheduler(transferRepository, accountClient, fraudClient,
        new CompensationProperties(Duration.ofSeconds(15), Duration.ofSeconds(120), 500), transferSaveService);
```

Add these four new tests:

```java
@Test
void destinationBlockedCompensatesWithoutEverAttemptingCredit() {
    // Stranded for an ordinary UNEXPECTED_ERROR reason (not a fraud-tagged one) -- proves the
    // destination fraud gate runs unconditionally, not only for DESTINATION_*-tagged rows.
    Transfer transfer = strandedTransfer();
    when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
    when(transferRepository.save(transfer)).thenReturn(transfer);
    doThrow(new FraudRejectedException("Account is blocklisted: " + TO)).when(fraudClient).check(TO);

    scheduler.drainCompensationRequired();

    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
    verify(accountClient, never()).credit(eq(TO), any(), any());
    verify(accountClient).credit(FROM, AMOUNT, TRANSFER_ID + ":compensate");
}

@Test
void destinationFraudStillUnavailableLeavesTheTransferAwaitingTheNextSweep() {
    Transfer transfer = strandedTransfer();
    when(transferRepository.findByStatus(eq(TransferStatus.COMPENSATION_REQUIRED), any())).thenReturn(List.of(transfer));
    doThrow(new FraudServiceUnavailableException("read timed out")).when(fraudClient).check(TO);

    scheduler.drainCompensationRequired();

    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPENSATION_REQUIRED);
    verify(accountClient, never()).credit(any(), any(), any());
    verify(transferRepository, never()).save(any());
}

@Test
void staleSourceBlockedMarksTheTransferFailedWithoutDebiting() {
    Transfer transfer = stalePendingTransfer();
    when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any(), any()))
            .thenReturn(List.of(transfer));
    when(transferRepository.save(transfer)).thenReturn(transfer);
    doThrow(new FraudRejectedException("Account is blocklisted: " + FROM)).when(fraudClient).check(FROM);

    scheduler.sweepStalePending();

    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
    assertThat(transfer.getFailureCode()).isEqualTo(TransferFailureCode.SOURCE_ACCOUNT_BLOCKED);
    verify(accountClient, never()).debit(any(), any(), any());
}

@Test
void staleSourceFraudStillUnavailableStaysPending() {
    Transfer transfer = stalePendingTransfer();
    when(transferRepository.findByStatusAndCreatedAtBefore(eq(TransferStatus.PENDING), any(), any()))
            .thenReturn(List.of(transfer));
    doThrow(new FraudServiceUnavailableException("read timed out")).when(fraudClient).check(FROM);

    scheduler.sweepStalePending();

    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
    verify(accountClient, never()).debit(any(), any(), any());
    verify(transferRepository, never()).save(any());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl transfer-service test -Dtest=CompensationSchedulerTest`
Expected: FAIL — compilation error, `CompensationScheduler`'s constructor doesn't accept a `FraudClient` yet.

- [ ] **Step 3: Add the fraud gates**

```java
// transfer-service/src/main/java/com/showcase/transfer/service/CompensationScheduler.java -- add imports
import com.showcase.transfer.client.FraudClient;
import com.showcase.transfer.client.FraudRejectedException;
import com.showcase.transfer.client.FraudServiceUnavailableException;

// -- add the field and update the constructor
    private final TransferRepository transferRepository;
    private final AccountClient accountClient;
    private final FraudClient fraudClient;
    private final CompensationProperties properties;
    private final TransferSaveService transferSaveService;

    public CompensationScheduler(TransferRepository transferRepository, AccountClient accountClient,
                                  FraudClient fraudClient, CompensationProperties properties,
                                  TransferSaveService transferSaveService) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.fraudClient = fraudClient;
        this.properties = properties;
        this.transferSaveService = transferSaveService;
    }
```

Replace `reconcileCredit()` with a version that gates on the destination fraud check first, unconditionally, before ever attempting `credit()`:

```java
    private void reconcileCredit(Transfer transfer) {
        try {
            fraudClient.check(transfer.getToAccountId());
        } catch (FraudRejectedException blocked) {
            compensateSource(transfer, "Destination account blocklisted: " + blocked.getDetail());
            return;
        } catch (FraudServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still cannot reconcile the destination fraud check, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
            return;
        }

        try {
            accountClient.credit(transfer.getToAccountId(), transfer.getAmount(), transfer.getId() + ":credit");
            transfer.markCompleted();
            transferSaveService.save(transfer);
            log.info("Transfer {} reconciled as COMPLETED: the credit had already landed", transfer.getId());
        } catch (AccountRejectedException definitivelyRejected) {
            compensateSource(transfer, definitivelyRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still cannot reconcile the credit leg, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
        }
    }
```

Replace `reconcileDebit()` with a version that gates on the source fraud check first:

```java
    private void reconcileDebit(Transfer transfer) {
        try {
            fraudClient.check(transfer.getFromAccountId());
        } catch (FraudRejectedException blocked) {
            transfer.markFailed(TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, blocked.getDetail());
            transferSaveService.save(transfer);
            log.info("Transfer {} recovered from stale PENDING as FAILED: source account blocklisted [{}]",
                    transfer.getId(), blocked.getDetail());
            return;
        } catch (FraudServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still stale PENDING, source fraud check still unavailable, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
            return;
        }

        try {
            accountClient.debit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getId() + ":debit");
            transfer.markCompensationRequired(TransferFailureCode.UNEXPECTED_ERROR,
                    "Recovered from a stale PENDING row: the debit leg is confirmed landed, the credit leg is unresolved");
            transferSaveService.save(transfer);
            log.info("Transfer {} promoted from stale PENDING to COMPENSATION_REQUIRED: debit confirmed landed",
                    transfer.getId());
        } catch (AccountRejectedException definitivelyRejected) {
            transfer.markFailed(TransferFailureCode.fromAccountCode(definitivelyRejected.getCode()),
                    definitivelyRejected.getDetail());
            transferSaveService.save(transfer);
            log.info("Transfer {} recovered from stale PENDING as FAILED: debit never landed [{}]",
                    transfer.getId(), definitivelyRejected.getDetail());
        } catch (AccountServiceUnavailableException stillUnavailable) {
            log.error("Transfer {} still stale PENDING, debit leg still unresolved, will retry next sweep: {}",
                    transfer.getId(), stillUnavailable.getMessage());
        }
    }
```

`compensateSource()`, `drainCompensationRequired()`, and `sweepStalePending()` are unchanged.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl transfer-service test -Dtest=CompensationSchedulerTest`
Expected: PASS — all previous tests plus the four new ones.

- [ ] **Step 5: Run the whole module's test suite**

Run: `./mvnw -pl transfer-service test`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git checkout -b feature/phase-5-task-4-compensation-scheduler-fraud-gates
git add transfer-service/
git commit -m "feat(transfer): gate stale-PENDING and COMPENSATION_REQUIRED recovery on fraud checks (Phase 5, Task 4)"
```

---

### Task 5: Docker Compose, Dockerfiles, and documentation sync

**Files:**
- Create: `fraud-service/Dockerfile`
- Modify: `account-service/Dockerfile`, `transfer-service/Dockerfile`, `notification-service/Dockerfile` (each needs the new module's `pom.xml` copied into the reactor build stage)
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/microservices-showcase-design.md`

**Interfaces:** none — this task wires and documents Tasks 1–4's already-tested code; no new production interfaces.

- [ ] **Step 1: `fraud-service/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY account-service/pom.xml account-service/pom.xml
COPY transfer-service/pom.xml transfer-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY fraud-service/pom.xml fraud-service/pom.xml
RUN chmod +x mvnw && ./mvnw -pl fraud-service -am dependency:go-offline -B
COPY fraud-service/src fraud-service/src
RUN ./mvnw -pl fraud-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/fraud-service/target/fraud-service-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Add the new module's `pom.xml` to the other three Dockerfiles**

In `account-service/Dockerfile`, `transfer-service/Dockerfile`, and `notification-service/Dockerfile`, add this line immediately after the existing `COPY notification-service/pom.xml notification-service/pom.xml` line:

```dockerfile
COPY fraud-service/pom.xml fraud-service/pom.xml
```

(Every Dockerfile copies every module's `pom.xml` so Maven's `-am` reactor build can resolve the full dependency graph — see the existing three files for the established pattern.)

- [ ] **Step 3: Add `fraud-service` to `docker-compose.yml`**

Add this service block (after `notification-service` is fine):

```yaml
  fraud-service:
    build:
      context: .
      dockerfile: fraud-service/Dockerfile
    container_name: showcase-fraud-service
    environment:
      FRAUD_BLOCKLIST_ACCOUNT_IDS: ${FRAUD_BLOCKLIST_ACCOUNT_IDS:-}
    ports:
      - "8084:8084"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8084/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 40s
```

And extend `transfer-service`'s block: add `FRAUD_SERVICE_URL: http://fraud-service:8084` to its `environment`, and add this to its `depends_on`:

```yaml
      fraud-service:
        condition: service_healthy
```

- [ ] **Step 4: Update `README.md`**

- In the "Running locally" section, change `"This starts Postgres, Account Service (8081), Transfer Service (8082), Kafka, and Notification Service (8083)."` to `"This starts Postgres, Account Service (8081), Transfer Service (8082), Fraud Service (8084), Kafka, and Notification Service (8083)."`
- In "Try it (Swagger UI)", add: `- Fraud Service — http://localhost:8084/swagger-ui.html`
- Replace the "All saga outcomes" section (added earlier this session) with the updated 14-row table and background-resolution bullets from this document's own "Complete Saga Outcome Table" section above (copy verbatim — this doc is the source of truth this task syncs the README against).
- In the `# Insufficient funds -> ...` comment block under "Transfers", add two new lines:
  ```
  # Source account blocklisted        -> 422 SOURCE_ACCOUNT_BLOCKED, no money moves
  # Destination account blocklisted   -> transfer recorded COMPENSATION_REQUIRED, source
  #                                       is automatically credited back within
  #                                       transfer.compensation.sweep-interval
  ```
- After the existing transfer curl example, add a demo block:
  ```
  # Trigger a blocked-source rejection (clean failure, no money moves): set
  # FRAUD_BLOCKLIST_ACCOUNT_IDS in .env to include an account id, restart fraud-service,
  # then transfer FROM that account.
  #
  # Trigger a blocked-destination compensation (money moves, then reverses automatically):
  # transfer TO a blocklisted account instead.
  curl "http://localhost:8082/transfers?status=FAILED"
  ```

- [ ] **Step 5: Update `docs/roadmap.md`**

Change row 5's status from `Not started` to `✅ Done`, and its scope cell to: `Fraud Service (stateless account-blocklist screen, no DB); FraudClient wired into the saga as two sync calls (source before debit, destination before credit); CompensationScheduler's stale-PENDING and COMPENSATION_REQUIRED sweeps both gate on their matching fraud check before acting`.

Add a note under the Phase-1 Flyway deferral item: `Phase 5 added zero schema surface (no new column, no new table) -- the revisit trigger this item names was not tripped by this phase.`

Remove the Phase-4-carried-over bullet about the stale `TransferCompleted`/`TransferFailed` event-pair naming and "§8 says topic names are undecided" — already fixed (see Step 6's note below), so the bullet is now itself stale.

- [ ] **Step 6: Update `docs/microservices-showcase-design.md` §4**

Replace:
```
3. **Sync call** → Fraud Service: risk-check the transfer. Same resilience wrapping.
4. Fraud passes → **sync call** → Account Service: credit the destination account.
```
with:
```
2b. **Sync call** → Fraud Service: screen the source account (before the debit — see docs/phase-5-fraud-service.md's Design Decisions for why the check runs twice, not once, between debit and credit). Same resilience wrapping. A block fails the transfer clean, no money moved.
3. **Sync call** → Account Service: debit the source account (optimistic locking on balance; rejects on insufficient funds).
3b. **Sync call** → Fraud Service: screen the destination account (before the credit). A block strands the transfer for compensation — the deliberate trigger for the compensation path below.
4. Destination clears → **sync call** → Account Service: credit the destination account.
```
(Renumber the remaining happy-path steps 5–7 accordingly, and fix the "reversing step 2" cross-reference in the failure section below to point at the correct step number for the debit.)

Replace:
```
- **Fraud rejects the transfer:** compensate by calling Account Service to credit the source account back (reversing step 2). Mark `Transfer` `FAILED`. Emit `TransferFailed` via the same outbox mechanism (for observability/notification).
```
with:
```
- **Source account blocked (pre-debit fraud screen):** transfer is marked `FAILED`. Nothing moved, nothing to compensate.
- **Destination account blocked (pre-credit fraud screen):** the source has already been debited, so the transfer is marked `COMPENSATION_REQUIRED` and CompensationScheduler credits the source back automatically, marking the transfer `COMPENSATED`. Emit `TransferFailed` via the same outbox mechanism either way (for observability/notification).
```

The stale `TransferCompleted`/`TransferFailed` event-pair naming docs/roadmap.md's deferred-items list carried over from Phase 4's review turns out to already be fixed as of this reading — §4 already has a "**Settled in Phase 4**" callout box (right after the happy-path numbered list) giving the real `transfer.completed`/`transfer.failed` topic names and payload shape, and a grep for "undecided" across the file finds nothing. Remove that now-stale carried-over bullet from `docs/roadmap.md`'s deferred-items list in Step 5 above instead of re-doing work that's already done — leaving it in would mislead the next reader into re-investigating a non-issue.

- [ ] **Step 7: Manual verification**

```bash
docker compose down -v
docker compose up --build
```

Wait for all containers healthy, then:

```bash
# Create two accounts, capture their ids as FROM/TO
curl -X POST http://localhost:8081/accounts -H "Content-Type: application/json" \
  -d '{"ownerName": "Ada", "initialBalance": 100.00}'
curl -X POST http://localhost:8081/accounts -H "Content-Type: application/json" \
  -d '{"ownerName": "Bob", "initialBalance": 100.00}'

# Happy path -- expect COMPLETED
curl -X POST http://localhost:8082/transfers -H "Content-Type: application/json" \
  -d '{"fromAccountId": "<FROM>", "toAccountId": "<TO>", "amount": 10.00}'
```

Then set `FRAUD_BLOCKLIST_ACCOUNT_IDS=<TO>` in `.env`, `docker compose up -d --build fraud-service`, and repeat the transfer — expect `COMPENSATION_REQUIRED` immediately, then `COMPENSATED` within `transfer.compensation.sweep-interval` (15s default):

```bash
curl http://localhost:8082/transfers/<id>
sleep 20
curl http://localhost:8082/transfers/<id>
```

Expected: status transitions `COMPENSATION_REQUIRED` → `COMPENSATED`, and Account balances confirm the source was credited back (`curl http://localhost:8081/accounts/<FROM>`).

- [ ] **Step 8: Commit**

```bash
git checkout -b feature/phase-5-task-5-compose-and-docs
git add fraud-service/Dockerfile account-service/Dockerfile transfer-service/Dockerfile notification-service/Dockerfile \
        docker-compose.yml README.md docs/roadmap.md docs/microservices-showcase-design.md
git commit -m "docs+infra: wire Fraud Service into Docker Compose and sync docs (Phase 5, Task 5)"
```

