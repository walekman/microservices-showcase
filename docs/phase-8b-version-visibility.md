# Version Visibility Implementation Phase (Phase 8b)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Read this brief critically rather than transcribing it: if something in it is wrong or impossible, report it instead of silently working around it.**

**Goal:** Make "which build is each service running?" answerable in four places from one source of truth — an unauthenticated `/actuator/info` on every service, a Prometheus info metric, the OpenTelemetry `service.version` resource attribute on every span, and a provisioned Grafana "Service Versions" dashboard — and make that version controllable from one line in the root `pom.xml` (overridable by CI with `-Drevision=`).

**Architecture:** The build version comes from a single `<revision>` property in the root `pom.xml`; Spring Boot's `build-info` goal turns it into `META-INF/build-info.properties`, which Boot exposes as a `BuildProperties` bean. Everything else reads that bean or the `info.commit` property (the git SHA, injected as a Docker build arg because `.git` is not in the image build context). Each service publishes a constant-1 `application_info{version,commit}` gauge — the Prometheus "info metric" convention — which the existing Prometheus scrape already collects, and a new Grafana dashboard renders it. No new containers, no new Java dependency.

**Tech Stack:** Spring Boot 3.5.16's `spring-boot-maven-plugin` `build-info` goal (version managed by the Boot parent), Micrometer 1.15.12 (`MeterBinder`, `Gauge`), Boot's `management.opentelemetry.resource-attributes`, Grafana provisioned dashboard JSON. **No new pinned dependency** — see CLAUDE.md's pinned-dependency lesson: nothing here is outside the Boot BOM, so no pin needs re-checking on this phase; the tests exercise the features themselves regardless.

**Spec:** This document (brainstormed with the user on 2026-09-21), `docs/microservices-showcase-design.md` §5 (Observability), and `docs/phase-8-observability.md` (the Prometheus/Grafana/OTel wiring this phase builds on).

## Global Constraints

- Java 21 floor. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- Spring MVC (blocking); no new containers; local deployment stays Docker Compose only.
- `/actuator/info` is **unauthenticated**, like `/actuator/health/**` and `/actuator/prometheus` (user decision, 2026-09-21). Its content is therefore limited to build name/version/time and the commit SHA. Do **not** enable the `java`, `os` or `process` info contributors, and do not add any other `info.*` property.
- The `management.endpoints.web.exposure.include` list becomes exactly `health,info,prometheus` in all five services — never `*`.
- The project's own version literal (`0.1.0-SNAPSHOT`) must appear in exactly one place after Task 1: the `<revision>` property in the root `pom.xml`.
- Never commit directly to `master`; one branch per task, `feature/phase-8b-task-<N>-<short-description>`, cut from a freshly fetched `origin/master` (**local `master` was 19 commits stale when this phase was planned — branch from `origin/master`, not `master`**), one PR per task, stop after each. Subagent review is manual, on request. (CLAUDE.md)
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review; always name the model explicitly; give smaller models the explicit checklists in this document. (CLAUDE.md)
- Sync any review fix back into this document verbatim from the merged source, not by hand. (CLAUDE.md)

## Two Versions, Only One In Scope

A service has a **build version** (what artifact is deployed — `0.1.0-SNAPSHOT`, `1.4.2`, a commit) and an **API/contract version** (what callers may rely on — `v1`). They move independently: a service can ship build 3.4.1 and still serve `v1`. This phase is **build version only**. The OpenAPI `info.version = "v1"` hard-coded in Account/Transfer/Fraud is the contract version, is correct as it stands, and is not touched. See Scope Boundary for what is deliberately left out on the contract side.

## Design Decisions Worth Knowing Before You Start

**What the industry-standard mechanism is, and why this phase follows it.** Every mature stack converges on the same shape: one version source in the build → the artifact carries it → the running process reports it through a machine-readable channel → the metrics system collects it like any other signal → a dashboard renders it. Concretely: (1) a build-time version (SemVer, set by CI from a tag), (2) an embedded build-metadata file (`build-info.properties` / `git.properties` for Spring), (3) a metadata endpoint (`/actuator/info`), (4) an **info metric** — a gauge fixed at `1` whose *labels* carry the metadata: `prometheus_build_info`, `go_build_info`, `kube_pod_info`, `node_uname_info` — and (5) the version as an OpenTelemetry resource attribute (`service.version`, a standard semantic convention). Kubernetes shops get the image tag from kube-state-metrics (`kube_pod_container_info`) and CD tools (Argo CD, Flux) add deployment annotations; at organisation scale a service catalog such as Backstage sits above all of it. This phase implements (1)–(5) at the scale a five-service Compose stack warrants.

**Why a Grafana dashboard over an info metric, and not a custom "version aggregator" service.** The non-standard answer is a bespoke service that polls every `/info` endpoint and serves a page: it re-implements service discovery, scrape timing and staleness handling that Prometheus already does, and adds a sixth deployable to a five-service demo. Prometheus already scrapes all five services every 10 s, so the version data needs no new plumbing — only a metric to scrape and a dashboard to draw it. `/actuator/info` stays as the human/`curl` path; the two agree because both read the same `BuildProperties` bean and `info.commit` property.

**Why an info metric and not a `version` common tag on every metric.** `management.metrics.tags.version=...` is one line and tempting, but it stamps the version onto *every* series, so each deploy re-labels every series in the system — old and new series never join, and `rate()`/`increase()` across a deploy boundary lose data. An info metric confines the churn to one series per service. This is the reason the Prometheus ecosystem uses `*_info` gauges.

