# Spring Boot 4 Migration (Phase 15)

**Goal:** Every module runs on a maintained Spring line. Boot 3.5.16 has been past OSS end of
life since June 30, 2026; Phase 8 stayed on it deliberately and recorded the migration as its own
phase (`phase-8-observability.md` Design Decisions and Scope Boundary, open item #1). This is that
phase.

**Architecture:** Unchanged. Same services, same contracts, same wire formats, same saga. The
phase swaps the platform underneath: Spring Boot 4.0 (Spring Framework 7, Jakarta EE 11,
Hibernate 7, Spring Security 7, Spring Kafka 4, Jackson 3), and the third-party libraries pinned
outside Boot's BOM move to their Boot 4 lines.

**Origin:** A half-day spike on 2026-11-02 bumped everything and got the full suite green (467
tests, 0 failures). Its findings are below, and its diff is Task 1.

## Global Constraints

- No behaviour change visible to a client: same endpoints, status codes, `code` values, JSON
  shapes and Kafka payloads. A 422 is still a 422; Framework 7 only renames the constant.
- The saga invariant in `CLAUDE.md` holds unchanged. The HTTP client timeouts that decide the
  unknown-outcome path keep their values; only the API that sets them changes.
- Jackson 3 everywhere (`tools.jackson.*`). No Jackson 2 compatibility module: it only defers the
  work, and Boot 4 autoconfigures a Jackson 3 `JsonMapper` alone.
- Per `CLAUDE.md`, every pin outside Boot's BOM is checked by exercising the feature, not by a
  green startup.

## Versions

| Dependency | From | To | Note |
|---|---|---|---|
| Spring Boot (parent) | 3.5.16 | 4.0.9 | Boot 4.1.x is GA, but Spring Cloud for it (2026.0) is still RC |
| Spring Cloud (gateway) | 2025.0.3 | 2025.1.4 | Gateway Server MVC 5.0.4 |
| Resilience4j | `resilience4j-spring-boot3` 2.4.0 | `resilience4j-spring-boot4` 2.4.0 | Same version, new artifact |
| springdoc-openapi | 2.8.17 | 3.0.3 | 3.0 line targets Boot 4.0 |
| datasource-micrometer | 1.5.0 | 2.3.0 | 2.x targets Boot 4 |
| Testcontainers | 1.21.4 | 2.0.5 | Artifacts renamed `testcontainers-*` |

## What has to change

Mechanical package moves are not listed; the compiler finds them. These are the changes that
need knowing, and the first four **fail without a compile error**, so the spec names them:

1. **Kafka autoconfiguration moved to its own module.** A raw `spring-kafka` dependency no longer
   brings Boot's Kafka autoconfiguration (`KafkaProperties`, the listener container factory,
   observation). Transfer and Notification depend on `spring-boot-starter-kafka`.
   `KafkaProperties.buildProducerProperties()` is now no-arg.
2. **Tracing needs the OpenTelemetry starter.** `micrometer-tracing-bridge-otel` +
   `opentelemetry-exporter-otlp` no longer get autoconfigured; without
   `spring-boot-starter-opentelemetry` there is no `Tracer` and no OTel `Resource`, so nothing
   reaches Tempo. Property keys move:
   - `management.tracing.enabled` → `management.tracing.export.enabled`
   - `management.otlp.tracing.endpoint` → `management.opentelemetry.tracing.export.otlp.endpoint`
3. **`RestClient.Builder` needs `spring-boot-starter-restclient`** (Transfer, FX). The webmvc
   starter no longer brings it.
