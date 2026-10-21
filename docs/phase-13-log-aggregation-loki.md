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
  - Retention is configured (the compactor logs its retention setting at start).
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