**Why the `_info` suffix is safe here (verified, not assumed).** Newer Prometheus client libraries reserve `_info` for the OpenMetrics `Info` type, so a gauge named `application.info` was a real risk. Probed against this project's exact Micrometer 1.15.12 / `prometheus-metrics-*` 1.3.10 during planning: `Gauge.builder("application.info", ...)` registers without error and scrapes as `application_info{commit="abc1234",version="0.1.0-SNAPSHOT"} 1`. Note the value renders as `1`, not `1.0` — tests therefore assert the *labels*, never the value text.

**Why the gauge needs `.strongReference(true)`.** Micrometer gauges hold their state object weakly. A capturing or otherwise collectable supplier can be garbage-collected and the gauge silently goes `NaN`. `() -> 1` happens to be a cached non-capturing lambda today, but that is an accident of the JVM, not a guarantee — `strongReference(true)` makes it explicit.

**Why the commit SHA is a Docker build arg and not `git-commit-id-maven-plugin` / `git.properties`.** `git.properties` is the standard answer for non-container builds, but the Dockerfiles build inside the image from an explicit `COPY` list — `.git` is not in the build context, and adding it would bust the Maven layer cache on every commit. So CI/compose passes `GIT_SHA` as a build arg (the standard container-build answer). The `ARG`/`ENV`/`LABEL` sit in the **final stage after the jar `COPY`**, so a changing SHA invalidates only the last few cheap layers, never the Maven build. The cost: a service run outside Docker reports `commit: unknown`. Compose defaults the arg to `unknown` — **not** a plausible-looking `dev` — so a forgotten `GIT_SHA` is visibly wrong on the dashboard rather than silently misleading.

**Why one `<revision>` property instead of `maven-release-plugin`.** Six poms hard-coded `0.1.0-SNAPSHOT`. Maven's CI-friendly `${revision}` (Maven ≥ 3.5; the wrapper pins 3.9.9) collapses that to one property that CI overrides with `-Drevision=1.2.3`, with no plugin. `maven-release-plugin` also commits, tags and bumps SNAPSHOTs — machinery for publishing to an artifact repository this project does not have. Verified during planning: `-Drevision=9.9.9` yields `fraud-service-9.9.9.jar` with `build.version=9.9.9`, and the Dockerfiles' `target/<svc>-*.jar` glob is unaffected.

**Why `management.opentelemetry.resource-attributes` and not a custom `Resource` bean.** Boot 3.5.16 ships it (confirmed in the actuator-autoconfigure metadata) and it merges into the same `Resource` the OTLP exporter uses. The quoted key `"service.version"` preserves the dot. The value is `@project.version@` — Boot's starter-parent filters `application*.yml` with the `@` delimiter, so it resolves to the pom version at build time with no Java. (`${...}` placeholders elsewhere in the ymls are untouched by that filtering; the only literal `@` in any yml is inside a comment at `transfer-service/.../application.yml:117`.)

**Why the binder code is copied five times.** There is no shared module — each service is deliberately independent (database-per-service, five self-contained poms), and `SecurityConfig`, `JwtDecoderConfig` and `KeycloakProperties` are already per-service copies. Introducing a shared library for a 20-line class would be a bigger architectural decision than this phase should make. The five copies differ only in their `package` line.

**Notification is different in two ways.** It has no Spring Security, so its actuator endpoints are already unauthenticated and it needs no `SecurityConfig` edit. It also has a flat package layout (`NotificationListener` and the application class sit in the root package, no `config` package) — Task 3 creates `com.showcase.notification.config` so the `BuildInfoMetricsConfig` copy stays identical to the other four.

**Gateway's `/actuator/info` reports the gateway's own build.** Gateway is routing-only and has no route for `/actuator/**`, so `http://localhost:8080/actuator/info` is Gateway's, not a downstream service's. The other services are reached on their own host ports (8081–8084).

## Verified During Planning

All of the below was run in a throwaway git worktree at `origin/master` (`6e043a5`) — the worktree was deleted afterwards and this branch carries none of that code. Results:

| Claim | Result |
|---|---|
| `<revision>` in root + `${revision}` in all six poms resolves across the reactor | ✅ Built and tested |
| `-Drevision=9.9.9 package` | ✅ `fraud-service-9.9.9.jar`, `build.version=9.9.9` |
| `build-info` goal writes `META-INF/build-info.properties` | ✅ `artifact`, `group`, `name`, `time`, `version` |
| `@project.version@` is filtered into `application.yml` | ✅ `"service.version": "0.1.0-SNAPSHOT"` in `target/classes/application.yml` |
| `/actuator/info` reachable with no token after adding the `permitAll` | ✅ 200 with `build.version` and `commit` |
| `Resource` bean carries `service.version` (and `service.name`) | ✅ `0.1.0-SNAPSHOT` / `fraud-service` |
| `application.info` gauge scrapes as `application_info{commit=…,version=…} 1` | ✅ through the real `/actuator/prometheus` endpoint |
| `BuildInfoIT` (all three tests below) | ✅ **3/3 green on Fraud and on Gateway** |

**Not verified during planning** — the implementer must treat these as unproven: Account, Transfer and Notification runs (the edits are mechanical copies, and their Testcontainers scaffolding is copied from those modules' existing ITs, but none was run); everything Docker-side (build args, image labels, the running stack); Prometheus and Grafana behaviour with the new metric/dashboard (Tasks 3–4 verify these live); and whether `service.version` actually reaches Tempo (the IT asserts the `Resource` bean, the exporter's input — Task 3 has a best-effort live check).

