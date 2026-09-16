# API Gateway Implementation Phase (Phase 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the system a single client-facing entry point. A new `gateway-service` module routes external traffic to Transfer Service (its full API — already the sole client-facing service per the design doc) and to Account Service's client-safe paths only (create/list/get an account). Account's `debit`/`credit` endpoints, and all of Fraud and Notification, get no route at all — they stay reachable only on the Docker-internal network. This phase is routing/topology only: no JWT validation, no Keycloak, no Spring Security anywhere. Auth is a deliberate, separate follow-up (Phase 7 — see Scope Boundary).

**Architecture:** A new stateless `gateway-service` module built on Spring Cloud Gateway **Server MVC** — the blocking, Servlet-based flavor of Spring Cloud Gateway (as opposed to the original WebFlux/Reactor-based one), matching the project's stated "Spring MVC, not WebFlux" stack choice and its virtual-threads convention. Routes are declared as `RouterFunction<ServerResponse>` beans (or equivalent) with explicit path predicates — only the paths a client should reach get a route; there is no blocklist filter to bypass or misconfigure, because the forbidden paths simply have no route.

**Tech Stack:** Spring Boot 3.3.8 / Java 21 (same as every other service — Boot bumped project-wide from 3.3.4 during implementation; see Design Decisions), `spring-cloud-starter-gateway-mvc`, Spring Cloud BOM `2023.0.5` (the 2023.0 release train adds explicit Spring Boot 3.3.x support; `2023.0.5` — released 2025-01-10 — is the pinned patch, verified present on Maven Central during this brainstorm). No database, no Kafka, no Spring Security. springdoc-openapi is not added to this module — a gateway has no business logic of its own to document; its Swagger surface is whatever Transfer/Account already publish.

