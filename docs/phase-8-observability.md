# Observability Implementation Phase (Phase 8)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire tracing, metrics, and structured logging across all five services so the system is observable the way the design doc describes: one continuous trace spanning Gateway → Transfer → Account/Fraud → (async hop via Kafka headers) → Notification, business + JVM/HTTP metrics on Grafana dashboards, and JSON logs carrying trace/span context. Full `docker compose up` bringing up every service already works (done incidentally during phases 5–7) — this phase adds the observability stack (OTel Collector, Tempo, Prometheus, Grafana) on top of it. Two items in the roadmap's original phase-8 forecast are explicitly not part of this phase: the end-to-end saga test module (split out to **Phase 9**, to be brainstormed separately) and Spring Cloud Contract tests (dropped from the roadmap entirely, not deferred).

**Architecture:** Every service gets the Micrometer Tracing → OTel bridge (REST calls propagate W3C trace context automatically; the Kafka hop propagates it via Spring Kafka's observation instrumentation) exporting OTLP to a new `otel-collector` container, which forwards to a new `tempo` container — Grafana's native trace backend, chosen over Jaeger so traces and metrics dashboards live in one UI. Metrics take a separate, simpler path: each service exposes `/actuator/prometheus` directly, scraped by a new `prometheus` container — no detour through the collector. A new `grafana` container has both data sources provisioned on startup, plus a JVM/HTTP dashboard and one hand-authored dashboard for this project's business metrics. Structured JSON logging uses Spring Boot's native support (added in 3.4), which requires bumping the project off 3.3.8.

**Tech Stack:** Spring Boot bumped from 3.3.8 to **3.5.16** project-wide, via the root `pom.xml`'s parent version (same mechanism as phase 6's 3.3.4→3.3.8 bump) — see Design Decisions for why 3.5.16 despite it already being past OSS end-of-life. `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` + `micrometer-registry-prometheus` added to all five services. New containers: `otel/opentelemetry-collector-contrib`, `grafana/tempo`, `prom/prometheus`, `grafana/grafana` (exact tags verified against Docker Hub at implementation time, not pinned here). No new Java dependency for logging — Boot 3.5's built-in `logging.structured.format.console=logstash` property is sufficient.

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

**Why the outbox-backlog gauge samples at each `OutboxPublisher` poll cycle rather than querying live on every Prometheus scrape.** A `Gauge` backed by a live `OutboxEventRepository.count()` call would run an extra DB query on every scrape (every 10s), independent of whether the outbox actually changed. Binding the gauge to a value `OutboxPublisher` already computes each poll cycle reuses work that's happening anyway.

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
- Business metrics, added at their existing natural source points rather than a separate metrics-collection layer:
  - `transfers.completed` / `transfers.failed` counters, incremented in Transfer Service's `TransferSaveService` choke point (phase 4) at the point it already decides the outbox event type.
  - `transfers.fraud_rejected` counter, incremented where the saga's `FraudClient` call causes a rejection on either leg.
  - `outbox.backlog` gauge, bound to the unpublished-row count `OutboxPublisher` computes each poll cycle (see Design Decisions).
- Resilience4j: confirm at implementation time whether `resilience4j-spring-boot3` already pulls in the Micrometer binder transitively; add `io.github.resilience4j:resilience4j-micrometer` explicitly to transfer-service's pom if not. This is what exposes circuit-breaker state transitions as metrics for a Grafana panel, per the design doc.

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
6. **Docs sync**: roadmap.md (row 8 narrows to this phase's actual scope, new row 9 added for the split-off e2e test module) and design.md (resolve "Jaeger (or Grafana Tempo)" to Tempo, drop the Spring Cloud Contract line entirely) — same pattern as every prior phase's final wiring/docs-sync commit.

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

- `docs/roadmap.md` row 8 narrows at implementation-sync time (Task 6) to link to this document with its actual scope (tracing/metrics/logging, no contract tests, no e2e module); a new row 9 is added, "Not started," forecasting the end-to-end saga test module.
- `docs/microservices-showcase-design.md` §5 gets "Jaeger (or Grafana Tempo)" resolved to "Grafana Tempo"; the Spring Cloud Contract line (§8) is removed entirely, not marked deferred.
