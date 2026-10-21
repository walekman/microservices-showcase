# Log Aggregation in Grafana (Phase 13)

**Goal:** Every log line the six Spring services write is searchable in Grafana and tied to the
trace it belongs to. You open a trace in Tempo, click a span, and see that request's log lines
from every service it passed through. The logs can also be browsed on their own, filtered by
service and level. Logs and spans stay in separate views; nothing is mixed into the span
waterfall.

**Architecture:** Grafana Loki stores the logs. Grafana Alloy reads the six services' container
output through the Docker socket and pushes it to Loki. The services already write one JSON
object per line (Boot's `logstash` structured format, Phase 8) with `traceId`/`spanId` from the
MDC, so collecting them needs no service change. What the services *don't* do yet is log every
request: gateway-service and account-service write no log lines of their own, so a trace's logs
would be nearly empty. Five services therefore gain a one-line-per-request log
(`RequestLoggingFilter`); notification-service already logs one line per event it handles.
Grafana gets Loki as a data source, a trace-to-logs link on the Tempo data source, a
logs-to-trace link on the Loki data source, and one provisioned logs dashboard.

```
service stdout ──▶ Docker ──(socket)──▶ alloy ──push──▶ loki ◀── Grafana (Explore, dashboard,
                                                                  Tempo "Logs for this span")
```

**Tech Stack:** `grafana/loki` and `grafana/alloy` images in Compose, configuration files under
`docker/loki/` and `docker/alloy/`, Grafana provisioning. One servlet filter per HTTP-facing
service, using only what is already on the classpath (Spring Web, SLF4J). No new dependency.

**Spec:** This document, brainstormed with the user on 2026-10-21. The task-by-task
implementation is added to it once this design is approved.

## Global Constraints

- Phase workflow (CLAUDE.md, from Phase 10): this phase lives on `feature/phase-13`, which starts
  with this spec commit and has a draft PR into `master`. Each task branches off
  `feature/phase-13` and gets its own PR back into it.
- Java 21; Spring Boot 3.5. Use `C:\dev\openjdk-21.0.2` as `JAVA_HOME`. (CLAUDE.md)
- **The only service code change is the request log line** (below). No `pom.xml` change, no
  change to what existing log statements say. Anything else that seems to need service code:
  stop and report it; that is a scope change for the user to decide.
- **The dev stack's memory stays bounded.** The ~7.4 GB Docker VM already holds the capped stack
  (~3 GB, `docker-compose.yml` header). Loki and Alloy get a Go soft limit (`GOMEMLIMIT`) like
  Tempo and Grafana, and together should add no more than ~400 MB in `docker stats` after a few
  transfers. Measure it; report the figure in the PR.
- **The E2E stack doesn't run them.** `docker-compose.e2e.yml` keeps Loki and Alloy from starting
  (for example `scale: 0`; any mechanism that works with the `up --detach --wait` call in
  `E2EStack` is fine). Verify with `./mvnw -Pe2e -pl e2e-tests verify` or at least with the same
  `docker compose -f docker-compose.yml -f docker-compose.e2e.yml config`/`up` invocation.
- **`docker compose up` still brings everything up**, Loki and Alloy included, with no extra
  flag or profile.
- Image tags are pinned to an exact version (my call, unlike Tempo/Prometheus/Grafana's
  `:latest`): Loki and Alloy configuration formats change between majors, and an unpinned
  upgrade would break silently. Use the current stable Loki 3.x and Alloy 1.x tags at build time,
  and write the configs against those versions' docs.

## Behaviour

### What gets collected

- The stdout/stderr of the six services: `gateway-service`, `account-service`,
  `transfer-service`, `notification-service`, `fraud-service`, `fx-service`. Nothing else: no
  Kafka, Postgres, Keycloak, Redis, nginx or observability containers (user's decision; adding
  one later is a line in the Alloy config).
- Containers are selected by their Compose service label (`com.docker.compose.service`), not by
  `container_name`, so the selection doesn't depend on fixed names.
- Lines that are not JSON (for example the JVM's `Picked up JAVA_TOOL_OPTIONS` on stderr) are
  kept as they are, just without a `level` label. They must not be dropped or break the pipeline.

### Labels and fields

- **Labels** (indexed, used in stream selectors): `service` (the Compose service name) and
  `level` (from the JSON `level` field, e.g. `INFO`, `WARN`, `ERROR`). Nothing else becomes a
  label: high-cardinality values like `traceId` as labels would bloat Loki's index.
- **Fields** (parsed at query time with `| json`): everything in the JSON line, including
  `message`, `logger_name`, `thread_name`, `traceId`, `spanId`, and stack traces.
- Timestamps: the line's own `@timestamp` is preferred over Docker's receive time if Alloy can
  parse it reliably; otherwise Docker's time is fine (builder's call, note which in the doc).

### In Grafana

- **Loki data source**, provisioned with a fixed `uid: loki`, `editable: false`, like the others.
  It has a derived field that turns `traceId` in a log line into a link to that trace in Tempo
  (logs → trace).
- **Tempo data source** gets a fixed `uid: tempo` and `tracesToLogsV2` pointing at `loki`
  (trace → logs). Clicking "Logs for this span" opens, next to the trace, every log line whose
  `traceId` equals the trace's ID, from all six services, over the span's time range widened by
  a margin (a minute either side is a reasonable start). It filters by trace, not span, so you
  see the whole request's logs; the user asked for "all logs ... in a trace context". The
  existing Prometheus data source and the dashboards that reference it don't change.
- **Logs dashboard** ("Service Logs", provisioned next to the existing four in
  `docker/grafana/dashboards/`):
  - variables: `service` (multi-select, all by default), `level` (multi-select, all by
    default), and a free-text search box
  - a log-volume time series by level, so error spikes stand out
  - a logs panel showing the matching lines, newest first, with the JSON fields expandable
- **Explore** works with Loki out of the box, e.g.
  `{service="transfer-service", level="ERROR"}` or `{service=~".+"} | json | traceId="<id>"`.
- **Anonymous visitors are Editors** (`GF_AUTH_ANONYMOUS_ORG_ROLE: Editor`, was `Viewer`).
  Grafana sends Viewers away from Explore, and both trace ↔ logs links open there; found in
  Task 3, chosen by the user over the deprecated `viewers_can_edit` and over signing in.

### The request log line

Added at the user's request, so every service a request passes through shows up in that
trace's logs.

- **Where:** gateway-service, account-service, transfer-service, fraud-service, fx-service. One
  `RequestLoggingFilter` (a `OncePerRequestFilter` `@Component`) in each service's `config`
  package. There is no shared module, and the services don't share code today, so each gets its
  own copy, identical apart from the package.
- **Not notification-service** (my call): its only HTTP endpoints are Actuator's, so the filter
  would never log anything. Its `NotificationListener` already writes `Notification sent: ...`
  (or `Duplicate delivery skipped: ...`) at INFO inside the Kafka consumer's span, which is
  the same trace, so it already shows up.
- **What it writes:** one line per request, after the response is complete:
  `POST /transfers 201 184ms`: method, path without the query string, status, duration in
  milliseconds. The same four values are also attached as SLF4J key-value pairs
  (`http.method`, `http.path`, `http.status`, `duration_ms`) if Boot 3.5's `logstash` format
  writes key-value pairs as JSON fields. Verify that with a test; if it doesn't, the message
  alone is enough. No headers, no query string, no request or response bodies, no user
  identity: the line must never carry a token or personal data.
- **Level:** `INFO`, and `WARN` for a 5xx status, so the dashboard's level filter finds server
  errors. 4xx stays `INFO`: it's the client's mistake, and the services already return
  RFC 7807 bodies for it.
- **Excluded:** `/actuator/**`. Compose healthchecks hit every service every 10 s and Prometheus
  scrapes them; logging those would bury the real requests.
- **Inside the trace:** the line must carry the request's `traceId`/`spanId`, so the filter
  runs inside Boot's `ServerHttpObservationFilter` (ordered after it, which is
  `Ordered.HIGHEST_PRECEDENCE + 1`), where the observation's span is current. It runs before
  Spring Security, so `401`/`403` responses are logged too.