**One planning finding that changes how builds behave:** `build-info` binds to `generate-resources`, so `./mvnw test` now has to resolve the Boot Maven plugin's *own* dependencies (`spring-boot-buildpack-platform`, `jna`, `commons-compress`, …) — previously only `package` needed the plugin, so it was never resolved for a test run. An **offline** build (`-o`) on a cold cache fails with `Cannot access central … in offline mode`. Online builds (CI, Docker, a normal dev machine) are unaffected; run one online build first if you use `-o`.

## What Gets Exposed

| Channel | Where | Contents |
|---|---|---|
| Endpoint | `GET /actuator/info` on each service's own port, no token | `{"commit":"…","build":{"artifact","group","name","time","version"}}` |
| Metric | `application_info{version,commit}` = 1, scraped by the existing Prometheus job per service (`job` label = service name) | version + commit per running service |
| Trace | OTel resource attribute `service.version` (beside the existing `service.name`) | version on every span |
| Image | OCI label `org.opencontainers.image.revision` | commit |
| Dashboard | Grafana → "Microservices Showcase — Service Versions" (`uid: showcase-service-versions`) | table of running builds, version-skew stats, build history |

## Task Breakdown

This document and the roadmap row land first, on `feature/docs-phase-8b-version-visibility` (its own docs PR). **Task branches start only after that PR is merged**, each cut from a freshly fetched `origin/master`.

| # | Task | Deliverable |
|---|---|---|
| 1 | Version source + `/actuator/info` | `<revision>`, `build-info`, `info` exposed, `permitAll` on four services, `BuildInfoIT` ×5 |
| 2 | Commit SHA | `info.commit`, Docker `GIT_SHA` build arg + OCI label, compose build args, extended `BuildInfoIT` |
| 3 | Info metric + OTel `service.version` | `BuildInfoMetricsConfig` ×5, resource attribute, extended `BuildInfoIT` |
| 4 | Grafana dashboard + docs sync + live verification | `service-versions.json`, README/design doc/CLAUDE.md/service-links/roadmap sync |

## Testing

One new IT per service, `BuildInfoIT`, grown across the tasks to three tests: (a) `/actuator/info` needs no token and reports the build version and commit; (b) `/actuator/prometheus` carries exactly one `application_info{…}` line whose labels are the build version and commit; (c) the OTel `Resource` carries `service.version`. They assert the **feature itself, not just that the context starts** — the lesson of Phase 8 Task 1, where a Boot bump left Swagger's static page returning 200 while every API-docs request failed. If exposure, the `permitAll`, the binder, or the resource attribute regresses, exactly one of these fails.

**Reactor-wide test count is the number that matters** (CLAUDE.md: reported counts have been module-scoped before). Task 1 Step 1 records the baseline; after Task 3 the reactor total must be exactly **baseline + 15** (5 services × 3 tests). Task 1 adds 5, Task 2 adds 0, Task 3 adds 10.

## Scope Boundary

Deliberately not in this phase:

- **API/contract versioning** — no `/v1` URI prefix, no header/media-type versioning; OpenAPI `info.version` stays the hard-coded `"v1"`. A `/v1` prefix would rewrite the Gateway routes and every test URL for little showcase value. (Spring Framework 7's native API versioning arrives with Boot 4, which is a separate migration — see Phase 8's Scope Boundary.)
- **Event `schemaVersion`** on the transactional-outbox payload. Today `OutboxEvent` carries `eventType` and a raw `payload` string with no schema version; Notification consumes it unversioned. Adding one changes the Transfer producer and the Notification consumer and deserves its own design — a strong candidate for a later phase on Kafka contract evolution.
- **Release automation** — computing `-Drevision` from a git tag, building/pushing tagged images, changelog tooling (release-please, JReleaser). CI (`.github/workflows/ci.yml`) only runs `./mvnw -B test` and there is no image registry. This phase makes the *mechanism* CI needs (`-Drevision`, `GIT_SHA` build arg) exist; wiring it is a follow-up.
- **`git-commit-id-maven-plugin` / `git.properties`** — see Design Decisions.
- **Version in structured logs** — Boot 3.5's `logging.structured.json.add.*` could stamp it; traces, metrics and `/info` already answer the question.
- **CI-pushed Grafana deployment annotations** and **version-skew alert rules** — no CD stage and no Alertmanager in the stack.
- **Extended `/actuator/info` detail** (`java`/`os`/`process`) — off, because the endpoint is unauthenticated.

## Roadmap Changes

Add a `8b` row between Phase 8 and Phase 9 in `docs/roadmap.md` (done when this document was written: status "Not started"). Task 4 flips it to ✅ Done. Phase 9 is unchanged.

## Whole-Branch Review Checklist (for the final review; give this list verbatim to the reviewing model)

Each item needs a stated result, not "looks fine":

1. `grep -rn "0.1.0-SNAPSHOT" --include=pom.xml .` matches **exactly one line**, the `<revision>` property in the root `pom.xml`.
2. In all five `application.yml`, `management.endpoints.web.exposure.include` is exactly `health,info,prometheus` (`grep -n "include:"`). No `*`.
3. `grep -rn "actuator/\*\*\|actuator/\*" --include=SecurityConfig.java .` matches nothing; the four `SecurityConfig` files each contain exactly `.requestMatchers("/actuator/info").permitAll()` and Notification has no `SecurityConfig`.
4. The only `info.*` property in any `application.yml` is `info.commit`; `management.info` contains only `env.enabled: true`.
5. In each of the five Dockerfiles, `ARG GIT_SHA=unknown` appears **after** the `COPY --from=build` line, in the runtime stage.
6. In `docker-compose.yml`, all five app services pass `GIT_SHA: ${GIT_SHA:-unknown}` under `build.args`, and none defaults it to anything but `unknown`.
7. `BuildInfoMetricsConfig` in the five services is identical apart from its `package` line (`diff` them).
8. Each `BuildInfoIT` has exactly three `@Test` methods and asserts labels, not the gauge value text.
9. `docker/grafana/dashboards/service-versions.json` parses as JSON and its `uid` (`showcase-service-versions`) matches the link in `docs/service-links.html`.
10. Reactor-wide test total equals the recorded baseline + 15.

