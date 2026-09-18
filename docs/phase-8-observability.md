# Observability Implementation Phase (Phase 8)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire tracing, metrics, and structured logging across all five services so the system is observable the way the design doc describes: one continuous trace spanning Gateway → Transfer → Account/Fraud → (async hop via Kafka headers) → Notification, business + JVM/HTTP metrics on Grafana dashboards, and JSON logs carrying trace/span context. Full `docker compose up` bringing up every service already works (done incidentally during phases 5–7) — this phase adds the observability stack (OTel Collector, Tempo, Prometheus, Grafana) on top of it. Two items in the roadmap's original phase-8 forecast are explicitly not part of this phase: the end-to-end saga test module (split out to **Phase 9**, to be brainstormed separately) and Spring Cloud Contract tests (dropped from the roadmap entirely, not deferred).

**Architecture:** Every service gets the Micrometer Tracing → OTel bridge (REST calls propagate W3C trace context automatically; the Kafka hop propagates it via Spring Kafka's observation instrumentation) exporting OTLP to a new `otel-collector` container, which forwards to a new `tempo` container — Grafana's native trace backend, chosen over Jaeger so traces and metrics dashboards live in one UI. Metrics take a separate, simpler path: each service exposes `/actuator/prometheus` directly, scraped by a new `prometheus` container — no detour through the collector. A new `grafana` container has both data sources provisioned on startup, plus a JVM/HTTP dashboard and one hand-authored dashboard for this project's business metrics. Structured JSON logging uses Spring Boot's native support (added in 3.4), which requires bumping the project off 3.3.8.

**Tech Stack:** Spring Boot bumped from 3.3.8 to **3.5.16** project-wide, via the root `pom.xml`'s parent version (same mechanism as phase 6's 3.3.4→3.3.8 bump) — see Design Decisions for why 3.5.16 despite it already being past OSS end-of-life. `gateway-service`'s Spring Cloud BOM bumps in lockstep, from `2023.0.5` to **`2025.0.3`** (Northfields, the release train matched to Boot 3.5.x — verified via web search; 2025.0.3 itself targets Boot 3.5.15, one patch behind this phase's 3.5.16, which is a normal same-minor-line mismatch). Both bumps were verified together in an isolated worktree during planning: full reactor `mvn test` (all 6 modules, every existing test) passes clean at these versions with zero source changes needed. `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` + `micrometer-registry-prometheus` added to all five services. New containers: `otel/opentelemetry-collector-contrib`, `grafana/tempo`, `prom/prometheus`, `grafana/grafana` (exact tags verified against Docker Hub at implementation time, not pinned here). No new Java dependency for logging — Boot 3.5's built-in `logging.structured.format.console=logstash` property is sufficient.

**Spec:** This document (brainstormed with the user on 2026-09-17) and `docs/microservices-showcase-design.md` §5 (Observability) and §8 (deployment/infra scope).

## Global Constraints

- Java 21 floor; Spring Boot bumped to 3.5.16 as part of this phase (Task 1) — see Design Decisions. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads unchanged by this phase.
- No k8s/service mesh; local deployment stays Docker Compose only, single instance per service.
- No centralized log aggregation (ELK/Loki) — explicitly out of scope per the design doc. Logs stay in container stdout.
- Do not build the end-to-end saga test module in this phase — that's Phase 9, brainstormed separately.
- Never commit directly to `master`; work happens on `feature/phase-8-task-<N>-*` branches, one PR per task, stop after each. Subagent review is manual, on request — not automatic. (CLAUDE.md)
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review. This phase touches every service's dependency tree and pom (the Boot bump) plus every service's runtime config — a plausible-looking but wrong OTLP endpoint or a silently-dropped metric is the kind of defect that only a careful whole-branch pass catches, same category as phase 6's Boot-bump review. Always name the model explicitly. (CLAUDE.md)

## Design Decisions Worth Knowing Before You Start

**Why Grafana Tempo over Jaeger.** Jaeger is the more broadly adopted, more mature standalone tracing backend with its own polished UI. Tempo is newer and has a smaller community on its own, but it's the native Grafana data source — and this phase already commits to Grafana for metrics dashboards. Picking Tempo means traces and metrics dashboards live in one UI with trace-to-metrics correlation available, instead of splitting attention across a separate Jaeger tab. For a project explicitly demonstrating observability, that single-pane-of-glass story is worth more than Jaeger's maturity edge.

**Why Boot 3.5.16 despite it being past OSS end-of-life (June 30, 2026).** The only currently-maintained line is Boot 4.x, which brings Spring Framework 7, Jakarta EE 11 (Servlet 6.1, JPA 3.2), and Jackson 3.x — a project-wide breaking-change migration on the scale of its own phase, not something to fold into an observability phase as a side effect. The user made a deliberate, informed call to stay on 3.5.16 (the last 3.5.x release) rather than take on that migration now. This is a real, named gap, not an oversight — a future Boot 4.x migration phase is recorded in Scope Boundary below.

**Why the OTel Collector stays in the pipeline even though services could push OTLP straight to Tempo.** Tempo accepts OTLP natively, so the collector is not strictly required to get traces from services to Tempo. It's kept anyway because the design doc specifies it as a pipeline stage, and because it's the natural place to add processing (sampling policies, a second export target, attribute scrubbing) later without touching any service's config — a thin `otlp` receiver → `batch` processor → `otlp` exporter passthrough today, with room to grow.

**Why Prometheus scrapes services directly instead of going through the Collector.** The design doc treats metrics and traces as two separate pipelines (§5: "Micrometer + Prometheus... scraped by Prometheus" is described independently from the tracing pipeline). Routing metrics through the Collector too would add a hop with no benefit here — Prometheus's pull model already works directly against each service's `/actuator/prometheus`, and nothing in this phase needs the Collector to touch metrics at all.

**Why app services don't hard-gate their startup on the OTel Collector's health.** Unlike Postgres/Kafka/Keycloak — real dependencies a service cannot function without — OTLP trace export is fire-and-forget and retries on its own. Gating every service's `docker compose up` startup on the collector being healthy would slow every local run for no correctness benefit; a service that starts before the collector just has its first few spans dropped, then exports normally once it's up.

**Why `logstash` over `ecs`/`gelf` for the structured logging format.** All three are natively supported by Boot 3.5. ECS targets the Elastic stack and GELF targets Graylog — neither of which this project uses or plans to use (no ELK/Loki, per the design doc). `logstash` is the more generic "structured JSON with MDC folded in" option, which is exactly what's needed: JSON logs carrying `traceId`/`spanId`, nothing more Elastic/Graylog-specific.

**Correction found while writing the implementation plan: the outbox-backlog gauge already exists.** `OutboxPublisher`'s constructor (`transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisher.java:41-42`) already registers `meterRegistry.gauge("transfer.outbox.backlog", outboxEventRepository, repository -> (double) repository.countByPublishedAtIsNull())` — a live query on every read, not poll-cycle-cached as this section originally (and wrongly) assumed before the codebase was actually checked. It was added in an earlier phase, before any `MeterRegistry` was Prometheus-backed, so it has never been visible on a scrape endpoint. Nothing needs to change in `OutboxPublisher` itself — this phase's Metrics task only needs to add the Prometheus registry so this already-registered gauge becomes visible on `/actuator/prometheus` for the first time. The live-query approach is fine as-is: `countByPublishedAtIsNull()` is a single indexed count, and Prometheus scrapes at a 10s interval, not per-request.

**Why `resilience4j-micrometer` needs no explicit pom entry.** Verified via `mvn dependency:tree -Dincludes=io.github.resilience4j` against `transfer-service`: `resilience4j-spring-boot3:2.4.0` already pulls in `resilience4j-micrometer:2.4.0` transitively. Circuit-breaker metrics become visible the moment the Prometheus registry is added — no pom change needed for this specifically.

## Tracing

- All 5 services: add `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp` to their poms.
- `management.tracing.sampling.probability=1.0` — 100% sampling. Traffic in this project is low (a manually-triggered demo, not production load), and the entire point of this phase is to make every trace visible, not to prove sampling works.
- `management.otlp.tracing.endpoint=http://otel-collector:4318/v1/traces` on all 5 services.
- Spring Kafka observation instrumentation enabled on both the producer side (Transfer's outbox publish) and consumer side (Notification's listener): `spring.kafka.template.observation-enabled=true` / `spring.kafka.listener.observation-enabled=true`. This is what makes trace context ride along in Kafka message headers, proving the sync/async boundary crossing the design doc calls out as the concrete demonstration of this project's dual-communication-style goal.
- New `otel-collector` container (`otel/opentelemetry-collector-contrib`): `otlp` receiver (grpc + http), `batch` processor, `otlp` exporter pointed at `tempo:4317`; `health_check` extension exposed on `:13133` for the Compose healthcheck.
- New `tempo` container (`grafana/tempo`), OTLP receiver enabled in its config, `/ready` healthcheck.

## Metrics