- **Errors:** if the filter chain throws, the line still gets written (in a `finally`) with
  status `500`, and the exception is rethrown unchanged. The filter never swallows or changes a
  response.
- **The Gateway logs its own line** for a proxied request (`POST /transfers 201 ...`), and the
  downstream service logs its line too. That's two lines for one hop, by design: they have
  different durations, and the difference is the Gateway's own overhead.

### Retention

Loki keeps logs for **3 days** (compactor retention), on a Docker volume so they survive a
`docker compose restart` but go with `docker compose down -v`. It's a local demo; logs should
not pile up.

## Design Decisions

- **Loki, not Dozzle.** Dozzle only views container output and can't be linked from a Tempo span.
  Grafana's trace-to-logs needs a log store it can query, so Loki is what delivers "logs in a
  trace context".
- **Alloy reading Docker, not the services sending logs over OTLP.** The OTLP route would reuse
  the existing OTel Collector, but needs a logback appender dependency and config in all six
  services; reading container output needs no service change. It also keeps the option of
  collecting Kafka or Keycloak later, which the OTLP route could never see. Promtail is not used:
  Grafana deprecated it in favour of Alloy.
- **Stdout stays the source.** `docker compose logs` keeps working exactly as before; Loki is
  an additional copy, not a replacement.
- **`traceId` as a field, not a label.** Every request has a new one; as a label it would create
  a stream per request. Loki's line filters and `| json` over the label-selected streams are fast
  enough at demo scale.
- **A per-request log line, not more logging inside the services.** gateway-service and
  account-service write no log lines today, so trace-to-logs would show almost nothing from them.
  One line per request, written by a filter, makes every HTTP hop visible with no change to
  business code; extra log statements in use cases would be a separate, case-by-case decision.