---

## Tasks

### Task 1: Single version source + unauthenticated `/actuator/info`

**Branch:** `feature/phase-8b-task-1-version-source-and-info`

**Files:**
- Modify: `pom.xml` (root)
- Modify: `account-service/pom.xml`, `transfer-service/pom.xml`, `notification-service/pom.xml`, `fraud-service/pom.xml`, `gateway-service/pom.xml` — parent `<version>` and a `build-info` execution
- Modify: `account-service/src/main/resources/application.yml`, and the same file in `transfer-`, `notification-`, `fraud-`, `gateway-service` — expose `info`
- Modify: `SecurityConfig.java` in account (`com/showcase/account/config/`), transfer, fraud, gateway — `permitAll` for `/actuator/info` (Notification has none)
- Create: `BuildInfoIT.java` in each of the five services' test trees

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a `org.springframework.boot.info.BuildProperties` bean in every service's context (`getVersion()`, `getName()`, `getTime()`); `GET /actuator/info` returning `{"build":{…}}` with no token. Tasks 2–3 rely on both, and on the `BuildInfoIT` class existing in each module.

- [ ] **Step 1: Branch and record the reactor-wide baseline**

```bash
git fetch origin
git switch --no-track -c feature/phase-8b-task-1-version-source-and-info origin/master
export JAVA_HOME=/c/dev/openjdk-21.0.2 PATH="/c/dev/openjdk-21.0.2/bin:$PATH"
./mvnw -B test 2>&1 | tee /tmp/baseline.log | grep -E "Tests run:.*Fail|BUILD"
```

Needs Docker running; takes a few minutes. Write down the **final reactor summary** `Tests run: N, Failures: 0, Errors: 0` line (the last one before `BUILD SUCCESS`, not a per-module line). That `N` is the baseline for the "+15" check. If the baseline is not green, stop and report — do not start on a red master.

- [ ] **Step 2: Write the failing tests**

Create `BuildInfoIT.java` in each service. The test body is identical in all five; only the class scaffolding differs. **Fraud and Gateway** need no containers — full file for Fraud (`fraud-service/src/test/java/com/showcase/fraud/BuildInfoIT.java`; for Gateway use the same file with `package com.showcase.gateway;` at `gateway-service/src/test/java/com/showcase/gateway/BuildInfoIT.java`):

```java
package com.showcase.fraud;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the version channels this phase adds. Asserts the behaviour itself, not just that the
 * context starts: /actuator/info must be reachable with no token and must report the build's
 * version. Real HTTP round trip (RANDOM_PORT) so the real SecurityConfig is in the path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BuildInfoIT {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BuildProperties buildProperties;

    @Test
    void infoEndpointNeedsNoTokenAndReportsTheBuildVersion() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(buildProperties.getVersion()).isNotBlank();
        assertThat((String) JsonPath.read(response.getBody(), "$.build.version"))
                .isEqualTo(buildProperties.getVersion());
    }
}
```

**Account and Transfer** (`account-service/src/test/java/com/showcase/account/BuildInfoIT.java`, `transfer-service/src/test/java/com/showcase/transfer/BuildInfoIT.java`) — same file with the right `package`, plus a Postgres container copied from each module's existing `TracingBridgeIT` / `PrometheusExposureIT`. Add these imports:

```java
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
```

add `@Testcontainers` above `@SpringBootTest(...)`'s class, and this field as the first member of the class:

```java
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
```

**Notification** (`notification-service/src/test/java/com/showcase/notification/BuildInfoIT.java`) — same file with `package com.showcase.notification;`, plus a Kafka container copied from its `TracingBridgeIT`. Add these imports:

```java
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
```

add `@Testcontainers` above the class, and these members before the `@Autowired` fields:

```java
    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
./mvnw -B -pl fraud-service,gateway-service,account-service,transfer-service,notification-service -am \
  -Dtest=BuildInfoIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: **FAIL** in every module — the context cannot start because no `BuildProperties` bean exists (`NoSuchBeanDefinitionException: … BuildProperties`). If any module's `BuildInfoIT` passes, stop and report: something already provides the bean and this brief's premise is wrong.

- [ ] **Step 4: Single version source**

The root `pom.xml` has the literal once (its own `<version>`), and each of the five service poms has it exactly once, in its `<parent>` block. **Run these commands — do not also hand-edit, or `<revision>` will be added twice:**

```bash
sed -i 's|<version>0.1.0-SNAPSHOT</version>|<version>${revision}</version>|' pom.xml */pom.xml
sed -i 's|<java.version>21</java.version>|<revision>0.1.0-SNAPSHOT</revision>\n    <java.version>21</java.version>|' pom.xml
grep -rn "0.1.0-SNAPSHOT" --include=pom.xml .
```

The first command turns all six literals into `${revision}`; the second re-introduces the literal once, as the `<revision>` property's value. The `grep` must print **one** line, that property. The root pom should now read:

```xml
  <groupId>com.showcase</groupId>
  <artifactId>microservices-showcase</artifactId>
  <version>${revision}</version>
  <packaging>pom</packaging>
  ...
  <properties>
    <revision>0.1.0-SNAPSHOT</revision>
    <java.version>21</java.version>