- All 5 services: add `io.micrometer:micrometer-registry-prometheus`; widen `management.endpoints.web.exposure.include` to include `prometheus` alongside the existing `health`.
- New `prometheus` container, static scrape config (`docker/prometheus/prometheus.yml`) targeting each service's `<container-name>:<port>/actuator/prometheus` on a 10s interval; `/-/healthy` healthcheck.
- Business metrics, all three derived inside Transfer Service's `TransferSaveService.save()` choke point (phase 4) — the one place that already computes `OutboxEventType.forStatus(saved.getStatus())`, so no new call site is needed:
  - `transfers.completed`, incremented when `eventType == TRANSFER_COMPLETED`.
  - `transfers.failed`, incremented when `eventType == TRANSFER_FAILED` (covers `FAILED`, `COMPENSATED`, and `COMPENSATION_FAILED` — the same terminal-failure grouping `OutboxEventType.forStatus()` already uses, reused rather than re-derived).
  - `transfers.fraud_rejected`, incremented additionally (alongside `transfers.failed`) when the saved transfer's `failureCode` is `SOURCE_ACCOUNT_BLOCKED` or `DESTINATION_ACCOUNT_BLOCKED` — the two `TransferFailureCode` values a fraud-service rejection actually produces. This refines the spec's original wording ("incremented where the saga's `FraudClient` call causes a rejection") to reuse the existing choke point instead of adding a second metrics call site inside the saga itself.
  - `transfer.outbox.backlog` gauge — already implemented (see Design Decisions); nothing to add here.
- Resilience4j: no pom change needed — `resilience4j-micrometer` is already a transitive dependency (see Design Decisions). Circuit-breaker state transitions become visible on `/actuator/prometheus` as soon as the Prometheus registry is added.

## Structured Logging

- `logging.structured.format.console=logstash` added to all 5 services' `application.yml`. No new dependency, no logback XML — Boot 3.5's native writer emits JSON to stdout and folds the MDC (where Micrometer Tracing already places `traceId`/`spanId`) into JSON fields automatically.
- Logs remain in container stdout, viewable via `docker compose logs`. No aggregation stack (ELK/Loki) — explicitly out of scope per the design doc.

## Grafana Dashboards

- New `grafana` container. Data sources provisioned on startup via `docker/grafana/provisioning/datasources/` — Prometheus and Tempo both pre-wired, no manual setup after `docker compose up`.
- Dashboards provisioned via `docker/grafana/provisioning/dashboards/` pointing at a `docker/grafana/dashboards/` folder containing:
  - A known community JVM/Micrometer dashboard (JSON import) for standard JVM/HTTP metrics.
  - One hand-authored dashboard for this project's business metrics: transfer completed/failed/fraud-rejected counters, outbox-backlog gauge, and a circuit-breaker-state panel.
- `/api/health` healthcheck; `depends_on` Prometheus and Tempo (both healthy).

## Docker Compose Wiring

- New services: `otel-collector`, `tempo`, `prometheus`, `grafana`, each following the existing `docker/<tool>/` config-mount pattern already used for `docker/postgres` and `docker/keycloak`.
- All 5 app services get an env var pointing their OTLP exporter at `otel-collector:4318` — no hard `depends_on` health-gate on the collector (see Design Decisions).
- Healthchecks: Prometheus `/-/healthy`, Grafana `/api/health`, Tempo `/ready`, OTel Collector via its `health_check` extension on `:13133`.

## Task Breakdown

Each task is its own branch/PR off the current `master`, per this project's workflow:

1. **Boot 3.3.8 → 3.5.16 bump**, project-wide via the root `pom.xml` parent version. Done first since every later task builds on it; full build + test suite re-run across all 5 modules to catch anything the bump breaks before any observability code is added.
2. **Tracing pipeline**: tracing dependencies + config on all 5 services, Kafka observation flags, `otel-collector` + `tempo` containers wired into Compose.
3. **Metrics**: Prometheus dependency + config on all 5 services, business metrics at their source points, `prometheus` container wired into Compose.
4. **Structured JSON logging**: `logging.structured.format.console=logstash` on all 5 services.
5. **Grafana**: container + provisioned datasources + dashboards wired into Compose.
6. **Docs sync**: design.md (resolve "Jaeger (or Grafana Tempo)" to Tempo, drop the Spring Cloud Contract line entirely) — same pattern as every prior phase's final wiring/docs-sync commit. roadmap.md was already synced ahead of implementation, in this phase's spec PR — see Roadmap Changes.

## Testing