- **A filter, not Tomcat's access log.** `server.tomcat.accesslog` writes its own text format to
  a file, outside Logback, so it would carry neither the JSON format nor the MDC trace IDs.
- **Infrastructure logs are not trace-correlated, and not collected now.** Kafka, Keycloak and
  Redis log server events, not per-request lines, and carry no `traceId`; the user chose the six
  services only for this phase.
- **This reverses the design doc's "no centralized log aggregation" non-goal**, at the user's
  request. The design doc is updated in this phase.

## Scope Boundary

- Collecting logs from infrastructure containers (Kafka, Postgres, Keycloak, Redis, nginx, the
  observability stack).
- Changing what the services log, beyond the request log line: no new log statements in
  business code, and no change to existing ones.
- A request log line in notification-service (it has no business HTTP endpoints).
- Alerting on log patterns, log-based metrics, and Loki in the E2E stack.
- Production concerns: multi-tenant Loki, object storage, high availability.

## Delivery

| Task | Branch | Scope |
|---|---|---|
| 1 | `feature/phase-13-task-1-loki-alloy` | Loki and Alloy in Compose (pinned images, configs, volume, memory caps, Docker socket), the E2E overlay exclusion, the Loki data source and the Tempo↔Loki links |
| 2 | `feature/phase-13-task-2-request-logging` | `RequestLoggingFilter` and its test in the five HTTP-facing services |
| 3 | `feature/phase-13-task-3-logs-dashboard-docs` | The "Service Logs" dashboard, the live check script, docs |

Tasks 1 and 2 are independent of each other. Task 3 needs both.

Docs updated alongside (Task 3):
- `docs/microservices-showcase-design.md`: the non-goal line in §1, the Observability bullet in
  §3, and §5 Logging
- `docs/roadmap.md`: a Phase 13 row (added with this spec, updated when the phase finishes)
- `docs/open-items.md`: "log aggregation" comes off §2 "Deliberately not planned"; this phase's
  Scope Boundary items are added
- `CLAUDE.md`: the "Also:" infrastructure line gains Loki and Alloy
- `README.md`: where to look at logs in Grafana
- `tools/service-links.html`: a link to the logs dashboard, if it lists the other dashboards

## Testing

- **`RequestLoggingFilterTest` in each of the five services** (plain unit test with
  `MockHttpServletRequest`/`MockHttpServletResponse` and Boot's `OutputCaptureExtension`, or a
  Logback list appender, builder's call), covering:
  - one line for a normal request, with method, path, status and a duration
  - the query string is not in the line
  - `/actuator/health` and `/actuator/prometheus` produce no line
  - a 5xx is `WARN`, a 4xx is `INFO`
  - a chain that throws still produces a line with `500`, and the exception reaches the caller
  - with an active span, the line carries that span's `traceId` (one test in one service, e.g.
    with the existing `TracingBridgeIT` setup, is enough to prove the ordering; the ordering is
    the same in all five)

Everything else is verified live, against the dev stack:

- **A live check script** (`tools/check-trace-logs.ps1`, builder may choose the exact name): it
  gets a demo user's token the way `e2e-tests`' `TestUsers` does (password grant on
  `showcase-ui`), sends a transfer through the Gateway with its own `traceparent` header, so the
  trace ID is known in advance, then polls Loki's HTTP API (`/loki/api/v1/query_range`) until
  lines with that `traceId` arrive from gateway-service, transfer-service, account-service,
  fraud-service and notification-service, or fails after a timeout (~60 s). fx-service is
  expected only for a cross-currency transfer; the script makes a same-currency one unless told
  otherwise. It prints which services it found lines from.
- **Manual checks**, recorded in the PR:
  - In Tempo, open the transfer's trace, click a span, "Logs for this span": the same lines appear.
  - In a log line, the `traceId` link opens the trace in Tempo.
  - The dashboard's service/level/search filters narrow the panel as expected.
  - A non-JSON line (e.g. `Picked up JAVA_TOOL_OPTIONS`) is in Loki, without a `level` label.
  - Only the six services' streams exist: `/loki/api/v1/label/service/values` lists exactly six.
  - Retention is configured: Loki's `/config` shows `retention_period: 3d` and the compactor's
    `retention_enabled: true` (Loki does not log the setting at start).
  - Memory: `docker stats --no-stream` for Loki and Alloy, within the cap above.
  - `docker compose -f docker-compose.yml -f docker-compose.e2e.yml config` shows Loki and Alloy
    not started.
- `./mvnw -B test` passes, including the five new filter tests.

## Review Focus

1. **Alloy picks containers by Compose label, and only the six.** A wrong relabel rule either
   collects everything (noise, memory) or nothing (silent). Check with the label values query.
2. **Non-JSON lines survive.** A JSON stage that drops unparsable lines would hide stack-trace
   fragments or JVM warnings.
3. **The trace-to-logs query filters on the parsed `traceId` field**, not just a substring
   match on the line, and covers all six services, not only the span's own service.