4. **Jackson 3 is mandatory, not a choice.** Jackson 2 still arrives transitively, so
   `com.fasterxml.jackson.databind.ObjectMapper` *compiles*, but no such bean exists at runtime.
   Annotations stay in `com.fasterxml.jackson.annotation`. `JsonProcessingException` becomes the
   unchecked `tools.jackson.core.JacksonException`; `JavaTimeModule` is built in; mappers are
   immutable (configure through `JsonMapper.builder()`). Spring Kafka: `JsonSerializer` /
   `JsonDeserializer` → `JacksonJsonSerializer(JsonMapper)` / `JacksonJsonDeserializer`
   (Notification's dead-letter template and its `spring.deserializer.value.delegate.class`).
   The E2E module's dependency becomes `tools.jackson.core:jackson-databind`.
5. **HTTP client factories:** `ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings…)`
   → `ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
   .withConnectTimeout(…).withReadTimeout(…))` in Transfer's three client configs and FX's
   provider config. Same detection order, same timeouts.
6. **Framework 7:** `HttpStatus.UNPROCESSABLE_ENTITY` → `UNPROCESSABLE_CONTENT` (same 422 on the
   wire, but enum equality fails). The saga clients classify by `is4xxClientError`, so they are
   unaffected. `HttpHeaders` is no longer a `MultiValueMap`: `containsKey` → `containsHeader`.
7. **Starters renamed:** `spring-boot-starter-web` → `-webmvc`, `-aop` → `-aspectj`.
8. **Tests:** Boot 4 splits test support into per-technology modules.
   - `spring-boot-starter-webmvc-test` (`@WebMvcTest`, `@AutoConfigureMockMvc`)
   - `spring-boot-starter-security-test`, or a `@WebMvcTest` slice has no `HttpSecurity`
   - `spring-boot-starter-data-jpa-test` (`@DataJpaTest`, `@AutoConfigureTestDatabase`)
   - `spring-boot-resttestclient`, plus `spring-boot-restclient` test-scoped where main code
     lacks it, and `@AutoConfigureTestRestTemplate` on every class injecting `TestRestTemplate`
   - `spring-boot-micrometer-{tracing,metrics}-test`: `@AutoConfigureObservability` →
     `@AutoConfigureTracing` + `@AutoConfigureMetrics`
   - `@MockBean` → `@MockitoBean`

## Design Decisions

**Boot 4.0, not 4.1.** Spring Cloud's Boot 4.1 release train (2026.0) is still RC, and the
Gateway can't move without it. 4.0.x is maintained; 4.1 is a later minor bump.

**Starters over raw libraries.** Findings 1–3 are all the same trap: in Boot 4 a library on the
classpath no longer implies its autoconfiguration. Depending on the Boot starter is the fix in
each case, and the rule for future dependencies.

## Scope Boundary

| Not in this phase | Why |
|---|---|
| Framework 7 API versioning (`/v1`) | A feature, not a migration; `phase-8b-version-visibility.md` ruled out `/v1` |
| JSpecify null-safety annotations | Optional adoption, no migration need |
| Flyway (`open-items.md`) | Its own phase; Hibernate 7 keeps `ddl-auto: update` working |
| Boot 4.1 | Waits for Spring Cloud 2026.0 GA |

## Delivery

| Task | Content |
|---|---|
| 1 | The spike's diff: version pins, starters, main and test code, Jackson 3, YAML keys. Full `./mvnw test` green |
| 2 | Live verification: rebuild every image, `docker compose up`, traces in Tempo for a transfer (sync + Kafka hop), `/actuator/info` commit, Prometheus scrape, Loki trace links, Bank UI login + transfer; E2E suite (`-Pe2e`) |
| 3 | Deprecation sweep: compile with `-Xlint:deprecation`, move off the deprecated aliases (e.g. `org.testcontainers.containers.PostgreSQLContainer` → `org.testcontainers.postgresql`) |
| 4 | Docs: design doc versions and anything describing Jackson/OTel wiring, `CLAUDE.md` version line, roadmap row, remove open item #1 |

## Testing

- `./mvnw test` (full suite, needs Docker) after each task.
- Task 2 is the gate for findings 1–3: their failure mode is silent, so the live stack is checked
  rather than trusting startup. Tests that already cover them: `TracingBridgeIT`,
  `BuildInfoIT.openTelemetryResourceCarriesServiceVersion`, `OutboxPublisherIT`,
  `NotificationListenerIT`, `ConsumerErrorHandlingIT`, `OpenApiDocsIT`.
- `./mvnw -Pe2e -pl e2e-tests verify` with the dev stack stopped first.

## Implementation Notes (as built)

### Task 1

Landed as specified in "What has to change". Full `./mvnw test`: 467 tests, 0 failures, on the
same versions as the Versions table. Not yet exercised: Docker images, the live stack and E2E
(Task 2).

### Task 2

Live verification on the stack built from Task 1 (`GIT_SHA=433d276`). No code changes were
needed.

- **Startup:** all six services healthy under the unchanged `-Xmx192m` cap (210–300 MiB per
  container). `/actuator/info` reports the commit. Keycloak failed once on the first
  `up --build`, a Postgres connection timeout while image builds held the CPU; a plain `up -d`
  cleared it. That's unrelated to the migration.
- **Tracing (finding 2):** one transfer through the Gateway gives a single Tempo trace of 62
  spans across Gateway, Transfer, Account, Fraud and Notification, including the Kafka hop
  (`outbox.publish` → `transfer.completed process`), each with `service.version`, plus Fraud's
  datasource-micrometer spans. `GET /fx/rates` traces `@Observed` spans, Lettuce `get`/`set`/`eval`,
  and the provider call.
- **Logs:** `tools/check-trace-logs.ps1` finds the transfer's traceId in Loki for all five services.
- **Metrics:** all six Prometheus targets up. Every metric on the project's dashboards exists. The
  11 missing ones are all panels of the stock community `jvm-micrometer.json` (Jetty, Tomcat
  MBean registry, an extra process-memory library) that this project never enabled. They were
  already missing on Boot 3.
- **API contract:** `/v3/api-docs` 200 (OpenAPI 3.1) on Account, Transfer, Fraud and FX. Bank UI
  endpoints (`/accounts/mine`, `/accounts/{id}/summary`, `/transfers/mine`) answer as before, and
  RFC 7807 bodies are unchanged: `SAME_ACCOUNT_TRANSFER` 400, and `INSUFFICIENT_FUNDS` 422 with
  `transferStatus: FAILED`.
- **E2E:** `./mvnw -Pe2e -pl e2e-tests verify`: 6/6, including the Account fault scenarios (the
  breaker opens and recovers; a lost debit stays `PENDING` until the sweep replays it).

Not checked here: a click-through of the Bank UI's Keycloak PKCE login in a browser. The UI and
the realm are unchanged; only the APIs it calls were exercised.

### Task 3

`-Xlint:deprecation,removal` across main, test and E2E code found 84 warnings; none remain:

- `org.testcontainers.containers.PostgreSQLContainer<?>` (34 tests) → `org.testcontainers.postgresql.PostgreSQLContainer`
  (no longer generic).
- `ScheduledTaskRegistrar.addFixedDelayTask(Runnable, long)` → the `Duration` overload in
  `CompensationScheduler` and `OutboxPublisher`. Same zero initial delay, same intervals.
- Jackson 3 `JsonNode.asText()` → `asString()`; MockMvc `isUnprocessableEntity()` →
  `isUnprocessableContent()`; `KafkaTestUtils.consumerProps(…, "true")` → the `boolean` overload.

Two deprecated starters don't show up at compile time:
`spring-boot-starter-oauth2-resource-server` → `spring-boot-starter-security-oauth2-resource-server`,
`spring-boot-starter-oauth2-client` → `spring-boot-starter-security-oauth2-client`.
No YAML key in any service matches one of the 434 deprecated properties in the classpath's
configuration metadata. (The scan does flag the pre-Task 1 tracing keys, so it works.)
Full `./mvnw test`: 467 tests, 0 failures.

### Task 4

Docs brought up to Boot 4: the version line in `CLAUDE.md` (plus a lesson about starters vs raw
libraries under "Executing implementation phases"), `README.md`, design doc §3 (runtime line and
the starters rule), the infographic header (re-rendered with `docs/infographic/render.sh`),
roadmap row marked done, open item #1 removed (the remaining items are renumbered), and Phase 8's
Scope Boundary row now points here. Phase 8's own rationale for staying on 3.5.16 is kept as
history.