- **Per-service metric-exposure tests:** a lightweight test per service (MockMvc or `TestRestTemplate` against `/actuator/prometheus`) asserting the service's own business metric names (e.g. `transfers_completed_total` on Transfer Service) appear in the scrape output — catches a metric silently never registered without needing the full stack up.
- **Structured logging test:** capture stdout during a test run and assert it parses as JSON with `traceId`/`spanId` keys present on a log line emitted inside a traced request — proves the MDC-to-JSON wiring actually works, not just that the property is set.
- **Manual live verification (required, not optional — this project's culture is to verify claims against a real run, not just claim them):**
  ```bash
  docker compose down -v
  docker compose up --build
  ```
  Wait for all containers healthy, then:
  - Trigger a transfer through the Gateway; confirm a single trace in Tempo (via Grafana Explore) spans Gateway → Transfer → Account → Fraud → the Kafka hop → Notification.
  - Check Prometheus's Targets page — all 5 services `UP`.
  - Open the provisioned Grafana dashboards — JVM panel and the business-metrics dashboard both populated, no "no data" panels.
  - Pull a service's container logs (`docker compose logs transfer-service`) and confirm lines are JSON with a `traceId` matching the trace ID shown in Tempo for that request.
  - Force a circuit-breaker trip (stop Account Service mid-run, as phase 3's testing already does) and confirm the circuit-breaker-state panel reflects the transition.

## Scope Boundary

**In scope:** tracing (Micrometer→OTel Collector→Tempo) across all 5 services and both the sync and async hop; metrics (Prometheus + business metrics + Resilience4j) across all 5 services; structured JSON logging across all 5 services; Grafana with provisioned datasources and dashboards; the Boot 3.3.8→3.5.16 bump; roadmap/design doc sync.

**Explicitly out of scope** — named follow-ups, not oversights:

| Deferred | Why not now | Lands in |
|---|---|---|
| End-to-end saga test module (Testcontainers-driven, real services through the Gateway) | Independent concern from runtime observability infra — different kind of work, own design questions (which scenarios, how services get booted for the test) | Phase 9 (not yet brainstormed) |
| Boot 4.x migration (Spring Framework 7 / Jakarta EE 11 / Jackson 3) | 3.5.16 was a deliberate stopgap despite being past OSS EOL — see Design Decisions. A real gap, not deferred lightly: this project is meant to demonstrate modern practice, and it's now pinned to a dead branch | Not yet scheduled — revisit as its own phase |
| Centralized log aggregation (ELK/Loki) | Explicitly out of scope per the design doc §5 | Not currently planned |
| Alerting (Prometheus Alertmanager / Grafana alert rules) | Not asked for; the design doc's observability section describes dashboards and traces, not alerting | Not currently planned |
| Trace-to-metrics exemplar correlation in Grafana | Nice-to-have Tempo/Grafana feature, not required by the design doc's observability goals | Not currently planned — revisit if it proves easy during implementation |

## Roadmap Changes

- `docs/roadmap.md` row 8 already narrowed to this phase's actual scope, linked to this document, ahead of implementation (done in this spec PR at the user's request, rather than waiting for Task 6 as every prior phase did); a new row 9, "Not started," forecasts the end-to-end saga test module.
- `docs/microservices-showcase-design.md` §5 gets "Jaeger (or Grafana Tempo)" resolved to "Grafana Tempo"; the Spring Cloud Contract line (§8) is removed entirely, not marked deferred. Still pending — lands in Task 6.

---

## Tasks

All commands below assume `JAVA_HOME` is set to `C:\dev\openjdk-21.0.2` first (CLAUDE.md). Every infra config below (Tempo, OTel Collector, Grafana provisioning) was verified live against the real images during planning — see each task's notes for what was actually run, not assumed.

### Task 1: Spring Boot 3.3.8 → 3.5.16, gateway-service's Spring Cloud 2023.0.5 → 2025.0.3

**Files:**
- Modify: `pom.xml:10`
- Modify: `gateway-service/pom.xml:17`

**Interfaces:** none — a dependency-version change only, no new types or signatures. Every later task assumes the reactor already builds green at these versions.

Verified during planning, in an isolated git worktree, before writing this task: bumping both versions together and running `./mvnw test` across the full reactor (all 6 modules, every existing test — account-service, transfer-service, notification-service, fraud-service, gateway-service) passed with **zero failures and zero source changes needed**. 2025.0.3 is the Spring Cloud release train matched to Boot 3.5.x ("Northfields"); it itself targets Boot 3.5.15, one patch behind this project's target 3.5.16, which is a normal same-minor-line mismatch, not a conflict.

- [ ] **Step 1: Bump the root `pom.xml`'s Boot parent version**

In `pom.xml`, change:
```xml
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.8</version>
    <relativePath/>
  </parent>
```
to:
```xml
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
    <relativePath/>
  </parent>
```

- [ ] **Step 2: Bump `gateway-service`'s Spring Cloud BOM**

In `gateway-service/pom.xml`, change:
```xml
  <properties>
    <spring-cloud.version>2023.0.5</spring-cloud.version>
  </properties>
```
to:
```xml
  <properties>
    <spring-cloud.version>2025.0.3</spring-cloud.version>
  </properties>
```

- [ ] **Step 3: Run the full reactor test suite**

Run: `./mvnw test`
Expected: `BUILD SUCCESS`, all 6 modules (root aggregator + 5 services), zero failed/errored tests. This re-confirms what was already verified in the scratch worktree during planning — if anything differs here, something about this branch's state differs from that verification and needs investigating before continuing, not skipping.

- [ ] **Step 4: Commit**

```bash
git checkout -b feature/phase-8-task-1-boot-bump
git add pom.xml gateway-service/pom.xml
git commit -m "chore: bump Spring Boot 3.3.8 -> 3.5.16, Spring Cloud 2023.0.5 -> 2025.0.3 (Phase 8, Task 1)"
```

---

### Task 2: Tracing — Micrometer Tracing → OTel Collector → Tempo

**Files:**
- Modify: `account-service/pom.xml`, `transfer-service/pom.xml`, `notification-service/pom.xml`, `fraud-service/pom.xml`, `gateway-service/pom.xml`
- Modify: `account-service/src/main/resources/application.yml`, `transfer-service/src/main/resources/application.yml`, `notification-service/src/main/resources/application.yml`, `fraud-service/src/main/resources/application.yml`, `gateway-service/src/main/resources/application.yml`
- Create: `docker/otel-collector/config.yaml`
- Create: `docker/tempo/tempo.yaml`
- Modify: `docker-compose.yml`
- Test: `transfer-service/src/test/java/com/showcase/transfer/TracingBridgeIT.java`, and one equivalent per remaining service (see the table in Step 6)

**Interfaces:**
- Produces: every service now has an active `io.micrometer.tracing.Tracer` bean backed by `io.micrometer.tracing.otel.bridge.OtelTracer`, and exports OTLP spans to `otel-collector:4318`. Task 4 (structured logging) depends on this — the MDC `traceId`/`spanId` entries it asserts on come from this task's tracing bridge being active.

- [ ] **Step 1: Add the tracing dependencies to all 5 poms**

Add to `account-service/pom.xml`, `transfer-service/pom.xml`, `notification-service/pom.xml`, `fraud-service/pom.xml`, and `gateway-service/pom.xml` (inside `<dependencies>`, versions come from the Boot BOM — no explicit `<version>`, matching every other Boot-managed dependency already in these poms):

```xml
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
```

- [ ] **Step 2: Write the failing test — `TracingBridgeIT` (transfer-service)**

This proves the tracing bridge is actually active, not just that the dependency is present — it fails if the bridge is misconfigured or excluded, not just if it's missing. Mirrors `TransferSecurityIT`'s Postgres-Testcontainers setup (same reasoning: JPA autoconfiguration needs a real datasource for a full context boot), but doesn't need Security's `@AutoConfigureMockMvc`/JWT setup since it only asserts on the `Tracer` bean.

```java
// transfer-service/src/test/java/com/showcase/transfer/TracingBridgeIT.java
package com.showcase.transfer;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TracingBridgeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Tracer tracer;

    @Test
    void tracingBridgeIsActive() {
        assertThat(tracer).isInstanceOf(OtelTracer.class);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -pl transfer-service -am test -Dtest=TracingBridgeIT`
Expected: FAIL — no `Tracer` bean exists yet (`NoSuchBeanDefinitionException`), since the tracing dependencies aren't on the classpath.

- [ ] **Step 4: Add tracing config to all 5 `application.yml` files**

Add this block to each of the 5 services' `application.yml`, under the existing `management:` key (alongside `endpoints`/`endpoint`, which every service already has):

```yaml
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
```

So e.g. `account-service/src/main/resources/application.yml`'s `management:` block becomes:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
```
(same shape for `transfer-service`, `notification-service`, `fraud-service`, `gateway-service` — each already has an identical `management.endpoints`/`management.endpoint` block to append this under.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -pl transfer-service -am test -Dtest=TracingBridgeIT`
Expected: PASS.

- [ ] **Step 6: Add the equivalent test to the other 4 services**

Same test body as Step 2 (package changed to match each service, class name `TracingBridgeIT`), but each service's Testcontainers/context setup should be copied from its own existing pattern rather than reinvented:

| Service | Test package | Copy container/context setup from |
|---|---|---|
| `account-service` | `com.showcase.account` | `AccountControllerIT` (Postgres Testcontainers, `@ServiceConnection`) |
| `notification-service` | `com.showcase.notification` | `NotificationListenerIT` (Kafka Testcontainers, `@ServiceConnection`) |
| `fraud-service` | `com.showcase.fraud` | `FraudCheckControllerTest` (plain `@SpringBootTest`, no containers — fraud-service has no DB) |
| `gateway-service` | `com.showcase.gateway` | `GatewaySecurityIT` (plain `@SpringBootTest`, no containers — gateway-service has no DB) |

Run each: `./mvnw -pl <service> -am test -Dtest=TracingBridgeIT` — expected PASS for all 4, same reasoning as Step 5.

- [ ] **Step 7: Enable Spring Kafka observation instrumentation**

In `transfer-service/src/main/resources/application.yml`, under the existing `spring.kafka:` key, add a sibling `template` block (alongside the existing `producer:` block):
```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    template:
      observation-enabled: true
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      ...
```
(only the new `template:` block is added — the existing `producer:` block and everything under it is unchanged.)

In `notification-service/src/main/resources/application.yml`, under the existing `spring.kafka:` key, add a sibling `listener` block (alongside the existing `consumer:` block):
```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    listener:
      observation-enabled: true
    consumer:
      group-id: notification-service
      ...
```

This is what makes trace context ride along in Kafka message headers — Spring for Apache Kafka does not enable Observation by default (verified via Spring Kafka's own docs during planning), so both sides must opt in explicitly for the async hop to carry trace context.

- [ ] **Step 8: Create `docker/otel-collector/config.yaml`**

Verified live during planning (`docker run` directly against `otel/opentelemetry-collector-contrib:latest`, no shell available in that image — see Step 10's note): this exact config starts cleanly with zero warnings.

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:

exporters:
  otlp_grpc:
    endpoint: tempo:4317
    tls:
      insecure: true

extensions:
  health_check:
    endpoint: 0.0.0.0:13133

service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp_grpc]
```

(`otlp_grpc`, not the older `otlp` exporter alias — the latter is deprecated as of collector 0.161.0 and logs a warning on every startup; verified by running both during planning.)

- [ ] **Step 9: Create `docker/tempo/tempo.yaml`**

**Corrected during Task 2's actual implementation — this differs from what planning verified.** Planning's `docker run` check (v3.0.0, no explicit protocol endpoints) started cleanly and looked right, but that check only proved Tempo starts, not that anything can reach it. Running it inside this Compose file's network, alongside a real `otel-collector`, surfaced the actual bug: Tempo's log showed its OTLP receivers listening on `endpoint=127.0.0.1:4317` — loopback-only, unreachable from any other container — and `otel-collector`'s logs showed a continuous stream of `connection refused` retrying every few seconds, never recovering, even well past both containers' startup. Explicit `endpoint: 0.0.0.0:...` on both protocols fixes it: re-verified live in this same Compose network afterward, confirmed by Tempo's log changing to `endpoint=[::]:4317` and `otel-collector`'s connection errors disappearing entirely.

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        http:
          endpoint: 0.0.0.0:4318
        grpc:
          endpoint: 0.0.0.0:4317

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces
    wal:
      path: /var/tempo/wal
```

- [ ] **Step 10: Add `otel-collector` and `tempo` to `docker-compose.yml`**

Add these two service blocks (after `keycloak` is fine):

```yaml
  tempo:
    image: grafana/tempo:latest
    container_name: showcase-tempo
    command: ["-config.file=/etc/tempo.yaml"]
    volumes:
      - ./docker/tempo/tempo.yaml:/etc/tempo.yaml:ro
    ports:
      - "3200:3200"

  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    container_name: showcase-otel-collector
    volumes:
      - ./docker/otel-collector/config.yaml:/etc/otelcol-contrib/config.yaml:ro
    ports:
      - "4317:4317"
      - "4318:4318"
    depends_on:
      - tempo
```

Neither service gets a `healthcheck:` block. Verified live during planning: both `grafana/tempo` and `otel/opentelemetry-collector-contrib` are distroless images with **no shell, no `wget`, no `curl`** (confirmed via `docker run --entrypoint /bin/sh ...` failing with "executable file not found in $PATH" on both) — every `healthcheck.test` in this compose file uses `CMD-SHELL`, which requires a shell inside the container, so it cannot work here, unlike every other service in this file. `otel-collector`'s `depends_on: [tempo]` is a plain (non-health-gated) start-order hint, not a `condition: service_healthy` gate — Compose's `service_started` is the implicit default for a bare `depends_on` list entry. This is consistent with Design Decisions' point that nothing needs to hard-gate on the collector's health: it genuinely cannot express one here even if it wanted to.

- [ ] **Step 11: Point all 5 app services at the collector**

Add `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: http://otel-collector:4318/v1/traces` to each of the 5 app services' `environment:` block in `docker-compose.yml` (`account-service`, `transfer-service`, `notification-service`, `fraud-service`, `gateway-service`). No `depends_on` addition for any of them — see Design Decisions for why (fire-and-forget export, not a hard dependency).

- [ ] **Step 12: Commit**

```bash
git checkout -b feature/phase-8-task-2-tracing
git add account-service/pom.xml transfer-service/pom.xml notification-service/pom.xml fraud-service/pom.xml gateway-service/pom.xml \
        account-service/src/main/resources/application.yml transfer-service/src/main/resources/application.yml \
        notification-service/src/main/resources/application.yml fraud-service/src/main/resources/application.yml \
        gateway-service/src/main/resources/application.yml \
        account-service/src/test/java/com/showcase/account/TracingBridgeIT.java \
        transfer-service/src/test/java/com/showcase/transfer/TracingBridgeIT.java \
        notification-service/src/test/java/com/showcase/notification/TracingBridgeIT.java \
        fraud-service/src/test/java/com/showcase/fraud/TracingBridgeIT.java \
        gateway-service/src/test/java/com/showcase/gateway/TracingBridgeIT.java \
        docker/otel-collector/config.yaml docker/tempo/tempo.yaml docker-compose.yml
git commit -m "feat(observability): wire Micrometer Tracing -> OTel Collector -> Tempo across all 5 services (Phase 8, Task 2)"
```

---

### Task 3: Metrics — Prometheus + business counters + Resilience4j

**Files:**
- Modify: `account-service/pom.xml`, `transfer-service/pom.xml`, `notification-service/pom.xml`, `fraud-service/pom.xml`, `gateway-service/pom.xml`
- Modify: `account-service/src/main/resources/application.yml`, `transfer-service/src/main/resources/application.yml`, `notification-service/src/main/resources/application.yml`, `fraud-service/src/main/resources/application.yml`, `gateway-service/src/main/resources/application.yml`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java`
- Modify: `transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java`
- Create: `docker/prometheus/prometheus.yml`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: nothing new from Task 2.
- Produces: `/actuator/prometheus` on all 5 services. `TransferSaveService`'s constructor gains a `MeterRegistry` parameter (Spring-managed, single constructor — same pattern `OutboxPublisher` already uses).

- [ ] **Step 1: Add the Prometheus registry to all 5 poms**

Add to each of the 5 services' `<dependencies>`:
```xml
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

- [ ] **Step 2: Widen actuator exposure on all 5 services, and explicitly enable Prometheus export**

In each service's `application.yml`, change:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```
to:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

**The `management.prometheus.metrics.export.enabled: true` block is not optional, and was not in this plan's original draft — found only by actually running a test against it, not assumed from docs.** On Boot 3.5.16, adding the `micrometer-registry-prometheus` dependency and widening `exposure.include` alone still leaves `/actuator/prometheus` 404ing: the `PrometheusMeterRegistry` bean itself never gets created, because `PrometheusMetricsExportAutoConfiguration`'s `@ConditionalOnEnabledMetricsExport` condition is *false by default*. Its condition-evaluation-report message reads `management.defaults.metrics.export.enabled is considered false` — that text names the generic fallback property, not the one that actually needs setting; `management.defaults.metrics.export.enabled=true` (tried first, including as a same-JVM unforked system property to rule out any propagation issue) had no effect at all. Only the registry-specific `management.prometheus.metrics.export.enabled=true` flips the condition. Verified live: without it, `PrometheusExposureIT` (Step 8 below) gets 404; with it, 200 and the real metric names. (Getting there also needed Step 7's `SecurityConfig` fix — without that, the same endpoint 401s before this condition is ever reached.)

- [ ] **Step 3: Write the failing test — business metric counters in `TransferSaveServiceTest`**

Uses a real `io.micrometer.core.instrument.simple.SimpleMeterRegistry` (Micrometer's own lightweight in-memory implementation, built for exactly this — no mocking needed, same reasoning `OutboxPublisher`'s existing gauge already relies on a real `MeterRegistry`, not a mock).

Add to `transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java`:

```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
// ... (existing imports unchanged)

@ExtendWith(MockitoExtension.class)
class TransferSaveServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private SimpleMeterRegistry meterRegistry;
    private TransferSaveService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        service = new TransferSaveService(transferRepository, outboxEventRepository, objectMapper, meterRegistry);
    }

    // ... (existing tests unchanged, they don't touch meterRegistry)

    @Test
    void incrementsCompletedCounterForACompletedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.completed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(0.0);
    }

    @Test
    void incrementsFailedCounterForAFailedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(0.0);
    }

    @Test
    void incrementsFraudRejectedCounterAlongsideFailedForABlockedSourceAccount() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, "source blocked");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(1.0);
    }

    @Test
    void incrementsFraudRejectedCounterForABlockedDestinationAccount() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED, "destination blocked");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(1.0);
    }

    @Test
    void incrementsNoCounterForATransientStatus() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.completed").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(0.0);
    }
}
```

Also add `import static org.assertj.core.api.Assertions.assertThat;` to the existing import list.

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw -pl transfer-service -am test -Dtest=TransferSaveServiceTest`
Expected: compile failure — `TransferSaveService`'s constructor doesn't accept a fourth `MeterRegistry` argument yet.

- [ ] **Step 5: Implement the counters in `TransferSaveService`**

Modify `transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java`:

```java
// transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java
package com.showcase.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The only place a Transfer is persisted after a mark*() status transition. Centralizing
 * it here means no call site (TransferService, CompensationScheduler) has to know which
 * statuses are "outbox-worthy" -- that decision lives in one place,
 * OutboxEventType.forStatus(). The @Transactional here is narrow: one repository save
 * plus, at most, one insert -- no HTTP calls inside it -- so it does not reopen
 * TransferService's "the saga itself must not be @Transactional" rule; see
 * docs/phase-2-transfer-service-saga.md's Design Decisions and this phase's own.
 *
 * <p>Also the single place transfers.completed/transfers.failed/transfers.fraud_rejected
 * are counted (Phase 8) -- reusing the same forStatus() terminal/non-terminal
 * classification the outbox write already relies on, rather than a second call site
 * inside the saga itself. See docs/phase-8-observability.md's Design Decisions.
 */
@Service
public class TransferSaveService {

    private final TransferRepository transferRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Counter transfersCompleted;
    private final Counter transfersFailed;
    private final Counter transfersFraudRejected;

    public TransferSaveService(TransferRepository transferRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.transfersCompleted = meterRegistry.counter("transfers.completed");
        this.transfersFailed = meterRegistry.counter("transfers.failed");
        this.transfersFraudRejected = meterRegistry.counter("transfers.fraud_rejected");
    }

    @Transactional
    public Transfer save(Transfer transfer) {
        Transfer saved = transferRepository.save(transfer);
        OutboxEventType eventType = OutboxEventType.forStatus(saved.getStatus());
        if (eventType != null) {
            outboxEventRepository.save(new OutboxEvent(saved.getId(), eventType, toPayload(saved)));
            recordMetric(eventType, saved.getFailureCode());
        }
        return saved;
    }

    private void recordMetric(OutboxEventType eventType, TransferFailureCode failureCode) {
        switch (eventType) {
            case TRANSFER_COMPLETED -> transfersCompleted.increment();
            case TRANSFER_FAILED -> {
                transfersFailed.increment();
                if (failureCode == TransferFailureCode.SOURCE_ACCOUNT_BLOCKED
                        || failureCode == TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED) {
                    transfersFraudRejected.increment();
                }
            }
        }
    }

    private String toPayload(Transfer transfer) {
        try {
            return objectMapper.writeValueAsString(new TransferEventPayload(
                    transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                    transfer.getAmount(), transfer.getStatus(), transfer.getFailureCode(),
                    transfer.getFailureReason(), transfer.getSettledAt()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(
                    "Failed to serialize outbox payload for transfer " + transfer.getId(), impossible);
        }
    }

    private record TransferEventPayload(UUID transferId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                                         TransferStatus status, TransferFailureCode failureCode,
                                         String failureReason, Instant settledAt) {
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -pl transfer-service -am test -Dtest=TransferSaveServiceTest`
Expected: PASS, all 8 tests (3 existing + 5 new) green.

- [ ] **Step 7: Permit `/actuator/prometheus` in every service's `SecurityConfig`**

**Also not in this plan's original draft — found the same way as Step 2's fix, by actually running a request against the endpoint.** `account-service`, `transfer-service`, `fraud-service`, and `gateway-service` all have Spring Security + OAuth2 Resource Server (Phase 7); each `SecurityConfig` permits only `/actuator/health/**` without a JWT, so `/actuator/prometheus` falls through to `anyRequest().authenticated()` and returns 401. Prometheus's plain HTTP scrape has no way to attach a JWT, so this must be a `permitAll()` matcher, the same posture as the existing health matcher (`notification-service` has no `SecurityConfig` at all, so it needs no change here).

In each of the 4 `SecurityConfig` classes, change:
```java
                        .requestMatchers("/actuator/health/**").permitAll()
```
to:
```java
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
```

- [ ] **Step 8: Write and run a Prometheus-exposure IT**

Closes the coverage this phase's own Testing section promises ("a lightweight test per service... asserting the service's own business metric names appear in the scrape output") — Step 3-6 above prove the counter *logic* against a hand-built `SimpleMeterRegistry`, not that the real Spring-autoconfigured `PrometheusMeterRegistry` actually surfaces them on `/actuator/prometheus`. This also pins down the real Prometheus metric names (Micrometer's dot-to-underscore, `_total`-suffix-on-counters convention) that Task 5's dashboard JSON currently flags as unverified.

```java
// transfer-service/src/test/java/com/showcase/transfer/service/PrometheusExposureIT.java
package com.showcase.transfer.service;

import com.showcase.transfer.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PrometheusExposureIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransferSaveService transferSaveService;
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void businessCountersAndOutboxGaugeAppearOnThePrometheusScrapeEndpoint() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        transferSaveService.save(transfer);

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("transfers_completed_total");
        assertThat(response.getBody()).contains("transfer_outbox_backlog");
    }
}
```

Run: `./mvnw -pl transfer-service -am test -Dtest=PrometheusExposureIT`
Expected: PASS. `Transfer`'s `fromAccountId`/`toAccountId` are arbitrary UUIDs with no real Account row behind them — fine here, `Transfer` has no foreign-key constraint to Account (database-per-service; `TransferRepositoryTest` already inserts rows the same way). If either `assertThat(...).contains(...)` fails, read the actual response body to find the real metric name Micrometer produced rather than guessing — that real name is what Task 5's dashboard JSON must use.

- [ ] **Step 9: Create `docker/prometheus/prometheus.yml`**

```yaml
global:
  scrape_interval: 10s

scrape_configs:
  - job_name: account-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["account-service:8081"]
  - job_name: transfer-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["transfer-service:8082"]
  - job_name: notification-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["notification-service:8083"]
  - job_name: fraud-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["fraud-service:8084"]
  - job_name: gateway-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["gateway-service:8080"]
```

- [ ] **Step 10: Add `prometheus` to `docker-compose.yml`**

```yaml
  prometheus:
    image: prom/prometheus:latest
    container_name: showcase-prometheus
    volumes:
      - ./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:9090/-/healthy"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 15s
```

No `command:` override needed — the image's default command already points at `/etc/prometheus/prometheus.yml` (confirmed via `docker inspect prom/prometheus:latest` during planning), which is exactly where the volume mount above places this config. The healthcheck uses exec-form `wget` (not `CMD-SHELL`) since this image was confirmed to have `/bin/wget` but no shell to run a `CMD-SHELL` pipeline in.

- [ ] **Step 11: Commit**

```bash
git checkout -b feature/phase-8-task-3-metrics
git add account-service/pom.xml transfer-service/pom.xml notification-service/pom.xml fraud-service/pom.xml gateway-service/pom.xml \
        account-service/src/main/resources/application.yml transfer-service/src/main/resources/application.yml \
        notification-service/src/main/resources/application.yml fraud-service/src/main/resources/application.yml \
        gateway-service/src/main/resources/application.yml \
        account-service/src/main/java/com/showcase/account/config/SecurityConfig.java \
        transfer-service/src/main/java/com/showcase/transfer/config/SecurityConfig.java \
        fraud-service/src/main/java/com/showcase/fraud/config/SecurityConfig.java \
        gateway-service/src/main/java/com/showcase/gateway/config/SecurityConfig.java \
        transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java \
        transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java \
        transfer-service/src/test/java/com/showcase/transfer/service/PrometheusExposureIT.java \
        docker/prometheus/prometheus.yml docker-compose.yml
git commit -m "feat(observability): Prometheus metrics + business counters (Phase 8, Task 3)"
```

---

### Task 4: Structured JSON logging

**Files:**
- Modify: `account-service/src/main/resources/application.yml`, `transfer-service/src/main/resources/application.yml`, `notification-service/src/main/resources/application.yml`, `fraud-service/src/main/resources/application.yml`, `gateway-service/src/main/resources/application.yml`
- Test: `transfer-service/src/test/java/com/showcase/transfer/StructuredLoggingIT.java`

**Interfaces:** none — a logging-format change only.

- [ ] **Step 1: Write the failing test — `StructuredLoggingIT` (transfer-service)**

Captures stdout during a real logging call and asserts it parses as JSON with `traceId`/`spanId` keys — proves the MDC-to-JSON wiring actually works end to end (tracing bridge from Task 2 + this task's logging format together), not just that the property is set.

```java
// transfer-service/src/test/java/com/showcase/transfer/StructuredLoggingIT.java
package com.showcase.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingIT.class);

    @Autowired
    private Tracer tracer;

    @Test
    void logLineInsideASpanIsJsonWithTraceContext(CapturedOutput output) throws IOException {
        io.micrometer.tracing.Span span = tracer.nextSpan().name("test-span").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            log.info("structured logging probe");
        } finally {
            span.end();
        }

        ObjectMapper mapper = new ObjectMapper();
        String jsonLine = Arrays.stream(output.getOut().split("\\R"))
                .filter(line -> line.contains("structured logging probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No captured log line contains the probe message"));

        JsonNode node = mapper.readTree(jsonLine);
        assertThat(node.has("traceId")).isTrue();
        assertThat(node.has("spanId")).isTrue();
        assertThat(node.get("traceId").asText()).isNotBlank();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl transfer-service -am test -Dtest=StructuredLoggingIT`
Expected: FAIL — `mapper.readTree(jsonLine)` throws, since the captured log line is plain text, not JSON (Boot's default console format).

- [ ] **Step 3: Add the structured logging property to all 5 services**

Add to each of the 5 services' `application.yml`, at the top level (alongside `server:`/`spring:`/`management:`):

```yaml
logging:
  structured:
    format:
      console: logstash
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl transfer-service -am test -Dtest=StructuredLoggingIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git checkout -b feature/phase-8-task-4-structured-logging
git add account-service/src/main/resources/application.yml transfer-service/src/main/resources/application.yml \
        notification-service/src/main/resources/application.yml fraud-service/src/main/resources/application.yml \
        gateway-service/src/main/resources/application.yml \
        transfer-service/src/test/java/com/showcase/transfer/StructuredLoggingIT.java
git commit -m "feat(observability): structured JSON logging with trace context (Phase 8, Task 4)"
```

---

### Task 5: Grafana — provisioned datasources + dashboards

**Files:**
- Create: `docker/grafana/provisioning/datasources/datasources.yml`
- Create: `docker/grafana/provisioning/dashboards/dashboards.yml`
- Create: `docker/grafana/dashboards/jvm-micrometer.json`
- Create: `docker/grafana/dashboards/business-metrics.json`
- Modify: `docker-compose.yml`

**Interfaces:** none new — this task only consumes the Prometheus/Tempo endpoints Tasks 2–3 already exposed.

- [ ] **Step 1: Create `docker/grafana/provisioning/datasources/datasources.yml`**

Verified live during planning (`docker run` against `grafana/grafana:latest`, v13.2.2): both datasources load with no errors — log confirms `"inserting datasource from configuration" name=Prometheus` and `name=Tempo`.

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
  - name: Tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    editable: false
```

- [ ] **Step 2: Create `docker/grafana/provisioning/dashboards/dashboards.yml`**

Verified live during planning alongside Step 1 — log confirms `"finished to provision dashboards"` with no errors. **The `options.path` below was corrected during Task 5's actual implementation** (see Step 5's note) — planning's path nested the dashboards mount inside the already-`:ro`-mounted `provisioning/` directory, which Docker refuses to create a second mountpoint under.

```yaml
apiVersion: 1

providers:
  - name: default
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /etc/grafana/dashboards-data
```

- [ ] **Step 3: Download and adapt the community JVM/Micrometer dashboard**

Verified live during planning: this exact URL resolves and returns a real dashboard JSON (grafana.com dashboard id 4701, "JVM (Micrometer)", revision 9).

```bash
curl -s "https://grafana.com/api/dashboards/4701/revisions/9/download" -o docker/grafana/dashboards/jvm-micrometer.json
```

The downloaded JSON references its datasource as the template variable `${DS_PROMETHEUS}` (34 occurrences, confirmed by counting during planning) — file-based provisioning does not get the interactive "map inputs" step the Grafana UI's import wizard would normally offer, so this must be replaced with the literal datasource name from Step 1 (`Prometheus`):

```bash
sed -i 's/\${DS_PROMETHEUS}/Prometheus/g' docker/grafana/dashboards/jvm-micrometer.json
```

Verify the replacement worked: `grep -c '${DS_PROMETHEUS}' docker/grafana/dashboards/jvm-micrometer.json` should print `0`.

- [ ] **Step 4: Create `docker/grafana/dashboards/business-metrics.json`**

A minimal hand-authored dashboard for this project's own metrics — five panels: the three `TransferSaveService` counters (Task 3), the pre-existing `transfer.outbox.backlog` gauge, and a circuit-breaker-state panel driven by Resilience4j's auto-exposed metrics.

```json
{
  "title": "Microservices Showcase — Business Metrics",
  "uid": "showcase-business-metrics",
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "10s",
  "time": { "from": "now-15m", "to": "now" },
  "panels": [
    {
      "id": 1,
      "title": "Transfers Completed",
      "type": "stat",
      "gridPos": { "h": 6, "w": 6, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "transfers_completed_total", "refId": "A" }
      ]
    },
    {
      "id": 2,
      "title": "Transfers Failed",
      "type": "stat",
      "gridPos": { "h": 6, "w": 6, "x": 6, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "transfers_failed_total", "refId": "A" }
      ]
    },
    {
      "id": 3,
      "title": "Transfers Fraud-Rejected",
      "type": "stat",
      "gridPos": { "h": 6, "w": 6, "x": 12, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "transfers_fraud_rejected_total", "refId": "A" }
      ]
    },
    {
      "id": 4,
      "title": "Outbox Backlog",
      "type": "stat",
      "gridPos": { "h": 6, "w": 6, "x": 18, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "transfer_outbox_backlog", "refId": "A" }
      ]
    },
    {
      "id": 5,
      "title": "Circuit Breaker State (accountService / fraudService)",
      "type": "timeseries",
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 6 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "resilience4j_circuitbreaker_state", "legendFormat": "{{name}} - {{state}}", "refId": "A" }
      ]
    }
  ]
}
```

`transfers_completed_total` and `transfer_outbox_backlog` are already confirmed real by Task 3's `PrometheusExposureIT`. `transfers_failed_total`, `transfers_fraud_rejected_total`, and `resilience4j_circuitbreaker_state` follow the same Micrometer naming convention but were not directly asserted by that test — confirm them against the real `/actuator/prometheus` output during this task's live verification (Task 6, Step 4) and fix the `expr` fields here if any differ, rather than treating them as certain.

- [ ] **Step 5: Add `grafana` to `docker-compose.yml`**

```yaml
  grafana:
    image: grafana/grafana:latest
    container_name: showcase-grafana
    environment:
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
    volumes:
      - ./docker/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./docker/grafana/dashboards:/etc/grafana/dashboards-data:ro
    ports:
      - "3001:3000"
    depends_on:
      prometheus:
        condition: service_healthy
      tempo:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000/api/health"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s
```

`tempo`'s condition is `service_started`, not `service_healthy` — Tempo has no healthcheck (see Task 2's note), so `service_healthy` would never resolve. `GF_AUTH_ANONYMOUS_ENABLED` is set so the dashboards are viewable without a login prompt for local demo purposes — this project has no other auth-gated UI (Keycloak's admin console aside), and Grafana's own auth is unrelated to this project's Keycloak realm. Port mapped to `3001` on the host, not `3000` — `3000` is a common local-dev collision (several frontend dev servers default to it); the container's own internal port stays `3000`, only the host mapping changes.

**The dashboards volume target is `/etc/grafana/dashboards-data`, not `/etc/grafana/provisioning/dashboards-data` as an earlier draft of this step had it — found live, not by inspection.** Docker refused to start the container: `failed to create task for container: ... error mounting ".../docker/grafana/dashboards" to rootfs at "/etc/grafana/provisioning/dashboards-data": ... read-only file system`. The `provisioning` volume above is already mounted `:ro` as a whole directory; Docker cannot create a second mountpoint *underneath* an already-mounted read-only bind mount. Moving the dashboards mount to a sibling path outside `provisioning/` (and updating `dashboards.yml`'s `options.path` to match — Step 2 above already reflects this) avoids the nested-mount conflict entirely. Verified live afterward: all 3 containers (`prometheus`, `tempo`, `grafana`) start together, Grafana reports `healthy`, and `GET /api/search` on the running container returns both dashboards by title.

- [ ] **Step 6: Commit**

```bash
git checkout -b feature/phase-8-task-5-grafana
git add docker/grafana docker-compose.yml
git commit -m "feat(observability): provisioned Grafana dashboards (Phase 8, Task 5)"
```

---

### Task 6: Docs sync + full live verification

**Files:**
- Modify: `docs/microservices-showcase-design.md`
- Modify: `README.md`

**Interfaces:** none — this task wires and documents Tasks 1–5's already-tested code; no new production interfaces.

- [ ] **Step 1: Update `docs/microservices-showcase-design.md` §5**

Find the line:
```
- **Observability:** Micrometer Tracing bridged to OpenTelemetry → OTel Collector → Jaeger (or Grafana Tempo); Micrometer + Prometheus (`/actuator/prometheus`) scraped by Prometheus, visualized in Grafana; structured JSON logs with `traceId`/`spanId` auto-injected via MDC. No centralized log aggregation (ELK/Loki) — out of scope.
```
Change `Jaeger (or Grafana Tempo)` to `Grafana Tempo`.

- [ ] **Step 2: Remove the Spring Cloud Contract line from §8**

Find and delete the line:
```
- **Cross-service contract tests** (Spring Cloud Contract): Transfer's synchronous dependency on Account's and Fraud's APIs is covered by consumer-driven contracts, so a breaking API change fails in the *producing* service's own build.
```
Deleted entirely, not replaced with a "deferred" note — dropped from the roadmap at the user's explicit request during Phase 8 brainstorming, not a scope cut made lightly.

- [ ] **Step 3: Update `README.md`**

Add a new section documenting the observability stack (mirroring the existing "API Gateway" section's style): what's now running (Grafana on `:3001`, Prometheus on `:9090`, Tempo on `:3200`), and a one-line pointer to trigger a transfer and watch it in Grafana.

- [ ] **Step 4: Full live verification**

```bash
docker compose down -v
docker compose up --build
```

Wait for all containers healthy (note: `tempo` and `otel-collector` show no health status at all — expected, see Task 2 — verify they're simply `Up`, not `Exited`), then:

- Trigger a transfer through the Gateway (see README's existing curl examples); confirm a single trace in Tempo (via Grafana Explore, `http://localhost:3001`) spans Gateway → Transfer → Account → Fraud → the Kafka hop → Notification. **This step is exactly what caught a real gap during actual implementation**: the Gateway→Transfer→Account→Fraud portion traced as one connected trace, but Notification's Kafka-consumer span came back as a completely separate, unlinked trace (different trace ID, no parent, no span link) — confirmed via Tempo's API, not just eyeballing the UI. Root cause: `OutboxPublisher` sends from a `@Scheduled` polling thread, seconds after the original request's span has already ended, so Spring Kafka's observation instrumentation has no active span to propagate from and starts a fresh trace instead. This is a structural consequence of Phase 4's transactional-outbox pattern (which deliberately decouples publish-time from request-time for reliability) colliding with this phase's tracing goal — not a config mistake. Fixed in Task 7, added specifically because of this finding.
- Check Prometheus's Targets page (`http://localhost:9090/targets`) — all 5 services `UP`.
- Open the two provisioned Grafana dashboards — confirm the JVM panel and the business-metrics dashboard both populate with real data, not "No data." If any business-metric panel shows "No data," check the actual metric name against `/actuator/prometheus` directly (`curl http://localhost:8082/actuator/prometheus | grep transfers_`) and fix the dashboard JSON's `expr` to match — per Task 5's note, those names were not verified against a live system while writing this plan.
- Pull a service's container logs (`docker compose logs transfer-service`) and confirm lines are JSON with a `traceId` matching the trace ID shown in Tempo for that request.
- Force a circuit-breaker trip (stop Account Service mid-run, as phase 3's testing already does) and confirm the circuit-breaker-state panel reflects the transition.

- [ ] **Step 5: Commit**

```bash
git checkout -b feature/phase-8-task-6-docs-sync
git add docs/microservices-showcase-design.md README.md
git commit -m "docs: sync design doc with Phase 8's Tempo/no-contract-tests decisions (Phase 8, Task 6)"
```

---

### Task 7: Carry trace context across the outbox's async gap

**Added mid-phase, not in the original plan.** Task 6's own live-verification step (above) found that the design doc's and this phase's stated goal — "one continuous trace spanning Gateway → Transfer → Account → Fraud → the Kafka hop → Notification" — did not actually hold: the Kafka-consumer-side trace came back completely disconnected from the original request's trace (different trace ID, `parentSpanId: None`, no span links — confirmed via Tempo's `/api/traces/{id}` directly, not eyeballing the UI). The user chose to fix this now rather than defer it.

**Root cause:** `TransferSaveService.save()` writes the `OutboxEvent` row inside the original HTTP request's span. `OutboxPublisher.publishPending()` sends the Kafka message later, on a `@Scheduled` polling thread, by which point that span has long since ended — there is no active span for Spring Kafka's observation instrumentation (`spring.kafka.template.observation-enabled`, added in Task 2) to propagate from, so it starts a brand-new root trace instead.

**Fix:** persist the request's trace context on the `OutboxEvent` row at write time, then reconstruct it as the parent of a new span on the scheduler thread immediately before calling `kafkaTemplate.send()`. Spring Kafka's observation instrumentation then creates its own send-span as a proper *child* of that reconstructed parent, and injects continuation headers automatically — no manual Kafka header manipulation needed. (Getting the instrumentation itself actually active *did* need one producer config fix, found during implementation — see Step 11.)

**Files:**
- Modify: `transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEvent.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisher.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/config/KafkaProducerConfig.java`
- Modify: `transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java`
- Modify: `transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherTest.java`
- Modify: `transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherIT.java`
- Modify: `account-service/src/main/resources/application.yml`, `transfer-service/src/main/resources/application.yml`, `notification-service/src/main/resources/application.yml`, `fraud-service/src/main/resources/application.yml`, `gateway-service/src/main/resources/application.yml` (add `management.tracing.enabled: true`, explicit — see Step 11's second finding)

**Interfaces:**
- `OutboxEvent` gains a second constructor: `OutboxEvent(UUID transferId, OutboxEventType eventType, String payload, String traceId, String spanId)`. The existing 3-arg constructor is kept (delegates to the new one with `null, null`) — none of the 9 existing call sites across `OutboxEventRepositoryTest`/`OutboxEventTest`/`OutboxPublisherIT`/`OutboxPublisherTest` need to change.
- `TransferSaveService`'s constructor gains a `Tracer` parameter (5th arg, after `MeterRegistry`).
- `OutboxPublisher`'s constructor gains a `Tracer` parameter (5th arg, after `MeterRegistry`).

Verified against the actually-resolved `micrometer-tracing:1.5.12` jar via `javap` before writing any code (same discipline as phase 6's Gateway API verification) — `Tracer.traceContextBuilder()`, `TraceContext.Builder.traceId(String)/.spanId(String)/.sampled(Boolean)/.build()`, `Tracer.spanBuilder()`, `Span.Builder.setParent(TraceContext)`, and `Tracer.NOOP` all confirmed to exist with these exact signatures.

- [ ] **Step 1: Add `traceId`/`spanId` columns and the new constructor to `OutboxEvent`**

```java
// transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEvent.java
package com.showcase.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_unpublished", columnList = "publishedAt, createdAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private OutboxEventType eventType;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    // Nullable -- rows written before this column existed have none, and there is no
    // backfill (same "no backfill for pre-existing rows" precedent as Phase 7b's
    // ownerId/initiatorId). W3C format: traceId is 32 hex chars, spanId is 16 -- lengths
    // match real captured values from live verification, not a guess.
    @Column(updatable = false, length = 32)
    private String traceId;

    @Column(updatable = false, length = 16)
    private String spanId;

    public OutboxEvent(UUID transferId, OutboxEventType eventType, String payload) {
        this(transferId, eventType, payload, null, null);
    }

    public OutboxEvent(UUID transferId, OutboxEventType eventType, String payload, String traceId, String spanId) {
        this.transferId = transferId;
        this.eventType = eventType;
        this.payload = payload;
        this.traceId = traceId;
        this.spanId = spanId;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
```

`ddl-auto: update` picks up the two new nullable columns automatically — no `docker compose down -v` needed for this one (unlike Phase 7b's `NOT NULL` columns), since nullable columns over existing rows are never a Hibernate problem.

- [ ] **Step 2: Run the full test suite to verify it still passes**

Run: `./mvnw -pl transfer-service -am test`
Expected: PASS — the new constructor is additive; nothing existing changed behavior.

- [ ] **Step 3: Write the failing test — `TransferSaveService` captures trace context**

Add to `transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java`:

```java
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
// ... (existing imports unchanged)

@ExtendWith(MockitoExtension.class)
class TransferSaveServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private Tracer tracer;
    @Mock
    private Span span;
    @Mock
    private TraceContext traceContext;

    private SimpleMeterRegistry meterRegistry;
    private TransferSaveService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        service = new TransferSaveService(transferRepository, outboxEventRepository, objectMapper, meterRegistry, tracer);
    }

    // ... (existing tests unchanged -- none of them stub tracer.currentSpan(), so it
    // returns Mockito's default null, exercised by the null-safety branch added in Step 4)

    @Test
    void capturesTraceContextOnTheOutboxRowWhenASpanIsActive() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        when(traceContext.spanId()).thenReturn("b7ad6b7169203331");
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(captor.getValue().getSpanId()).isEqualTo("b7ad6b7169203331");
    }

    @Test
    void leavesTraceContextNullWhenNoSpanIsActive() {
        when(tracer.currentSpan()).thenReturn(null);
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceId()).isNull();
        assertThat(captor.getValue().getSpanId()).isNull();
    }
}
```

Also add `import com.showcase.transfer.domain.OutboxEvent;` and `import org.mockito.ArgumentCaptor;` to the existing import list.

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./mvnw -pl transfer-service -am test -Dtest=TransferSaveServiceTest`
Expected: compile failure — `TransferSaveService`'s constructor doesn't accept a 5th `Tracer` argument yet.

- [ ] **Step 5: Capture trace context in `TransferSaveService`**

Modify `transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java`: add the `Tracer` field/constructor parameter, and capture it when building the `OutboxEvent`.

```java
// Added imports
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

// Field
private final Tracer tracer;

// Constructor
public TransferSaveService(TransferRepository transferRepository,
                              OutboxEventRepository outboxEventRepository,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry,
                              Tracer tracer) {
    this.transferRepository = transferRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
    this.transfersCompleted = meterRegistry.counter("transfers.completed");
    this.transfersFailed = meterRegistry.counter("transfers.failed");
    this.transfersFraudRejected = meterRegistry.counter("transfers.fraud_rejected");
    this.tracer = tracer;
}

// save() -- only the OutboxEvent construction line changes:
@Transactional
public Transfer save(Transfer transfer) {
    Transfer saved = transferRepository.save(transfer);
    OutboxEventType eventType = OutboxEventType.forStatus(saved.getStatus());
    if (eventType != null) {
        Span currentSpan = tracer.currentSpan();
        String traceId = currentSpan != null ? currentSpan.context().traceId() : null;
        String spanId = currentSpan != null ? currentSpan.context().spanId() : null;
        outboxEventRepository.save(new OutboxEvent(saved.getId(), eventType, toPayload(saved), traceId, spanId));
        recordMetric(eventType, saved.getFailureCode());
    }
    return saved;
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -pl transfer-service -am test -Dtest=TransferSaveServiceTest`
Expected: PASS, all 10 tests (8 from Task 3 + 2 new) green.

- [ ] **Step 7: Write the failing test — `OutboxPublisher` continues the trace on send**

Add to `transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherTest.java`:

```java
import io.micrometer.tracing.Tracer;
// ... (existing imports unchanged)

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        OutboxPublisherProperties properties = new OutboxPublisherProperties(
                Duration.ofSeconds(5), 500, Duration.ofSeconds(5), null);
        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, properties,
                new SimpleMeterRegistry(), Tracer.NOOP);
    }

    // ... (existing 3 tests unchanged -- their event() helper uses the 3-arg OutboxEvent
    // constructor, so traceId/spanId are null and the new parenting logic's null-check
    // branch is what they exercise; Tracer.NOOP never gets called in that branch)
}
```

(Only `setUp()`'s constructor call changes — `Tracer.NOOP` as the 5th argument. The existing 3 tests need no other change, per the Interfaces note above.)

Add a new test method to the same class:

```java
    @Test
    void publishingAnEventWithTraceContextStartsASpanParentedToIt() {
        Tracer tracer = mock(Tracer.class);
        Span parentSpan = mock(Span.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        TraceContext.Builder contextBuilder = mock(TraceContext.Builder.class);
        TraceContext reconstructed = mock(TraceContext.class);

        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate,
                new OutboxPublisherProperties(Duration.ofSeconds(5), 500, Duration.ofSeconds(5), null),
                new SimpleMeterRegistry(), tracer);

        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}",
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(event));
        when(tracer.traceContextBuilder()).thenReturn(contextBuilder);
        when(contextBuilder.traceId("0af7651916cd43dd8448eb211c80319c")).thenReturn(contextBuilder);
        when(contextBuilder.spanId("b7ad6b7169203331")).thenReturn(contextBuilder);
        when(contextBuilder.sampled(true)).thenReturn(contextBuilder);
        when(contextBuilder.build()).thenReturn(reconstructed);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);
        when(spanBuilder.setParent(reconstructed)).thenReturn(spanBuilder);
        when(spanBuilder.name("outbox.publish")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(parentSpan);
        when(tracer.withSpan(parentSpan)).thenReturn(scope);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        verify(tracer).withSpan(parentSpan);
        verify(scope).close();
        verify(parentSpan).end();
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }
```

Add `import io.micrometer.tracing.Span;`, `import io.micrometer.tracing.TraceContext;`, and `import static org.mockito.Mockito.mock;` to the import list.

- [ ] **Step 8: Run the test to verify it fails**

Run: `./mvnw -pl transfer-service -am test -Dtest=OutboxPublisherTest`
Expected: compile failure — `OutboxPublisher`'s constructor doesn't accept a 5th `Tracer` argument yet.

- [ ] **Step 9: Reconstruct and propagate trace context in `OutboxPublisher`**

Modify `transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisher.java`:

```java
// Added imports
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

// Field
private final Tracer tracer;

// Constructor
public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
                        OutboxPublisherProperties properties, MeterRegistry meterRegistry, Tracer tracer) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
    this.tracer = tracer;
    meterRegistry.gauge("transfer.outbox.backlog", outboxEventRepository,
            repository -> (double) repository.countByPublishedAtIsNull());
}
```

Rename the existing `private void publish(OutboxEvent event)` method to `doPublish`, and add a new `publish` wrapper above it:

```java
    private void publish(OutboxEvent event) {
        if (event.getTraceId() == null || event.getSpanId() == null) {
            // No trace context captured at write time (e.g. a row from before this
            // column existed, or the outbox write happened with no active span) --
            // fall through to a normal send, which still gets its own fresh trace via
            // Spring Kafka's observation instrumentation, just not linked to anything.
            doPublish(event);
            return;
        }
        TraceContext parentContext = tracer.traceContextBuilder()
                .traceId(event.getTraceId())
                .spanId(event.getSpanId())
                .sampled(true)
                .build();
        Span span = tracer.spanBuilder().setParent(parentContext).name("outbox.publish").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            doPublish(event);
        } finally {
            span.end();
        }
    }

    private void doPublish(OutboxEvent event) {
        // ... existing publish() method body, unchanged, just renamed
    }
```

`publishPending()`'s call to `publish(event)` (inside its per-row try/catch) is unchanged — it already calls the method named `publish`, which now does the parenting first and delegates to `doPublish` for the actual send/error-handling logic that was already there.

- [ ] **Step 10: Run the tests to verify they pass**

Run: `./mvnw -pl transfer-service -am test -Dtest=OutboxPublisherTest`
Expected: PASS, all 4 tests (3 existing + 1 new) green.

- [ ] **Step 11: Add a real-Kafka header assertion to `OutboxPublisherIT`**

Proves the full chain end to end against a real broker: a stored trace context actually produces a `traceparent` Kafka header on the wire, not just that the right mock calls happened in isolation. This step is exactly what caught two more real bugs — neither reachable by a mocked unit test — documented below rather than smoothed over.

Add to `transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherIT.java`:

```java
    @Test
    void publishedRecordCarriesTraceparentHeaderWhenTheOutboxEventHasTraceContext() {
        // TRANSFER_FAILED, not TRANSFER_COMPLETED -- publishesAnUnpublishedEventAndMarksItPublished
        // above already publishes a real record to the TRANSFER_COMPLETED topic in this same
        // class. A fresh consumer group here defaults to reading from the earliest offset, so
        // reusing that topic would pick up that other test's leftover record too and fail with
        // "More than one record for topic found" -- confirmed live by hitting exactly that error
        // before this fix. A different topic sidesteps the collision entirely.
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String spanId = "b7ad6b7169203331";
        String payload = "{\"status\":\"FAILED\"}";
        outboxEventRepository.saveAndFlush(
                new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, payload, traceId, spanId));

        outboxPublisher.publishPending();

        String topic = outboxPublisherProperties.topics().failed();
        var consumerProps = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), "outbox-publisher-it-trace", "true");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(10));

            var traceparentHeader = record.headers().lastHeader("traceparent");
            assertThat(traceparentHeader).isNotNull();
            String traceparent = new String(traceparentHeader.value(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(traceparent).contains(traceId);
        }
    }
```

Also add `import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;`, and put `@AutoConfigureObservability` on the class itself, alongside the existing `@SpringBootTest`/`@Testcontainers`/`@Import` annotations.

**Two real bugs found running this step, neither visible to `OutboxPublisherTest`'s mocks:**

1. **`KafkaProducerConfig`'s manually-constructed `KafkaTemplate` bean silently ignored `spring.kafka.template.observation-enabled` (Task 2) entirely.** That property only customizes Boot's *own* autoconfigured `KafkaTemplate` — since this project already defines its own `@Bean KafkaTemplate<String, String> kafkaTemplate(...)` (predating Phase 8, for its custom producer properties), Boot's autoconfiguration backs off completely and never touches this bean. The real Kafka record carried **zero** headers of any kind (not a wrong name — literally none), confirmed by dumping every header during debugging. Fixed by injecting `ObservationRegistry` into the bean method and calling `setObservationRegistry(...)`/`setObservationEnabled(true)` explicitly:
   ```java
   @Bean
   public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory,
                                                        ObservationRegistry observationRegistry) {
       KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
       template.setObservationRegistry(observationRegistry);
       template.setObservationEnabled(true);
       return template;
   }
   ```
   (in `transfer-service/src/main/java/com/showcase/transfer/config/KafkaProducerConfig.java`, plus `import io.micrometer.observation.ObservationRegistry;`)

2. **`@SpringBootTest` disables tracing/metrics observability by default**, via Spring Boot's own `ObservabilityContextCustomizerFactory` (package `org.springframework.boot.test.autoconfigure.actuate.observability`) — it adds `management.tracing.enabled=false` as a test-only property source that overrides `application.yml` unconditionally, regardless of what's configured there. Confirmed via the Boot condition-evaluation report (`-Ddebug=true`): `OnEnabledTracingCondition` reported `management.tracing.enabled is false` even with that exact property set to `true` in `application.yml`, and even set as a same-JVM **unforced system property** (the highest normal precedence) — neither had any effect, because the test-only override sits above both. This is *why* Task 3's analogous metrics fix (`management.prometheus.metrics.export.enabled`) worked without needing this: that property is the exporter-*specific* key, which Prometheus's own condition checks before falling back to the generic default the test override targets — tracing has no equivalent specific key to sidestep it with. The fix is `@AutoConfigureObservability` on the test class, Spring Boot's own documented opt-back-in mechanism — needed only on `OutboxPublisherIT`, since it's the only test in this phase asserting real wire-level propagation rather than a bean's type (`TracingBridgeIT`) or in-process MDC content (`StructuredLoggingIT`), neither of which this default affects.

Both findings also motivate keeping `management.tracing.enabled: true` explicit in `application.yml` (added alongside `sampling.probability` in Task 2's config) even though it wasn't sufficient on its own to fix this test — explicit is better than relying on an undocumented-to-a-casual-reader non-test default, and it costs nothing in the real running app, where `@SpringBootTest`'s override never applies at all.

- [ ] **Step 12: Run the test to verify it passes**

Run: `./mvnw -pl transfer-service -am test -Dtest=OutboxPublisherIT`
Expected: PASS, all 3 tests — the `traceparent` header on the real consumed Kafka record contains the exact `traceId` this test stored on the `OutboxEvent`, proving the reconstructed span's context actually made it onto the wire via Spring Kafka's own instrumentation.

- [ ] **Step 13: Run the full reactor test suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, all modules, zero failures.

- [ ] **Step 14: Live re-verification — the trace gap Task 6 found is now closed**

```bash
docker compose down -v
docker compose up --build -d
```

Repeat Task 6 Step 4's transfer-through-the-Gateway scenario, then query Tempo directly (`curl "http://localhost:3200/api/traces/<gateway-trace-id>"`, the same way this gap was originally found) and confirm `notification-service`'s `transfer.completed receive` span now appears as part of the *same* trace ID as the Gateway/Transfer/Account/Fraud spans — not a separate one. This is the one thing that must be re-checked against a live system, not just unit/integration tests, since it's exactly the class of gap (structural async-boundary trace propagation) that only showed up under real Kafka timing the first time.

- [ ] **Step 15: Commit**

```bash
git checkout -b feature/phase-8-task-7-outbox-trace-continuity
git add transfer-service/src/main/java/com/showcase/transfer/domain/OutboxEvent.java \
        transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java \
        transfer-service/src/main/java/com/showcase/transfer/service/OutboxPublisher.java \
        transfer-service/src/main/java/com/showcase/transfer/config/KafkaProducerConfig.java \
        transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java \
        transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherTest.java \
        transfer-service/src/test/java/com/showcase/transfer/service/OutboxPublisherIT.java \
        account-service/src/main/resources/application.yml transfer-service/src/main/resources/application.yml \
        notification-service/src/main/resources/application.yml fraud-service/src/main/resources/application.yml \
        gateway-service/src/main/resources/application.yml
git commit -m "fix(observability): carry trace context across the outbox's async gap (Phase 8, Task 7)"
```