4. **The E2E stack really doesn't start Loki and Alloy** under the exact `E2EStack` command.
5. **The request line really carries the trace ID.** A filter ordered before
   `ServerHttpObservationFilter` compiles, runs and logs fine, but without `traceId`. Only a
   test with an active span, or the live check, catches it.
6. **The request line never leaks secrets.** No `Authorization` header, no query string, no
   bodies.
7. **Docker socket on Docker Desktop for Windows**: `/var/run/docker.sock` must be mounted
   read-only and actually work there. Verify live, not just that Alloy starts.

---

# Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. If something in this plan is wrong or impossible, report it rather than silently working around it — read it critically rather than transcribing it.

**Goal:** Build the design above in three tasks, each its own branch off `feature/phase-13` and
its own PR back into it.

**Architecture:** Task 1 adds Loki and Alloy to Compose and wires them into Grafana; logs flow
from that point on. Task 2 adds the request log line to five services; it touches no
infrastructure and can be built in parallel with Task 1. Task 3 adds the dashboard and the live
check script, which need both, and finishes the docs.

**Tech Stack:** as in the header of this document. Pinned images, current stable at the time of
writing (2026-10-21, Docker Hub): `grafana/loki:3.7.9`, `grafana/alloy:v1.21.0`. A newer patch
release of the same minor is fine; write the configs against the pinned version's docs.

**Spec:** the design sections above.

## Review Focus (plan)

Things the spec implies but no happy-path check would catch, most likely first. Each has a
check in the task named.

1. **Grafana provisioning eats `${...}`** (Task 1). Grafana expands environment variables in
   provisioning files, so `${__trace.traceId}` in the Tempo data source's query, or
   `${__value.raw}` in Loki's derived field, silently becomes empty. They must be written
   `$${__trace.traceId}` and `$${__value.raw}`. The check reads the provisioned data source back
   from Grafana's API.
2. **The request line without a trace ID** (Task 2). A filter ordered before
   `ServerHttpObservationFilter` logs fine but with no `traceId`. `RequestLoggingIT` checks it
   through the real servlet filter chain.
3. **Non-JSON lines dropped** (Task 1). A JSON stage that drops unparsable lines hides JVM
   warnings and stray stderr. The check finds `Picked up JAVA_TOOL_OPTIONS` in Loki.
4. **The wrong containers collected** (Task 1). The check lists the `service` label's values:
   exactly the six.
5. **The E2E stack broken by the new services** (Task 1). A `scale: 0` service that another
   service `depends_on` makes `up --wait` fail. Grafana must not depend on Loki, and the E2E
   suite is run once.

## Before any task that runs Docker

Per `CLAUDE.md`, the coordinating session (never an implementer) brings down any other
worktree's `docker compose` stack before a task's live-verification step. Container names are
fixed, so two stacks collide. The E2E run in Task 1 also needs the dev stack stopped
(`docker compose stop`, then `docker compose start` afterwards): this machine's Docker VM can't
hold both.

## Task 1: Loki, Alloy, Grafana data sources

Branch: `feature/phase-13-task-1-loki-alloy`, off `feature/phase-13`.

**Files:**
- Create: `docker/loki/loki.yaml`
- Create: `docker/alloy/config.alloy`
- Modify: `docker-compose.yml` (two services, one volume)
- Modify: `docker-compose.e2e.yml` (the two services at `scale: 0`)
- Modify: `docker/grafana/provisioning/datasources/datasources.yml`

**Interfaces:**
- Produces: Loki at `http://loki:3100` in Compose and `http://localhost:3100` on the host;
  streams labelled `service` (Compose service name) and `level`; Grafana data sources
  `uid: loki` and `uid: tempo`. Task 3's dashboard and script rely on these exact names.

- [ ] **Step 1: Write `docker/loki/loki.yaml`**

  Single-binary Loki (all targets in one process), `auth_enabled: false`, HTTP on `3100`,
  `replication_factor: 1`, in-memory ring, filesystem storage under `/loki`. Schema: one entry,
  `store: tsdb`, `object_store: filesystem`, `schema: v13`, `period: 24h`, `from` any date
  before today. Retention: `limits_config.retention_period: 72h`, and the compactor with
  `retention_enabled: true` (Loki 3 also requires `delete_request_store: filesystem` when
  retention is on). `limits_config.discover_service_name: []`, or Loki 3 adds a third label,
  `service_name`, to every stream.