```

- [ ] **Step 5: Add `build-info` to every service pom**

In each of the five service poms, change the existing plugin entry from

```xml
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
```

to

```xml
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
```

- [ ] **Step 6: Expose `info` in every service**

In each service's `src/main/resources/application.yml`, change the existing line (it is under `management.endpoints.web.exposure`, and is identical in all five files):

```yaml
        include: health,prometheus
```

to

```yaml
        include: health,info,prometheus
```

- [ ] **Step 7: Open `/actuator/info` in the four services that have a `SecurityConfig`**

In `SecurityConfig.java` of **account, transfer, fraud and gateway**, add one line directly after the existing `/actuator/prometheus` matcher:

```java
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/info").permitAll()
```

Notification has no Spring Security, so its endpoints are already unauthenticated — no edit there.

- [ ] **Step 8: Run the tests to verify they pass**

```bash
./mvnw -B -pl fraud-service,gateway-service,account-service,transfer-service,notification-service -am \
  -Dtest=BuildInfoIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 5 modules, 1 test each, all PASS. (Online — see the offline finding above.)

- [ ] **Step 9: Verify the override actually controls the version, then run the whole suite**

```bash
./mvnw -B -q -pl fraud-service -am -Drevision=9.9.9 -DskipTests package
ls fraud-service/target/*.jar
unzip -p fraud-service/target/fraud-service-9.9.9.jar META-INF/build-info.properties | grep version
./mvnw -B test 2>&1 | grep -E "Tests run:.*Fail|BUILD"
```

Expected: `fraud-service-9.9.9.jar` exists, prints `build.version=9.9.9`; then the reactor-wide total is **baseline + 5**, `BUILD SUCCESS`. (The `9.9.9` jar stays in the ignored `target/` directory; it is harmless, and `./mvnw -B -q -pl fraud-service clean` removes it.)

- [ ] **Step 10: Commit, push, open the PR, stop**

```bash
git status --short          # only poms, application.yml, SecurityConfig and BuildInfoIT files
git add -A pom.xml */pom.xml */src
git commit -m "feat(versioning): single <revision> version source and unauthenticated /actuator/info (Phase 8b, Task 1)"
git push -u origin feature/phase-8b-task-1-version-source-and-info
gh pr create --title "Phase 8b Task 1: single version source + /actuator/info" --body "<what/why/verification, incl. the reactor-wide test total>"
```

Then **stop** and wait for the user to review and merge.

---

### Task 2: Commit SHA in `/actuator/info`, the image, and compose

**Branch:** `feature/phase-8b-task-2-commit-sha` (from a freshly fetched `origin/master` containing Task 1)

**Files:**
- Modify: `application.yml` in all five services — `info.commit` and `management.info.env.enabled`
- Modify: `account-service/Dockerfile`, `transfer-service/Dockerfile`, `notification-service/Dockerfile`, `fraud-service/Dockerfile`, `gateway-service/Dockerfile`
- Modify: `docker-compose.yml` — a `GIT_SHA` build arg on the five app services
- Modify: the five `BuildInfoIT.java` files

**Interfaces:**
- Consumes: Task 1's `BuildInfoIT` and `/actuator/info`.
- Produces: the Spring property `info.commit` (String, default `unknown`) — **Task 3's `BuildInfoMetricsConfig` reads exactly this name**; `$.commit` in the `/actuator/info` JSON.

- [ ] **Step 1: Extend the failing test**

In all five `BuildInfoIT.java`, replace the class annotation and the test method so the commit is pinned by a test property and asserted:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "info.commit=abc1234")
class BuildInfoIT {
```

```java
    @Test
    void infoEndpointNeedsNoTokenAndReportsTheBuildVersionAndCommit() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(buildProperties.getVersion()).isNotBlank();
        assertThat((String) JsonPath.read(response.getBody(), "$.build.version"))
                .isEqualTo(buildProperties.getVersion());
        assertThat((String) JsonPath.read(response.getBody(), "$.commit")).isEqualTo("abc1234");
    }
```

Pinning `info.commit` in the test (rather than relying on the environment) keeps the test hermetic: a developer with `GIT_SHA` exported in their shell would otherwise see a different value.

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw -B -pl fraud-service -am -Dtest=BuildInfoIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL with a JsonPath `PathNotFoundException` for `$.commit` — the endpoint has no `commit` yet.

- [ ] **Step 3: Add `info.commit` and turn on the env info contributor**

In each service's `application.yml`, add a top-level `info:` block, and add `info.env.enabled` **under the existing `management:` key** (YAML forbids a second `management:` — merge, do not duplicate):

```yaml
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
```

(The `endpoints` lines are shown only to locate the insertion point; they already exist.) `management.info.env.enabled` publishes every `info.*` property under `/actuator/info` — which is why Global Constraints forbid adding any other `info.*` property to an unauthenticated endpoint.

- [ ] **Step 4: Run to verify it passes, in all five modules**

```bash
./mvnw -B -pl fraud-service,gateway-service,account-service,transfer-service,notification-service -am \
  -Dtest=BuildInfoIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS ×5.

- [ ] **Step 5: Dockerfiles — `GIT_SHA` build arg, env and OCI label**

In each of the five Dockerfiles, add these three lines in the **runtime stage, after the `COPY --from=build …` line and before `EXPOSE`** (position matters — see Design Decisions; a `GIT_SHA` earlier than the jar copy would still be correct but would needlessly bust cache; a `GIT_SHA` in the *build* stage would rebuild Maven on every commit):

```dockerfile
COPY --from=build /app/account-service/target/account-service-*.jar app.jar
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}
LABEL org.opencontainers.image.revision=${GIT_SHA}
EXPOSE 8081
```

(Shown for Account; the `COPY` and `EXPOSE` lines already exist and differ per service — only the three middle lines are new.)