**Spec:** This document (brainstormed with the user on 2026-09-16) and [docs/microservices-showcase-design.md](microservices-showcase-design.md) §2–§4, §7 (component table, tech stack, deployment — describes the Gateway generically and bundles it with Auth; this phase's Scope Boundary supersedes that bundling by splitting Auth into its own Phase 7).

## Global Constraints

- Java 21 floor; Spring Boot 3.3.8 (bumped from 3.3.4 during this phase — see Design Decisions). Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads enabled — `gateway-service` follows the same `spring.threads.virtual.enabled: true` convention as the other four services. Spring Cloud Gateway Server MVC (not the classic reactive Gateway) is a hard requirement of this phase, not a preference — see Design Decisions.
- No Spring Security, no JWT validation, no Keycloak wiring in this phase. Do not add auth machinery "while we're in here" — it lands in Phase 7 as its own reviewed unit.
- No database, no Kafka — `gateway-service` is stateless, same as Fraud/Notification.
- No k8s/service mesh; local deployment is Docker Compose only, single instance per service.
- Never commit directly to `master`; work happens on `feature/phase-6-task-<N>-*` branches, one PR per task, stop after each. Subagent review is manual, on request — not automatic. (CLAUDE.md)
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review. Always name the model explicitly. (CLAUDE.md)

## Design Decisions Worth Knowing Before You Start

**Why Gateway Server MVC, not classic Spring Cloud Gateway.** The classic Gateway is WebFlux/Project-Reactor-based — it would be the first reactive dependency introduced anywhere in an otherwise fully-blocking, virtual-threads codebase (design doc §3 explicitly rules this out: "Spring MVC (blocking, not WebFlux)"). Gateway Server MVC (added in Spring Cloud 2023.0, built on Spring MVC) gives the same routing capability without that inconsistency. This was a deliberate call, not a default — worth keeping in mind if a future package/import instinctively reaches for `org.springframework.cloud.gateway.route` (classic) instead of `org.springframework.cloud.gateway.server.mvc` (this phase's target).

**Why routing is allow-list by path, not a blocklist filter.** Account Service's `debit`/`credit` endpoints must never be reachable from outside the Docker network, even before Phase 7 adds real enforcement. A `GatewayFilter` that rejects those two paths would work, but it's a second place the rule can go stale (add a new sensitive Account endpoint later, forget the filter). Declaring only the three client-safe route predicates (`POST /accounts`, `GET /accounts`, `GET /accounts/{id}`) means an unrouted path 404s by construction — there's nothing to remember to update when Account's API grows, because new Account endpoints are unreachable through the Gateway by default until a route is deliberately added for them.

**Why Fraud and Notification get no routes.** Neither has ever had a client-facing use case (design doc: Fraud and Notification are both internal, `stateless`, consumed only by Transfer Service / Kafka respectively). Adding routes for them would be speculative — nothing in this phase or the roadmap calls for external access to either.

**Why response bodies pass through unchanged.** Transfer and Account already return RFC 7807 `application/problem+json` with a stable `code` property. The Gateway does no response rewriting, so that contract reaches the client exactly as the origin service produced it — a client written against Transfer/Account's existing error contract doesn't need to know a Gateway is in front of it at all. A request to a path with no matching route predicate gets a plain 404 from the Gateway itself (not problem+json) — there's no upstream service to describe an error on behalf of; the path simply doesn't exist at this boundary, same as any other unmapped Spring MVC route.

**Why Boot bumped to 3.3.8, and why `spring-cloud-starter-gateway-mvc` not `-server-webmvc`.** Both were found during implementation, not brainstorming, and both were verified against the actually-resolved jars (`javap` + sources), not assumed from docs — current Spring Cloud Gateway docs describe the *latest* API (renamed starter, `http()` + `BeforeFilterFunctions.uri()`), which is only accurate from gateway 4.3.x (Spring Cloud 2024.0.x+) onward, not the 4.1.x line this phase's 2023.0.5 BOM actually resolves. Two consequences:
- The correct starter for this release train is `spring-cloud-starter-gateway-mvc` (no `-server-webmvc` suffix, no `-server-mvc` middle) — that name is a 4.3.x-era rename that doesn't exist as a resolvable artifact for 4.1.x.
- `spring-cloud-gateway-server-mvc:4.1.6` calls `HttpHeaders.headerSet()` (added in Spring Framework 6.1.15) from its internal proxy handler, but Boot 3.3.4 ships Spring Framework 6.1.13 — a `NoSuchMethodError` on every proxied request, caught by `GatewayRoutingIT` (Task 2), not by `GatewayRoutesConfigTest`'s route-matching test (which never invokes a handler, so it can't see this class of bug). User-approved fix: bump the root `pom.xml`'s Boot parent to `3.3.8` (latest 3.3.x patch) project-wide, rather than pin Framework jars separately or drop Gateway Server MVC. `GatewayRoutesConfig` itself uses the 2-arg `GatewayRouterFunctions.route(RequestPredicate, HandlerFunction)` plus `HandlerFunctions.http(String)` — 4.1.x's equivalent of the newer `route(routeId).route(predicate, http()).before(uri(baseUrl)).build()` shape, confirmed by reading `HandlerFunctions`'s source: both forms set the same `MvcUtils.GATEWAY_REQUEST_URL_ATTR` and delegate to the same `ProxyExchangeHandlerFunction`.

## Gateway Service

New Spring Boot module `gateway-service/` (port 8080, package `com.showcase.gateway`), stateless — no database, no Kafka, no Spring Security.

Routes:

| Client path | Method(s) | Upstream | Notes |
|---|---|---|---|
| `/transfers/**` | all | `http://transfer-service:8082` | Full passthrough — Transfer's entire API is already client-facing. |
| `/accounts` | `POST`, `GET` | `http://account-service:8081` | Create + list. |
| `/accounts/{id}` | `GET` | `http://account-service:8081` | Get by id. |
| everything else (incl. `/accounts/{id}/debit`, `/accounts/{id}/credit`, any Fraud/Notification path) | — | — | No route exists; Gateway returns `404`. |

- Actuator health endpoint (`/actuator/health`), Docker Compose healthcheck, Dockerfile matching the other four services' two-stage build.
- `application.yml` holds the two upstream base URLs (`account-service`/`transfer-service` hostnames, resolved via Docker Compose's internal DNS) as plain config properties — no service discovery/registry in scope, consistent with how `AccountClient`/`FraudClient` in Transfer Service already hardcode their upstream URLs via `application.yml` rather than a registry.

## Docker Compose Wiring

- New `gateway-service` entry: build context `gateway-service/`, port mapping `"8080:8080"`, `depends_on: { transfer-service: { condition: service_healthy }, account-service: { condition: service_healthy } }`, healthcheck via `curl -f http://localhost:8080/actuator/health`, matching the existing four services' healthcheck shape.
- Root `pom.xml`: add `<module>gateway-service</module>`.

## Testing

- **Route-predicate unit tests:** MockMvc (or the Gateway Server MVC test-support equivalent) against the router config directly, no real backend — assert each allow-listed path resolves to the right upstream, and that `/accounts/{id}/debit`, `/accounts/{id}/credit`, and an arbitrary Fraud/Notification-shaped path all 404.
- **Integration test:** Testcontainers-based, Gateway routing against WireMock stubs standing in for Transfer/Account (or the real service JARs if that proves simpler in practice — implementer's call, not a design commitment) — proves a request actually proxies through and a response status/body survives the round-trip unchanged, including a `problem+json` error body.
- **End-to-end:** none new in this phase. The Phase 7-planned E2E suite (design doc §6) already assumes requests arrive "via the Gateway" — this phase is what makes that assumption true for the first time, not something it adds new E2E coverage for itself.

## Scope Boundary

**In scope:** `gateway-service` module; the three allow-listed routes; Docker Compose wiring for the new service; roadmap.md renumbering and status/scope rows (see below).

**Explicitly out of scope** — named follow-ups, not oversights:

| Deferred | Why not now | Lands in |
|---|---|---|
| JWT validation, Keycloak, Spring Security Resource Server | Deliberately split out — routing and auth are separable concerns with no reason to couple them into one phase/PR | Phase 7 (new) |
| Rate limiting / CORS / request-logging filters | Not asked for, no concrete need yet | Not currently planned — revisit if a need emerges |
| Account read-route consumers (an actual client using `GET /accounts`) | Out of scope — this phase only proves the Gateway *can* reach it | N/A, already usable once this phase lands |
| Trace propagation across the new Gateway hop | No OTel Collector yet | Phase 8 (renumbered Observability) |
| Gateway-level resilience (CircuitBreaker/Retry/TimeLimiter in front of Transfer/Account) | Not asked for; Transfer already wraps its own downstream calls, and the Gateway adding a second resilience layer in front of a resilience-wrapped service is a design question of its own, not a default | Not currently planned — revisit only if a concrete failure mode motivates it |

## Roadmap Changes

- `docs/roadmap.md` Phase 6 row: scope narrows to "API Gateway" (routing only, no auth).
- New Phase 7 row inserted: "Auth (Keycloak/JWT)" — Keycloak pre-configured realm, JWT validation at the Gateway and via Spring Security Resource Server in each service.
- Current Phase 7 ("Observability + Full Compose Integration") renumbers to Phase 8. No scope change to that row beyond the number.

---

## Tasks

### Task 1: Gateway Service module — routing

**Files:**
- Modify: `pom.xml:19-24` (add `<module>gateway-service</module>`)
- Create: `gateway-service/pom.xml`
- Create: `gateway-service/src/main/resources/application.yml`
- Create: `gateway-service/src/main/java/com/showcase/gateway/GatewayServiceApplication.java`
- Create: `gateway-service/src/main/java/com/showcase/gateway/config/AccountServiceProperties.java`
- Create: `gateway-service/src/main/java/com/showcase/gateway/config/TransferServiceProperties.java`
- Create: `gateway-service/src/main/java/com/showcase/gateway/config/GatewayRoutesConfig.java`
- Test: `gateway-service/src/test/java/com/showcase/gateway/config/GatewayRoutesConfigTest.java`

**Interfaces:**
- Produces: `AccountServiceProperties(String baseUrl)` bound to config prefix `account-service`; `TransferServiceProperties(String baseUrl)` bound to config prefix `transfer-service`; `GatewayRoutesConfig` with two `@Bean` methods, `transferRoutes(TransferServiceProperties)` and `accountRoutes(AccountServiceProperties)`, each returning `RouterFunction<ServerResponse>`. Task 2 relies on the config keys `account-service.base-url` / `transfer-service.base-url` being overridable via `@DynamicPropertySource`, and on the resulting Gateway proxying `/transfers/**` and the three allow-listed `/accounts` paths exactly as this task wires them.

- [ ] **Step 1: Add the module to the root `pom.xml`**

In `pom.xml`, change:
```xml
  <modules>
    <module>account-service</module>
    <module>transfer-service</module>
    <module>notification-service</module>
    <module>fraud-service</module>
  </modules>
```
to:
```xml
  <modules>
    <module>account-service</module>
    <module>transfer-service</module>
    <module>notification-service</module>
    <module>fraud-service</module>
    <module>gateway-service</module>
  </modules>
```

- [ ] **Step 2: Create `gateway-service/pom.xml`**

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

  <artifactId>gateway-service</artifactId>

  <properties>
    <spring-cloud.version>2023.0.5</spring-cloud.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-gateway-mvc</artifactId>
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

`spring-cloud-dependencies` is imported only inside `gateway-service/pom.xml`, not the root `pom.xml` — no other module needs Spring Cloud, and importing it at the root would put its managed versions in scope for every module for no reason.

- [ ] **Step 3: Create `gateway-service/src/main/resources/application.yml`**

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway-service
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

account-service:
  base-url: ${ACCOUNT_SERVICE_URL:http://localhost:8081}

transfer-service:
  base-url: ${TRANSFER_SERVICE_URL:http://localhost:8082}
```

- [ ] **Step 4: Create `gateway-service/src/main/java/com/showcase/gateway/GatewayServiceApplication.java`**

```java
package com.showcase.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: Write the failing test — `GatewayRoutesConfigTest`**

```java
package com.showcase.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutesConfigTest {

    private final GatewayRoutesConfig config = new GatewayRoutesConfig();
    private final RouterFunction<ServerResponse> transferRoutes =
            config.transferRoutes(new TransferServiceProperties("http://transfer-service:8082"));
    private final RouterFunction<ServerResponse> accountRoutes =
            config.accountRoutes(new AccountServiceProperties("http://account-service:8081"));

    /**
     * Checks only whether a route predicate matches -- RouterFunction.route() never invokes
     * the handler, so this never makes a real network call and needs no backend running.
     */
    private static boolean matches(RouterFunction<ServerResponse> routes, String method, String path) {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(mockRequest);
        List<HttpMessageConverter<?>> converters = List.of();
        ServerRequest request = ServerRequest.create(mockRequest, converters);
        return routes.route(request).isPresent();
    }

    @Test
    void transfersPathMatchesAnyMethod() {
        assertThat(matches(transferRoutes, "POST", "/transfers")).isTrue();
        assertThat(matches(transferRoutes, "GET", "/transfers")).isTrue();
        assertThat(matches(transferRoutes, "GET", "/transfers/123")).isTrue();
    }

    @Test
    void accountCreateAndListMatch() {
        assertThat(matches(accountRoutes, "POST", "/accounts")).isTrue();
        assertThat(matches(accountRoutes, "GET", "/accounts")).isTrue();
    }

    @Test
    void accountGetByIdMatches() {
        assertThat(matches(accountRoutes, "GET", "/accounts/123")).isTrue();
    }

    @Test
    void accountDebitAndCreditHaveNoRoute() {
        assertThat(matches(accountRoutes, "POST", "/accounts/123/debit")).isFalse();
        assertThat(matches(accountRoutes, "POST", "/accounts/123/credit")).isFalse();
    }

    @Test
    void unrelatedPathsHaveNoRoute() {
        assertThat(matches(transferRoutes, "GET", "/fraud-check")).isFalse();
        assertThat(matches(accountRoutes, "GET", "/fraud-check")).isFalse();
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `./mvnw -pl gateway-service -am test -Dtest=GatewayRoutesConfigTest`
Expected: compile failure — `GatewayRoutesConfig`, `AccountServiceProperties`, `TransferServiceProperties` do not exist yet.

- [ ] **Step 7: Implement the config-properties records**

```java
// gateway-service/src/main/java/com/showcase/gateway/config/AccountServiceProperties.java
package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "account-service")
public record AccountServiceProperties(String baseUrl) {
}
```

```java
// gateway-service/src/main/java/com/showcase/gateway/config/TransferServiceProperties.java
package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transfer-service")
public record TransferServiceProperties(String baseUrl) {
}
```

- [ ] **Step 8: Implement `GatewayRoutesConfig`**

```java
package com.showcase.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * Allow-list routing: only the predicates declared here are reachable through the Gateway.
 * Account's debit/credit endpoints (and every Fraud/Notification path) have no route at
 * all, so they 404 by construction instead of relying on a blocklist filter that could go
 * stale as Account's API grows. See docs/phase-6-api-gateway.md's Design Decisions.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> transferRoutes(TransferServiceProperties properties) {
        return route(path("/transfers/**"), http(properties.baseUrl()));
    }

    @Bean
    public RouterFunction<ServerResponse> accountRoutes(AccountServiceProperties properties) {
        RequestPredicate accountPaths = POST("/accounts")
                .or(GET("/accounts"))
                .or(GET("/accounts/{id}"));
        return route(accountPaths, http(properties.baseUrl()));
    }
}
```

**Re-synced from the merged source (see Design Decisions below for why):** the plan as originally
written used `GatewayRouterFunctions.route(routeId).route(predicate, http()).before(uri(baseUrl)).build()`
— the current (post-4.3.x) Gateway Server MVC API. The actual dependency resolved for this
release train is `spring-cloud-gateway-server-mvc:4.1.6`, whose `HandlerFunctions` has no no-arg
`http()`/`BeforeFilterFunctions.uri()` pair; verified via `javap` against the resolved jar. The
2-arg static `GatewayRouterFunctions.route(RequestPredicate, HandlerFunction)` plus
`HandlerFunctions.http(String)` used above is 4.1.6's equivalent, not a workaround: reading
`HandlerFunctions`'s source shows `http(String)` sets the same `MvcUtils.GATEWAY_REQUEST_URL_ATTR`
and delegates to the same `ProxyExchangeHandlerFunction` as the newer no-arg form.

- [ ] **Step 9: Run the test to verify it passes**

Run: `./mvnw -pl gateway-service -am test -Dtest=GatewayRoutesConfigTest`
Expected: PASS, all 5 tests green.

- [ ] **Step 10: Commit**

```bash
git checkout -b feature/phase-6-task-1-gateway-module
git add pom.xml gateway-service
git commit -m "feat(gateway): add gateway-service module with allow-listed routing (Phase 6, Task 1)"
```

---

### Task 2: Integration test — real proxy round-trip

**Files:**
- Test: `gateway-service/src/test/java/com/showcase/gateway/GatewayRoutingIT.java`

**Interfaces:**
- Consumes: Task 1's `account-service.base-url` / `transfer-service.base-url` config keys (overridden here via `@DynamicPropertySource`) and the two `RouterFunction` beans they drive. No new production interfaces.

This mirrors `transfer-service`'s `AccountClientFallbackIT` pattern — a tiny real JDK `com.sun.net.httpserver.HttpServer` stands in for the upstream, not a mocking library, so no new test dependency is introduced. `gateway-service` has no database, so (unlike `AccountClientFallbackIT`) this test needs no Testcontainers/Postgres at all.

- [ ] **Step 1: Write the failing test**

```java
package com.showcase.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIT {

    private static HttpServer fakeTransferService;
    private static HttpServer fakeAccountService;

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) throws IOException {
        fakeTransferService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeTransferService.createContext("/transfers/123", exchange -> {
            byte[] body = "{\"id\":\"123\",\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeTransferService.start();
        registry.add("transfer-service.base-url",
                () -> "http://localhost:" + fakeTransferService.getAddress().getPort());

        fakeAccountService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeAccountService.createContext("/accounts/999", exchange -> {
            byte[] body = ("""
                    {"type":"https://showcase.example/errors/account-not-found","title":"Account not found",
                     "status":404,"detail":"Account not found: 999","code":"ACCOUNT_NOT_FOUND",
                     "timestamp":"2026-09-16T12:00:00Z"}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeAccountService.start();
        registry.add("account-service.base-url",
                () -> "http://localhost:" + fakeAccountService.getAddress().getPort());
    }

    @AfterAll
    static void stopFakes() {
        if (fakeTransferService != null) {
            fakeTransferService.stop(0);
        }
        if (fakeAccountService != null) {
            fakeAccountService.stop(0);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void proxiesToTransferServiceUnchanged() {
        ResponseEntity<String> response = restTemplate.getForEntity("/transfers/123", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"COMPLETED\"");
    }

    @Test
    void proxiesProblemJsonErrorBodyUnchanged() {
        ResponseEntity<String> response = restTemplate.getForEntity("/accounts/999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"ACCOUNT_NOT_FOUND\"");
    }

    @Test
    void debitPathIsUnreachableThroughTheGateway() {
        ResponseEntity<String> response = restTemplate.postForEntity("/accounts/999/debit", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void creditPathIsUnreachableThroughTheGateway() {
        ResponseEntity<String> response = restTemplate.postForEntity("/accounts/999/credit", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl gateway-service -am test -Dtest=GatewayRoutingIT`
Expected: FAIL (or PASS-by-accident is not possible here — Task 1's routes already exist, so this step is really "run it once before assuming it works" rather than a true red step; if it fails, read the failure before touching Task 1's code, since the bug is more likely in this test's fake-server wiring than in already-tested routing).

- [ ] **Step 3: Fix any wiring issues, then run again to verify it passes**

Run: `./mvnw -pl gateway-service -am test -Dtest=GatewayRoutingIT`
Expected: PASS, all 4 tests green.

- [ ] **Step 4: Commit**

```bash
git checkout -b feature/phase-6-task-2-gateway-integration-test
git add gateway-service/src/test/java/com/showcase/gateway/GatewayRoutingIT.java
git commit -m "test(gateway): add real proxy round-trip integration test (Phase 6, Task 2)"
```

---

### Task 3: Docker Compose, Dockerfiles, and documentation sync

**Files:**
- Create: `gateway-service/Dockerfile`
- Modify: `account-service/Dockerfile`, `transfer-service/Dockerfile`, `notification-service/Dockerfile`, `fraud-service/Dockerfile` (each needs `gateway-service/pom.xml` copied into the reactor build stage)
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `docs/roadmap.md`

**Interfaces:** none — this task wires and documents Tasks 1–2's already-tested code; no new production interfaces.

- [ ] **Step 1: Create `gateway-service/Dockerfile`**

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
RUN chmod +x mvnw && ./mvnw -pl gateway-service -am dependency:go-offline -B
COPY gateway-service/src gateway-service/src
RUN ./mvnw -pl gateway-service -am package -DskipTests -B

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/gateway-service/target/gateway-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Add the new module's `pom.xml` to the other four Dockerfiles**

In `account-service/Dockerfile`, `transfer-service/Dockerfile`, `notification-service/Dockerfile`, and `fraud-service/Dockerfile`, add this line immediately after the existing `COPY fraud-service/pom.xml fraud-service/pom.xml` line:

```dockerfile
COPY gateway-service/pom.xml gateway-service/pom.xml
```

(Every Dockerfile copies every module's `pom.xml` so Maven's `-am` reactor build can resolve the full dependency graph — the root `pom.xml`'s `<modules>` list now names five modules, and `mvn -pl X -am` fails immediately if any listed module's `pom.xml` is missing from the build context, even one `X` doesn't depend on.)

- [ ] **Step 3: Add `gateway-service` to `docker-compose.yml`**

Add this service block (after `fraud-service` is fine):

```yaml
  gateway-service:
    build:
      context: .
      dockerfile: gateway-service/Dockerfile
    container_name: showcase-gateway-service
    environment:
      ACCOUNT_SERVICE_URL: http://account-service:8081
      TRANSFER_SERVICE_URL: http://transfer-service:8082
    ports:
      - "8080:8080"
    depends_on:
      account-service:
        condition: service_healthy
      transfer-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 40s
```

- [ ] **Step 4: Update `README.md`**

- In the "Running locally" section, change `"This starts Postgres, Account Service (8081), Transfer Service (8082), Fraud Service (8084), Kafka, and Notification Service (8083)."` to `"This starts Postgres, Account Service (8081), Transfer Service (8082), Fraud Service (8084), Kafka, Notification Service (8083), and the API Gateway (8080)."`
- Add a new section after "Try it (Swagger UI)" and before "Try it (curl)":
  ```markdown
  ## API Gateway

  A single entry point at `http://localhost:8080` routes to the two client-facing services:

  - `/transfers/**` → Transfer Service (full API)
  - `POST /accounts`, `GET /accounts`, `GET /accounts/{id}` → Account Service

  Account's `/accounts/{id}/debit` and `/accounts/{id}/credit` are intentionally **not** routed
  — they're internal saga calls Transfer Service makes directly on the Docker network, and stay
  unreachable from outside it. Every other example in this README still targets each service's
  own port directly (8081/8082/8083/8084); the Gateway doesn't replace those, it adds a second,
  narrower way in. There is no authentication yet (see `docs/roadmap.md` Phase 7) — anyone who
  can reach port 8080 can reach the routed paths, same as reaching 8081/8082 directly today.
  ```

- [ ] **Step 5: Update `docs/roadmap.md`**

Change row 6 from:
```
| 6 | API Gateway + Auth | Not started | Keycloak (pre-configured realm), JWT validation at the Gateway and via Spring Security Resource Server in each service |
```
to:
```
| 6 | [API Gateway](phase-6-api-gateway.md) | ✅ Done | Routing-only `gateway-service` (Spring Cloud Gateway Server MVC, blocking — no WebFlux) in front of Transfer Service's full API and Account Service's three client-safe paths (`POST /accounts`, `GET /accounts`, `GET /accounts/{id}`); Account's `debit`/`credit` and all of Fraud/Notification stay unreachable through the Gateway by construction (no route exists), not by a filter. No JWT/auth in this phase. |
```

Change row 7 from:
```
| 7 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; Spring Cloud Contract tests; end-to-end saga test module; final `docker compose up` bringing up all services + infra |
```
to two rows:
```
| 7 | Auth (Keycloak/JWT) | Not started | Keycloak (pre-configured realm), JWT validation at the Gateway and via Spring Security Resource Server in each service — split out from the original "Gateway + Auth" forecast during Phase 6 brainstorming (see `docs/phase-6-api-gateway.md`) |
| 8 | Observability + Full Compose Integration | Not started | OTel Collector, Prometheus, Grafana, Jaeger/Tempo; full trace across the sync+async hop; Spring Cloud Contract tests; end-to-end saga test module; final `docker compose up` bringing up all services + infra |
```

- [ ] **Step 6: Manual verification**

```bash
docker compose down -v
docker compose up --build
```

Wait for all containers healthy, then:

```bash
# Create an account directly on Account Service, and again through the Gateway -- both work
curl -X POST http://localhost:8081/accounts -H "Content-Type: application/json" \
  -d '{"ownerName": "Ada", "initialBalance": 100.00}'
curl -X POST http://localhost:8080/accounts -H "Content-Type: application/json" \
  -d '{"ownerName": "Bob", "initialBalance": 100.00}'

# A transfer through the Gateway -- expect the same COMPLETED response Transfer Service itself would give
curl -X POST http://localhost:8080/transfers -H "Content-Type: application/json" \
  -d '{"fromAccountId": "<ADA_ID>", "toAccountId": "<BOB_ID>", "amount": 10.00}'

# debit/credit are unreachable through the Gateway -- expect 404, not the account-not-found problem+json
curl -i -X POST http://localhost:8080/accounts/<ADA_ID>/debit -H "Content-Type: application/json" \
  -d '{"amount": 1.00, "idempotencyKey": "manual-check"}'
```

Expected: the Gateway-routed create/transfer calls return the same bodies/status codes as calling Account/Transfer directly would; the debit call through the Gateway returns a plain `404` (no route), while the same call against `http://localhost:8081/accounts/<ADA_ID>/debit` directly still works exactly as before — the Gateway narrows what's reachable from outside, it doesn't change Account Service's own contract.

- [ ] **Step 7: Commit**

```bash
git checkout -b feature/phase-6-task-3-compose-and-docs
git add gateway-service/Dockerfile account-service/Dockerfile transfer-service/Dockerfile \
        notification-service/Dockerfile fraud-service/Dockerfile docker-compose.yml README.md docs/roadmap.md
git commit -m "docs+infra: wire API Gateway into Docker Compose and sync docs (Phase 6, Task 3)"
```