- [ ] **Step 2: Write `docker/alloy/config.alloy`**

  - `discovery.docker` on `unix:///var/run/docker.sock`.
  - `discovery.relabel` over those targets that **keeps** only containers whose
    `__meta_docker_container_label_com_docker_compose_service` matches exactly
    `gateway-service|account-service|transfer-service|notification-service|fraud-service|fx-service`,
    and copies that label into `service`.
  - `loki.source.docker` with `targets = discovery.relabel.<name>.output`. Not `relabel_rules`
    on `loki.source.docker`: there, the keep rule let every container through (as
    `service_name="unknown_service"`).
  - `loki.process`: a `stage.json` extracting `level` and `@timestamp`, a `stage.labels` making
    `level` a label, and a `stage.timestamp` from `@timestamp` (keep it or not, per the spec,
    after looking at a real line's format). Unparsable lines must pass through unchanged;
    `drop_malformed` stays off.
  - `loki.write` to `http://loki:3100/loki/api/v1/push`.

- [ ] **Step 3: Add `loki` and `alloy` to `docker-compose.yml`**

  Next to `tempo`, in the same style:
  - `loki`: `grafana/loki:3.7.9`, `container_name: showcase-loki`,
    `command: ["-config.file=/etc/loki/loki.yaml"]`, config mounted `:ro`, a `loki-data` volume
    on `/loki` (add it under `volumes:`), port `3100:3100`, `GOMEMLIMIT: 192MiB`. A healthcheck
    on `/ready` only if the image has a tool to run it (check; Tempo has none either).
  - `alloy`: `grafana/alloy:v1.21.0`, `container_name: showcase-alloy`, the config mounted
    `:ro`, `/var/run/docker.sock:/var/run/docker.sock:ro`, `GOMEMLIMIT: 96MiB`, port
    `12345:12345` for Alloy's pipeline UI (run with `--server.http.listen-addr=0.0.0.0:12345`),
    `depends_on: [loki]`.
  - `grafana` does **not** get a `depends_on` on Loki (Review Focus 5).

- [ ] **Step 4: Keep them out of the E2E stack**

  In `docker-compose.e2e.yml`, after `grafana`: `loki` and `alloy` with `scale: 0`,
  `container_name: !reset null` and `ports: !reset []`, with a one-line comment saying why
  (memory; see the spec).

- [ ] **Step 5: Provision the data sources**

  In `datasources.yml`, leave Prometheus as it is. Add `deleteDatasources: [{name: Tempo,
  orgId: 1}]`: an existing Grafana container fails to start ("data source not found") when the
  provisioned Tempo's uid changes. Give Tempo `uid: tempo` and
  `jsonData.tracesToLogsV2`: `datasourceUid: loki`, `spanStartTimeShift: '-1m'`,
  `spanEndTimeShift: '1m'`, `customQuery: true`,
  `query: '{service=~".+"} | json | traceId="$${__trace.traceId}"'`.
  Add Loki: `uid: loki`, `type: loki`, `url: http://loki:3100`, `editable: false`,
  `jsonData.derivedFields` with one entry: `name: traceId`,
  `matcherRegex: '"traceId":"(\w+)"'`, `datasourceUid: tempo`, `url: '$${__value.raw}'`,
  `urlDisplayLabel: View trace`.

- [ ] **Step 6: Bring it up and generate traffic**

  Run `docker compose up -d loki alloy`, then `docker compose up -d --force-recreate grafana`,
  then `docker compose restart fraud-service` (so a fresh start, including the JVM's stderr
  line, is read), then make one transfer in the Bank UI (http://localhost:8090).

  Expected: `curl -s http://localhost:3100/ready` prints `ready`, and
  `docker compose logs alloy` shows no repeated errors.

- [ ] **Step 7: Check the labels (Review Focus 4)**

  Run: `curl -s http://localhost:3100/loki/api/v1/label/service/values` and
  `curl -s http://localhost:3100/loki/api/v1/label/level/values`

  Expected: `service` lists exactly the six service names; `level` lists at least `INFO`.

- [ ] **Step 8: Check that non-JSON lines survive (Review Focus 3)**

  Query Loki's `/loki/api/v1/query_range` with
  `{service="fraud-service"} |= "Picked up JAVA_TOOL_OPTIONS"` over the last 15 minutes
  (`curl -G` with `--data-urlencode` for `query` and `since=15m`).

  Expected: at least one result, and its stream has no `level` label.

- [ ] **Step 9: Check the provisioned links (Review Focus 1)**

  Run: `curl -s -u admin:admin http://localhost:3001/api/datasources/uid/tempo`, and the same
  for `uid/loki`.

  Expected: Tempo's `tracesToLogsV2.query` contains the literal `${__trace.traceId}`, and Loki's
  derived field `url` the literal `${__value.raw}`, not empty strings. Then, in Grafana, open the
  transfer's trace (Explore → Tempo → Search) and click a span's "Logs for this span": transfer
  log lines appear (the other services' request lines come with Task 2). From one of those log
  lines, "View trace" opens the same trace.

- [ ] **Step 10: Check memory**

  Run: `docker stats --no-stream showcase-loki showcase-alloy`
  Expected: together under ~400 MB. Put the figures in the PR description.

- [ ] **Step 11: Run the E2E suite once (Review Focus 5)**

  After the coordinator stops the dev stack (see above), run `./mvnw -Pe2e -pl e2e-tests verify`.
  Expected: `BUILD SUCCESS`, and during the run `docker ps` shows no `loki` or `alloy`
  container for the `showcase-e2e-*` project. Then `docker compose start` the dev stack again.

- [ ] **Step 12: Commit**

```bash
git add docker/loki docker/alloy docker-compose.yml docker-compose.e2e.yml docker/grafana/provisioning/datasources/datasources.yml
git commit -m "feat(observability): collect service logs in Loki via Alloy"
```

  Open the PR into `feature/phase-13`, and stop.

## Task 2: The request log line

Branch: `feature/phase-13-task-2-request-logging`, off `feature/phase-13`.

**Files**, for each of the five services, where `<svc>`/`<pkg>` is `gateway-service`/`gateway`,
`account-service`/`account`, `transfer-service`/`transfer`, `fraud-service`/`fraud`,
`fx-service`/`fx`:
- Create: `<svc>/src/main/java/com/showcase/<pkg>/config/RequestLoggingFilter.java`
- Test: `<svc>/src/test/java/com/showcase/<pkg>/config/RequestLoggingFilterTest.java`

and once:
- Test: `gateway-service/src/test/java/com/showcase/gateway/RequestLoggingIT.java`

**Interfaces:**
- Produces: in each service,
  `@Component @Order(Ordered.HIGHEST_PRECEDENCE + 2) public class RequestLoggingFilter extends OncePerRequestFilter`
  with `protected boolean shouldNotFilter(HttpServletRequest)` and
  `protected void doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain)`.
  Logger: `LoggerFactory.getLogger(RequestLoggingFilter.class)`. One log event per request:
  message `"{method} {path} {status} {millis}ms"` (e.g. `POST /transfers 201 184ms`), level
  `INFO`, or `WARN` when the status is 500 or above; key-value pairs `http.method`,
  `http.path`, `http.status` (int), `duration_ms` (long), via SLF4J's fluent API
  (`log.atLevel(level).addKeyValue(...)`). Task 3's script relies on the line carrying
  `traceId`.

- [ ] **Step 1: Write the failing unit test, in gateway-service**

  `RequestLoggingFilterTest`: attach a Logback `ListAppender<ILoggingEvent>` to the filter's
  logger in `@BeforeEach` (detach in `@AfterEach`), and drive the filter through its public
  `doFilter(request, response, chain)`, so `shouldNotFilter` applies, with
  `MockHttpServletRequest`/`MockHttpServletResponse` and a lambda `FilterChain` that sets the
  response status. Tests:
  - `logsOneLineWithMethodPathStatusAndDuration`: `POST /transfers`, chain sets 201 → exactly
    one event, level `INFO`, formatted message matches `^POST /transfers 201 \d+ms$`.
  - `leavesOutTheQueryString`: `GET /fx/rates` with query string `base=PLN&quote=EUR` → the
    message starts with `GET /fx/rates 200 ` and contains neither `?` nor `base=`.
  - `skipsActuatorEndpoints`: `/actuator/health`, `/actuator/prometheus` and `/actuator` → no
    events.
  - `logsServerErrorsAsWarn`: status 503 → `WARN`.
  - `logsClientErrorsAsInfo`: status 404 → `INFO`.
  - `logsA500AndRethrowsWhenTheChainThrows`: the chain throws `new IllegalStateException("boom")`
    → `assertThatThrownBy` sees that same instance, and one `WARN` event with ` 500 ` in the
    message.
  - `attachesKeyValuePairs`: the event's `getKeyValuePairs()` holds `http.method=POST`,
    `http.path=/transfers`, `http.status=201` and a non-negative `duration_ms`.

- [ ] **Step 2: Run it to verify it fails**

  Run: `./mvnw -pl gateway-service -Dtest=RequestLoggingFilterTest test`
  Expected: compilation failure, `RequestLoggingFilter` not found.

- [ ] **Step 3: Implement `RequestLoggingFilter` in `com.showcase.gateway.config`**

  Per the Interfaces block and the spec's "The request log line". `shouldNotFilter` is true for
  a URI equal to `/actuator` or starting with `/actuator/`. The path is
  `request.getRequestURI()` (never the query string). Time with `System.nanoTime()`; log in a
  `finally`, with status 500 when the chain threw and `response.getStatus()` otherwise; rethrow
  unchanged. The `@Order` places it after Boot's `ServerHttpObservationFilter`
  (`HIGHEST_PRECEDENCE + 1`), so the request's span is current, and before Spring Security's
  filter chain (`-100`), so 401/403 responses are logged. A short comment says so.

- [ ] **Step 4: Run it to verify it passes**

  Run: `./mvnw -pl gateway-service -Dtest=RequestLoggingFilterTest test`
  Expected: PASS, 7 tests.

- [ ] **Step 5: Write `RequestLoggingIT` (Review Focus 2)**

  In gateway-service, shaped like `GatewaySecurityIT` (`RANDOM_PORT`,
  `@Import(TestSecurityConfig.class)`; no upstream needed, since the request never gets past
  security), plus `@ExtendWith(OutputCaptureExtension.class)`:
  - `requestLineIsJsonInsideTheRequestTrace`: `GET /transfers/123` with no token → `401`.
    Find the captured stdout line containing `GET /transfers/123 401` and parse it with Jackson:
    `level` is `INFO`, and `traceId` and `spanId` are present and non-blank.
  - In the same test, check whether the line has an `http.status` field. If it does, assert it
    equals `401`. If Boot's `logstash` format doesn't write key-value pairs, don't assert it;
    say so in the PR description (Task 3 records it in "Implementation Notes (as built)").

- [ ] **Step 6: Run it**

  Run: `./mvnw -pl gateway-service -Dtest=RequestLoggingIT test`
  Expected: PASS. If `traceId` is missing, the filter's order is wrong: fix the order, not the
  test.

- [ ] **Step 7: Copy the filter and its unit test to account, transfer, fraud and fx**

  Same class and test in each service's `config` package; only the package line differs. In
  each test, use that service's real paths where the gateway test used `/transfers` and
  `/fx/rates` (e.g. `POST /accounts/{id}/debit` in account; fraud's and fx's own endpoints). No
  IT in these four: the ordering is the same in every service, and Task 3's live check covers
  all of them.

- [ ] **Step 8: Run the five modules' full suites**

  Run: `./mvnw -pl gateway-service,account-service,transfer-service,fraud-service,fx-service test`
  Expected: `BUILD SUCCESS`, including the five `RequestLoggingFilterTest`s and
  `RequestLoggingIT`. Report the test counts per module.

- [ ] **Step 9: Live check**

  Set `GIT_SHA` (CLAUDE.md), then
  `docker compose up -d --build --no-deps gateway-service account-service transfer-service fraud-service fx-service`,
  wait for healthy, make one transfer in the Bank UI, and read
  `docker compose logs --since 2m gateway-service account-service transfer-service fraud-service`.

  Expected: one request line per hop (Gateway's `POST /transfers 201`, Account's `exists`,
  `debit` and `credit`, Fraud's checks), each JSON and all with the same `traceId`; no
  `/actuator` lines.

- [ ] **Step 10: Commit**

```bash
git add gateway-service/src account-service/src transfer-service/src fraud-service/src fx-service/src
git commit -m "feat: log one line per HTTP request in every HTTP-facing service"
```

  Open the PR into `feature/phase-13`, and stop.

## Task 3: Logs dashboard, live check script, docs

Branch: `feature/phase-13-task-3-logs-dashboard-docs`, off `feature/phase-13` after Tasks 1 and
2 are merged into it.

**Files:**
- Create: `docker/grafana/dashboards/service-logs.json`
- Create: `tools/check-trace-logs.ps1`
- Modify: `docs/microservices-showcase-design.md` (§1 non-goals line, §3 Observability bullet,
  §5 Logging)
- Modify: `docs/roadmap.md` (Phase 13 row: ✅ Done, scope as built)
- Modify: `docs/open-items.md` (drop "log aggregation" from §2's Observability row; add this
  phase's Scope Boundary items to §2)
- Modify: `CLAUDE.md` ("Phases 1–12" → "1–13"; the "Also:" line gains Loki and Alloy)
- Modify: `README.md` (where to look at logs in Grafana)
- Modify: `tools/service-links.html` (the dashboard under Grafana; a Loki entry with `/ready`,
  like Tempo's)
- Modify: this document (an "Implementation Notes (as built)" section)

**Interfaces:**
- Consumes: Task 1's labels `service`/`level` and data source uids; Task 2's request line.

- [ ] **Step 1: Write the dashboard**

  `service-logs.json`, in the style of the existing four (`uid: showcase-service-logs`, title
  `Microservices Showcase — Service Logs`, data source `{ "type": "loki", "uid": "loki" }`):
  - variables: `service` (query `label_values(service)`, multi, include All, all value `.+`),
    `level` (custom `DEBUG,INFO,WARN,ERROR`, multi, include All, all value `.*`, so lines with
    no `level` label still match), `search` (textbox, default empty).
  - a time series "Log volume by level":
    `sum by (level) (count_over_time({service=~"$service", level=~"$level"} |= "$search" [$__auto]))`
  - a logs panel "Logs", newest first:
    `{service=~"$service", level=~"$level"} |= "$search"`

- [ ] **Step 2: Check the dashboard**

  Run `docker compose up -d --force-recreate grafana` and open
  http://localhost:3001/d/showcase-service-logs.
  Expected: both panels show data; choosing `transfer-service` and `INFO` narrows both;
  searching for a transfer ID from the UI leaves only lines containing it.

- [ ] **Step 3: Write `tools/check-trace-logs.ps1`**

  Windows PowerShell 5.1 compatible. Parameters with defaults: `-Gateway http://localhost:8080`,
  `-Keycloak http://localhost:8180`, `-Loki http://localhost:3100`, `-TimeoutSeconds 60`.
  1. Tokens for `ada` and `bob` (password `password`): password grant on
     `realms/showcase/protocol/openid-connect/token`, `client_id=showcase-ui`.
  2. For each, `GET /accounts/mine` through the Gateway; if the list is empty, `POST /accounts`
     with `ownerName` = the username, `initialBalance` `1000.00`, `currency` `EUR`.
  3. A random 32-hex `traceId` and 16-hex parent span ID; `POST /transfers` as ada with
     `{fromAccountId, toAccountId, amount: 1.00}` and headers `Idempotency-Key` (a new GUID)
     and `traceparent: 00-<traceId>-<spanId>-01`.
  4. Expected services: gateway, transfer, account, fraud and notification, plus fx when the
     two accounts' currencies differ.
  5. Every 2 s until all are found or the timeout: `GET /loki/api/v1/query_range` with
     `query={service=~".+"} |= "<traceId>"`, `since=5m`, `limit=1000`; collect the `service`
     labels of the returned streams.
  6. Print found and missing services; exit `0` if none is missing, `1` otherwise.

- [ ] **Step 4: Run it**

  Run: `powershell -File tools/check-trace-logs.ps1`
  Expected: every expected service found, exit code `0`. Then do the spec's manual checks
  (Testing section) not already done in Task 1, and record the results in the PR.

- [ ] **Step 5: Update the docs**

  Per the Files list. In the design doc, "no centralized log aggregation" is replaced by what
  this phase built (Loki via Alloy, trace ↔ logs links, the request log line); the other
  non-goals stay. "Implementation Notes (as built)" records: the image versions used, whether
  `@timestamp` became the Loki timestamp, whether Boot's `logstash` format writes the request
  line's key-value pairs (Task 2's finding), measured memory, and anything that differed from
  this plan.

- [ ] **Step 6: Commit**

```bash
git add docker/grafana/dashboards/service-logs.json tools docs CLAUDE.md README.md
git commit -m "feat(observability): service logs dashboard, trace-logs check, docs"
```

  Open the PR into `feature/phase-13`, and stop. The user merges the phase PR (#140).

## Implementation Notes (as built)

Phase 13 shipped as PRs #141 (Task 1), #142 (Task 2) and the Task 3 PR. Where the merged code
departs from the plan above, the plan was wrong and the code is right; the Task 1 blocks above
are re-synced.

- **Images:** `grafana/loki:3.7.9`, `grafana/alloy:v1.21.0`, both pinned. Grafana stays on
  `latest` (13.2.2 when built).
- **Alloy's container filter** runs in `discovery.relabel`, whose `output` feeds
  `loki.source.docker`. As `relabel_rules` on `loki.source.docker`, the keep rule let every
  container through, labelled `service_name="unknown_service"`.
- **Loki's labels:** Loki 3 copies a `service_name` label onto every stream by default;
  `discover_service_name: []` turns that off, so the only labels are `service` and `level`.
  Grafana's Logs Drilldown app keys on `service_name`; re-enable it if that app is wanted.
- **Timestamps:** `@timestamp` is the Loki entry timestamp. Checked on a transfer line: the
  entry's `1792623370788939974` ns is the line's `2026-10-21T22:56:10.788939974Z`.
- **Request line key-value pairs:** Boot 3.5's `logstash` format writes them as top-level JSON
  fields (`http.method`, `http.path`, `http.status`, `duration_ms`), so they can be filtered with
  `| json`.
- **Grafana data sources:** `deleteDatasources: Tempo` was needed. Giving the provisioned Tempo
  a `uid` made an existing Grafana container fail to start ("data source not found").
- **Grafana access:** anonymous visitors are now Editors, not Viewers. Viewers are sent away
  from Explore, where "Related logs" and "View trace" open. Task 1 had checked the links through
  `/api/ds/query`, which doesn't need Explore, so this only showed up in Task 3's click-through.
- **First start:** Alloy replays each container's existing log history once; Loki rejects lines
  older than 7 days, so the first start logs a batch of "timestamp too old" errors. Not tuned.
- **Retention:** Loki doesn't log its retention setting at start; `/config` shows
  `retention_period: 3d` with the compactor's `retention_enabled: true`.
- **Dashboard:** the logs panel sets `prettifyLogMessage: false`. Otherwise Grafana 13 prints
  each JSON line over about ten rows.
- **Memory** (`docker stats --no-stream`): after Task 1's checks, Loki 63.5 MiB and Alloy
  56.9 MiB; after Task 3's transfers, Loki 129 MiB and Alloy 97 MiB, about 227 MiB together,
  under the ~400 MB budget.
- **`tools/check-trace-logs.ps1`:** found all five expected services and exited 0; with
  notification-service stopped, it reported that one missing and exited 1.
- **Final review fix, `check-trace-logs.ps1`:** Windows PowerShell 5.1's `Invoke-RestMethod`
  emits a JSON array as one object, so `@(...)` counted an empty `/accounts/mine` as 1 and never
  created the missing account. The script pipes the result (`| ForEach-Object { $_ }`) to unroll
  it. Reproduced against a local server returning `[]` and a two-item list (old: 1 and 1, fixed:
  0 and 2).
- **Follow-up:** `RequestLoggingFilter` and its test are five identical copies. A shared
  infrastructure module (with `JwtDecoderConfig`, `KeycloakProperties` and the build-info
  binder) is planned as its own refactor after this phase; `docs/open-items.md` §1.