- [ ] **Step 6: `docker-compose.yml` — pass the arg to all five app services**

For each of `account-service`, `transfer-service`, `notification-service`, `fraud-service`, `gateway-service`, extend the existing `build:` block:

```yaml
    build:
      context: .
      dockerfile: account-service/Dockerfile
      args:
        GIT_SHA: ${GIT_SHA:-unknown}
```

- [ ] **Step 7: Live verification (Docker required)**

```bash
# bash
GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build
```
```powershell
# PowerShell
$env:GIT_SHA = git rev-parse --short HEAD; docker compose up -d --build
```

Wait until all five app containers report `healthy` (`docker compose ps`), then:

```bash
for p in 8080 8081 8082 8083 8084; do echo "== :$p"; curl -s http://localhost:$p/actuator/info; echo; done
docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' showcase-account-service
git rev-parse --short HEAD
```

Expected: every port returns JSON whose `commit` equals `git rev-parse --short HEAD` and whose `build.version` is `0.1.0-SNAPSHOT`, **with no token**; the `docker inspect` output equals the same SHA. Then confirm the failure mode is visible, not silent: `docker compose up -d --build --no-deps fraud-service` in a shell with `GIT_SHA` **unset** (PowerShell: `Remove-Item Env:GIT_SHA` first, since `$env:` assignments persist for the session) must report `"commit":"unknown"` on `:8084`. Restore with the SHA afterwards. Record the observed JSON for one service in the PR description.

- [ ] **Step 8: Run the whole suite, commit, push, PR, stop**

```bash
./mvnw -B test 2>&1 | grep -E "Tests run:.*Fail|BUILD"
git add -A docker-compose.yml */Dockerfile */src
git commit -m "feat(versioning): git SHA in /actuator/info, image label and compose build arg (Phase 8b, Task 2)"
```

Expected reactor-wide total: **baseline + 5** (this task adds no test methods). Push, open the PR with the observed live output, then **stop**.

---

### Task 3: `application_info` metric + OTel `service.version`

**Branch:** `feature/phase-8b-task-3-info-metric-and-otel-version`

**Files:**
- Create: `BuildInfoMetricsConfig.java` in `com/showcase/{account,transfer,fraud,gateway}/config/` and in a **new** `notification-service/src/main/java/com/showcase/notification/config/`
- Modify: `application.yml` in all five services — `management.opentelemetry.resource-attributes`
- Modify: the five `BuildInfoIT.java` files — two more tests

**Interfaces:**
- Consumes: `BuildProperties` (Task 1); the `info.commit` property (Task 2).
- Produces: a `MeterBinder` bean registering the gauge `application.info` (Prometheus name `application_info`) with labels `version` and `commit`, value 1; the OTel resource attribute `service.version`. **Task 4's dashboard queries `application_info` and the `job`, `version`, `commit` labels by these exact names.**

- [ ] **Step 1: Add the two failing tests**

In all five `BuildInfoIT.java`, add these imports:

```java
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
```

this field beside the other `@Autowired` fields:

```java
    @Autowired
    private Resource otelResource;
```

and these two methods after the existing one:

```java
    @Test
    void applicationInfoGaugeIsScrapedWithVersionAndCommitLabels() {
        String scrape = restTemplate.getForObject("/actuator/prometheus", String.class);

        // Labels, not the value: the sample renders as "1", not "1.0", for an *_info metric.
        assertThat(scrape.lines().filter(line -> line.startsWith("application_info{")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("version=\"" + buildProperties.getVersion() + "\"")
                        .contains("commit=\"abc1234\""));
    }

    @Test
    void openTelemetryResourceCarriesServiceVersion() {
        assertThat(otelResource.getAttribute(AttributeKey.stringKey("service.version")))
                .isEqualTo(buildProperties.getVersion());
    }
```

`Resource` is the OpenTelemetry SDK class the OTLP exporter is built from; it is on the classpath in all five services through Phase 8's `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` (this compiled and passed on Fraud and Gateway during planning).

- [ ] **Step 2: Run to verify both fail**

```bash
./mvnw -B -pl fraud-service -am -Dtest=BuildInfoIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 2 FAIL — the scrape has no `application_info{` line (`singleElement` fails on an empty stream), and the resource's `service.version` is `null`.

- [ ] **Step 3: Create `BuildInfoMetricsConfig` in each service**

`fraud-service/src/main/java/com/showcase/fraud/config/BuildInfoMetricsConfig.java`; the other four are the same file with only the `package` line changed (`com.showcase.account.config`, `…transfer.config`, `…gateway.config`, and `com.showcase.notification.config` — create that directory):

```java
package com.showcase.fraud.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the running build's identity as an "info metric": a gauge fixed at 1 whose labels
 * carry the metadata (Prometheus convention, e.g. prometheus_build_info). Kept off every other
 * series as a common tag on purpose -- a common tag would re-label every series on each deploy.
 * BuildProperties is optional so a run from an IDE without build-info.properties reports
 * "unknown" instead of failing to start.
 */
@Configuration(proxyBeanMethods = false)
public class BuildInfoMetricsConfig {

    @Bean
    MeterBinder applicationInfoMetrics(ObjectProvider<BuildProperties> buildProperties,
                                       @Value("${info.commit:unknown}") String commit) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "unknown";
        return registry -> Gauge.builder("application.info", () -> 1)
                .description("Constant 1; the labels carry the running build's version and commit")
                .tag("version", version)
                .tag("commit", commit)
                .strongReference(true)
                .register(registry);
    }
}
```

- [ ] **Step 4: Add the OTel resource attribute**

In each `application.yml`, add `opentelemetry` **under the existing `management:` key**, as a sibling of the existing `tracing:` block (do not create a second `management:`):

```yaml
management:
  opentelemetry:
    resource-attributes:
      "service.version": "@project.version@"
  tracing:
    enabled: true
```

The quotes keep the dot in the key; `@project.version@` is resolved by Boot's resource filtering at build time.

- [ ] **Step 5: Run to verify all three pass in all five modules**

```bash
./mvnw -B -pl fraud-service,gateway-service,account-service,transfer-service,notification-service -am \
  -Dtest=BuildInfoIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 5 modules × 3 tests, all PASS. Then confirm Boot's resource filtering actually resolved the placeholder in every service — including Transfer, whose `application.yml` has a stray `@Retry` in a comment (line ~117) that the `@`-delimited filter must leave alone:

```bash
grep -n "service.version" */target/classes/application.yml
grep -n "@Retry" transfer-service/target/classes/application.yml
```

Expected: five lines like `"service.version": "0.1.0-SNAPSHOT"` — **none** may still contain `@project.version@` — and the `@Retry` comment still present and unchanged.

- [ ] **Step 6: Live verification (Docker required)**

```bash
GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build   # PowerShell: see Task 2 Step 7
# wait for healthy, then allow ~30 s for two scrapes
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=application_info'
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=count(application_info)'
```

Expected: five result series, one per `job` (`account-service`, `transfer-service`, `notification-service`, `fraud-service`, `gateway-service`), each with `version`, `commit`, `job`, `instance` labels and value `"1"`; the count query returns `"5"`. **This is the first time the metric is seen through a real Prometheus scrape** (which may negotiate OpenMetrics rather than the plain text the IT reads) — if the names or labels differ from the IT's, fix Task 4's queries accordingly and record the difference here, as Phase 8 did for its business metrics.

Best-effort trace check (not verified during planning; the IT on the `Resource` bean is the gate, do not block the PR on this): send a request that reaches the gateway, e.g. `curl -i http://localhost:8080/transfers/123`, then `curl -s "http://localhost:3200/api/search?tags=service.version%3D0.1.0-SNAPSHOT&limit=5"` — a non-empty `traces` array confirms `service.version` reaches Tempo. If it is empty, report it rather than working around it.

- [ ] **Step 7: Whole suite, commit, push, PR, stop**

```bash
./mvnw -B test 2>&1 | grep -E "Tests run:.*Fail|BUILD"
git add -A */src
git commit -m "feat(observability): application_info metric and OTel service.version (Phase 8b, Task 3)"
```

Expected reactor-wide total: **baseline + 15**. Push, open the PR with the live Prometheus output pasted in, then **stop**.

---

### Task 4: Grafana "Service Versions" dashboard, docs sync, end-to-end verification

**Branch:** `feature/phase-8b-task-4-versions-dashboard-and-docs`

**Files:**
- Create: `docker/grafana/dashboards/service-versions.json`
- Modify: `docs/service-links.html`, `README.md`, `docs/microservices-showcase-design.md`, `CLAUDE.md`, `docs/roadmap.md`, and this document (Final Review section, if any findings)

**Interfaces:**
- Consumes: the `application_info` metric and its `job`/`version`/`commit` labels (Task 3); Grafana's existing provisioning (`docker/grafana/provisioning/dashboards/dashboards.yml` already loads every `*.json` under `/etc/grafana/dashboards-data`, polling every 30 s — no compose or provisioning change is needed).
- Produces: dashboard `uid: showcase-service-versions`, linked from `docs/service-links.html`.

- [ ] **Step 1: Create `docker/grafana/dashboards/service-versions.json`**

Mirrors `business-metrics.json`'s format and datasource reference exactly (`{ "type": "prometheus", "uid": "Prometheus" }`):

```json
{
  "title": "Microservices Showcase — Service Versions",
  "uid": "showcase-service-versions",
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "10s",
  "time": { "from": "now-1h", "to": "now" },
  "panels": [
    {
      "id": 1,
      "title": "Services Reporting",
      "type": "stat",
      "gridPos": { "h": 5, "w": 8, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "count(application_info)", "refId": "A" }
      ]
    },
    {
      "id": 2,
      "title": "Distinct Versions Running",
      "type": "stat",
      "description": "1 = every service runs the same version. More than 1 = version skew.",
      "gridPos": { "h": 5, "w": 8, "x": 8, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "count(count by (version) (application_info))", "refId": "A" }
      ],
      "fieldConfig": {
        "defaults": {
          "thresholds": {
            "mode": "absolute",
            "steps": [ { "color": "green", "value": null }, { "color": "orange", "value": 2 } ]
          }
        },
        "overrides": []
      }
    },
    {
      "id": 3,
      "title": "Distinct Commits Running",
      "type": "stat",
      "description": "More than 1 = the stack is partly redeployed, e.g. after docker compose up --build --no-deps <service>.",
      "gridPos": { "h": 5, "w": 8, "x": 16, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "count(count by (commit) (application_info))", "refId": "A" }
      ],
      "fieldConfig": {
        "defaults": {
          "thresholds": {
            "mode": "absolute",
            "steps": [ { "color": "green", "value": null }, { "color": "orange", "value": 2 } ]
          }
        },
        "overrides": []
      }
    },
    {
      "id": 4,
      "title": "Running Builds",
      "type": "table",
      "gridPos": { "h": 9, "w": 24, "x": 0, "y": 5 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "application_info", "instant": true, "format": "table", "refId": "A" }
      ],
      "transformations": [
        {
          "id": "organize",
          "options": {
            "excludeByName": { "Time": true, "Value": true, "__name__": true, "instance": true },
            "renameByName": { "job": "Service", "version": "Version", "commit": "Commit" },
            "indexByName": { "job": 0, "version": 1, "commit": 2 }
          }
        }
      ]
    },
    {
      "id": 5,
      "title": "Build History",
      "type": "state-timeline",
      "description": "One row per service+version+commit. A new row appearing is a redeploy.",
      "gridPos": { "h": 9, "w": 24, "x": 0, "y": 14 },
      "datasource": { "type": "prometheus", "uid": "Prometheus" },
      "targets": [
        { "expr": "application_info", "legendFormat": "{{job}} · {{version}} · {{commit}}", "refId": "A" }
      ]
    }
  ]
}
```

- [ ] **Step 2: Validate the JSON and the uid**

```bash
python -c "import json; d=json.load(open('docker/grafana/dashboards/service-versions.json', encoding='utf-8')); print(d['uid'], len(d['panels']), 'panels')"
```

Expected: `showcase-service-versions 5 panels`.

- [ ] **Step 3: Live verification — provisioning, data, and the skew stats**

```bash
GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build     # PowerShell form: see Task 2 Step 7
# allow ~30 s: Grafana polls the dashboards directory every 30 s, Prometheus scrapes every 10 s
curl -s 'http://localhost:3001/api/search?query=Service%20Versions'
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=count(count by (commit) (application_info))'
```

Expected: the search returns one dashboard with `uid: showcase-service-versions`; the commit-count query returns `"1"`. Then **prove the skew stat works** by deliberately redeploying one service with a different SHA:

```bash
GIT_SHA=deadbee docker compose up -d --build --no-deps account-service
# after ~30 s:
curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=count(count by (commit) (application_info))'
```

Expected: `"2"`. Restore with the real SHA and confirm it returns to `"1"`.

The PromQL is proven by these API calls; **rendering is not** (the Grafana API cannot tell you a panel looks right). Open `http://localhost:3001/d/showcase-service-versions` in a browser and check: five rows in "Running Builds" showing Service/Version/Commit with no `Time`/`Value`/`instance` columns; the two skew stats green at `1`; "Build History" showing one bar per service and, after the `deadbee` redeploy, a second row for `account-service`. Attach a screenshot to the PR. If a panel is wrong, fix the JSON — do not weaken the check.

- [ ] **Step 4: Docs sync**

- `docs/service-links.html`, line 83 — add the info link, and fix the Gateway note on line 88 ("except health/metrics" → "except health/info/metrics"):

```js
  const actuator = [["Health", "/actuator/health"], ["Build info", "/actuator/info"], ["Prometheus metrics", "/actuator/prometheus"]];
```

  and add to Grafana's `links` list, after the business-metrics entry:

```js
        ["Service versions dashboard", "/d/showcase-service-versions"],
```

- `README.md` — in the Observability section (currently "has two dashboards provisioned on startup"), change it to three and add: *"The Service Versions dashboard shows which version and commit each service is running, from the `application_info` metric every service publishes; the same facts are at `/actuator/info` on each service's port, no token needed. Pass the commit when building — `GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build` — or it shows as `unknown`."* Also adjust the sentence near line 31 ("Every endpoint except `/actuator/health` and Swagger's own pages now needs a bearer JWT") so its list of unauthenticated paths matches the `SecurityConfig`s: `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, Swagger's pages.

- `docs/microservices-showcase-design.md` §5 — add after the **Health** bullet:

```markdown
- **Versioning:** each service's build version comes from one `<revision>` in the root `pom.xml` and is exposed four ways: `GET /actuator/info` (unauthenticated: `build.version` plus the git commit), a constant-1 `application_info{version,commit}` gauge (the Prometheus "info metric" convention) driving a provisioned Grafana Service Versions dashboard, the `service.version` OpenTelemetry resource attribute on every span, and an OCI `revision` label on each image. This is build versioning only; API-contract versioning (OpenAPI `info.version`, currently `v1`) is separate and unchanged.
```

- `CLAUDE.md` — (a) "Project status": `Phases 1–8 (including 7b)` → `Phases 1–8 (including 7b and 8b)`; (b) after the "Versions live in the root `pom.xml`" sentence add: *"The project's own version is the single `<revision>` property there (CI overrides it with `-Drevision=`)."*; (c) under "Local environment", add: *"Pass the git commit into the images with `GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build` (PowerShell: `$env:GIT_SHA = git rev-parse --short HEAD; docker compose up -d --build`); without it `/actuator/info` and the Service Versions dashboard show `commit: unknown`. Also: `build-info` runs at `generate-resources`, so an offline (`-o`) Maven build on a cold cache fails to resolve the Boot plugin — run one online build first."*

- `docs/roadmap.md` — change the 8b row's status from `Not started` to `✅ Done`, and re-check its scope text against what actually shipped.

- [ ] **Step 5: Whole suite and grep checks**

```bash
./mvnw -B test 2>&1 | grep -E "Tests run:.*Fail|BUILD"
grep -rn "0.1.0-SNAPSHOT" --include=pom.xml .
grep -n "include:" */src/main/resources/application.yml
```

Expected: reactor-wide total **baseline + 15**; exactly one `0.1.0-SNAPSHOT` line; all five `include:` lines read `health,info,prometheus`.

- [ ] **Step 6: Commit, push, PR, stop**

```bash
git add docker/grafana/dashboards/service-versions.json docs README.md CLAUDE.md
git commit -m "feat(observability): Grafana Service Versions dashboard and docs sync (Phase 8b, Task 4)"
```

Push and open the PR with the screenshot and the `deadbee` skew-test output. Then **stop**. When all four PRs are merged, the user may ask for the whole-branch review (more capable model, the checklist above verbatim, named explicitly) — it is not automatic.
