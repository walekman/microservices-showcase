# Shared Platform Module (Phase 14)

**Goal:** The near-identical infrastructure classes copied across the services live once, in one
shared module that every service depends on. The services keep only what is specific to them.
The motivation is code hygiene: nothing is broken, but five or six copies of the same class are
noise, and a fix has to be repeated in each copy.

**Architecture:** A new Maven module in the reactor, working name `platform`, versioned and
built with the services (no separate release). It holds the cross-cutting infrastructure a
microservice chassis usually holds (Chris Richardson's "microservice chassis" pattern):
request logging, JWT decoding, Keycloak role mapping, the build-info metric, and the generic
part of RFC 7807 error handling. It never holds a service's contract: no DTOs, no event
payloads, no domain types, no service-specific error codes, no endpoint paths, no role names.
Each service picks up the pieces it uses by depending on the module, and can replace any piece
with its own.

```
account ─┐                  ┌─ request log line
transfer ─┤                  ├─ JWT decoder + Keycloak properties
fraud    ─┼──▶  platform  ──┼─ realm-role → authority mapping
fx       ─┤                  ├─ application_info build metric
gateway  ─┤                  └─ generic RFC 7807 error handling
notification ─(build metric only)
```

**This reverses a standing decision.** Phases 2, 7 and 8b duplicated these classes on purpose,
citing the design doc's "no shared module" stance: a shared jar turns every contract change into
a lockstep redeploy of every service. That argument is about *contracts*, and it still holds for
them. It doesn't apply to infrastructure that carries no contract, which is what large
microservice estates commonly share (Zalando's `problem-spring-web` is the closest prior art for
the error-handling part). The design doc's §3 now states the rule: share infrastructure, never
contracts.

**Spec:** This document, brainstormed with the user on 2026-10-28. The task-by-task
implementation is added to it once this design is approved.

## Global Constraints

- Phase workflow (CLAUDE.md, from Phase 10): this phase lives on `feature/phase-14`, which starts
  with this spec commit and has a draft PR into `master`. Each task branches off
  `feature/phase-14` and gets its own PR back into it.
- Java 21; Spring Boot 3.5. Use `C:\dev\openjdk-21.0.2` as `JAVA_HOME`. (CLAUDE.md)
- **Ignore PR #138** (Account Service in hexagonal architecture) until the user says otherwise.
  This phase is designed and built against `master`'s layout. Don't wait for #138, don't
  rebase onto it, and don't adapt to its moved `ApiExceptionHandler`/`Problems`.
- **A refactor, not a feature.** Every response status and `code`, header, log line and metric
  a client or dashboard can observe today stays the same. The error-handling alignment below
  (decided by the user) closes gaps no current endpoint reaches. A change that
  seems to need more than that: stop and report it; it is a scope change for the user to decide.
- **No service gains a dependency it doesn't already have.** In particular,
  notification-service must not end up with Spring Security or MVC error handling on its
  classpath or in its context just because it depends on the module.
- **Contracts never enter the module.** If something only fits in the module by naming a
  service, an endpoint, a role or a domain type, it stays in the service.
- **Every build path keeps working:** `./mvnw -B test` (CI), `docker compose up -d --build` for
  each service (every Dockerfile builds its service from the repo root and must include the
  module), and `./mvnw -Pe2e -pl e2e-tests verify`.
- Configuration stays as it is: the `keycloak.issuer-uri`/`keycloak.jwk-set-uri` properties keep
  their names and values, and no `docker-compose.yml` or environment change is needed.

## Behaviour

### What moves into the module

| Piece | Today | Used by |
|---|---|---|
| Request log line (`RequestLoggingFilter`) | 5 identical copies | gateway, account, transfer, fraud, fx |
| JWT decoder (`JwtDecoderConfig`) and its settings (`KeycloakProperties`) | 5 identical copies | gateway, account, transfer, fraud, fx |
| Keycloak realm roles → authorities (`KeycloakRealmRoleConverter`) | 5 copies, identical code | gateway, account, transfer, fraud, fx |
| `application_info` build metric (`BuildInfoMetricsConfig`) | 6 identical copies | all six |
| Generic error handling (below) | 4 partly overlapping copies | account, transfer, fraud, fx |

Each piece keeps the behaviour it has today. The javadoc that explains *why* each one is built
the way it is (the internal/external Keycloak address split, the filter's position in the chain,
the info-metric convention) moves with it. Those comments still matter.

### Generic error handling

The module owns the RFC 7807 *format* and the *generic* error vocabulary. Each service owns its
own codes.

- **In the module:** building a problem response (the `type` URI scheme, `title`, `code`,
  `timestamp`); the four generic codes `VALIDATION_FAILED`, `MALFORMED_REQUEST`,
  `REQUEST_REJECTED` and `INTERNAL_ERROR`; and the handling that produces them for request-shape
  errors no service-specific handler covers: bean-validation failures on a body or on a
  header/path parameter, a missing header, a missing query parameter, an unreadable body, a
  type mismatch, the catch-all 500 (logged), and the guard that stamps a `code` on every other
  Spring MVC error so the property is never absent.
- **In each service:** its own exceptions and codes (`ACCOUNT_NOT_FOUND`,
  `TRANSFER_IN_PROGRESS`, `ACCOUNT_BLOCKED`, `RATE_UNAVAILABLE`, and the rest), mapped with the
  module's problem builder.
- **Precedence:** a service's own handler always wins over a generic one, the catch-all
  included, and a service can replace any generic handler with its own. A generic handler must
  never swallow an exception a service maps.

**Aligned across services (user's decision).** Today the four handlers cover different
subsets of these cases. Fraud and FX have no body, validation or header handling. FX has no
type-mismatch handling. Account and Transfer have no missing-query-parameter handling. After
this phase all four handle every case above the same way.

No response a client can trigger today changes. Each gap sits where that service's current
endpoints can't reach it:

| Gap | Service | Why it's unreachable today |
|---|---|---|
| Body unreadable or failing validation | fraud, fx | No endpoint takes a request body |
| Missing header, header/path validation | fraud, fx | No endpoint takes a header or a validated parameter |
| Type mismatch | fx | Its only parameters (`base`, `quote`) are strings |
| Missing query parameter | account, transfer | Transfer's only query parameter (`status`) is optional; Account has none |

The alignment matters for the next endpoint each service gains, which then gets the right
generic code instead of `REQUEST_REJECTED` or a 500. Where a service already answers a case,
the generic answer must match today's status and `code`. The `detail` prose may change
wording, and consumers never branch on it (design doc §3). If the builder finds a reachable
case whose status or `code` changes, it goes back to the user.

### What stays in each service

- `SecurityConfig`: each service's authorization rules and its `no-auth` profile chain. They
  use the module's decoder and role mapping, but the rules themselves are the service's
  contract.
- Each service's `ApiExceptionHandler`, reduced to its own exceptions.
- All DTOs and event payloads, including both `TransferStatus` copies (Notification's
  deliberately lacks `PENDING` and `COMPENSATION_REQUIRED`).
- Per-service wiring tests: `BuildInfoIT`, `OpenApiDocsIT`, `TracingBridgeIT`, and each
  controller test asserting its error codes. They prove the shared piece is wired into *that*
  service, which a test in the module can't.

### What a service sees

Adding the module dependency is enough to get today's infrastructure behaviour. No
per-service glue code is needed for the request log line, the decoder, the role mapping or the
build metric. A service that needs something different declares its own bean and the module's
backs off.

## Design Decisions

- **One module, not one per concern.** Five small pieces don't justify five artifacts. The
  requirement that notification-service gains nothing it doesn't use is met inside the one
  module. How is left to the builder.
- **Same version as the services, built in the same reactor.** A separately versioned
  library is what lets a large estate upgrade services one at a time. Here every service
  builds from one commit anyway, so a platform change reaches all of them at once. That is
  acceptable *only because* the module carries no contract, which is why the
  contracts-never rule is a hard constraint and not a guideline.
- **Gateway gets the infrastructure pieces, not the error handling** (my call, flagged for the
  user). The gateway has no controllers of its own. Its error bodies today come from Spring
  Boot and Spring Cloud Gateway, and proxied responses pass through untouched. Giving it the
  generic handlers would change its own error responses, which this phase doesn't set out to do.
- **The request log line's logger name changes** from five per-service names to the module's
  one name (my call). The `service` label in Loki already says which service wrote it, and
  nothing in `docker/` or `tools/` filters on the logger name (checked). The message text and
  fields stay identical.
- **Generic codes belong to the platform, specific codes to the service.** The four generic
  codes are the same vocabulary everywhere. They describe the request's shape, not anything
  about a service's domain.

## Scope Boundary

- Sharing anything contract-shaped: DTOs, event payloads, `TransferStatus`, Account's or
  Transfer's request/response types. Those stay per service by design.
- A shared `SecurityConfig` or a shared rule set, including the `no-auth` profile.
- Error handling in the gateway (see Design Decisions).
- Sharing test code between services' test suites (a shared test-support module). The
  per-service wiring tests stay as they are.
- Publishing the module anywhere (a registry, a separate release).
- PR #138 (see Global Constraints).

## Delivery

| Task | Branch | Scope |
|---|---|---|
| 1 | `feature/phase-14-task-1-platform-infrastructure` | The module in the reactor and in all six Dockerfiles; the request log line, JWT decoder and its settings, role mapping and build metric move into it; every service's copies deleted; the moved tests consolidated in the module |
| 2 | `feature/phase-14-task-2-generic-error-handling` | The problem builder and generic handlers in the module; the four services' handlers reduced to their own cases, all four aligned on the full generic set; docs |

Task 2 needs Task 1's module.

Docs updated alongside:
- `docs/microservices-showcase-design.md`: §3's error-contract bullet and the new shared-code
  bullet (with this spec)
- `docs/roadmap.md`: a Phase 14 row (added with this spec, updated when the phase finishes)
- `docs/open-items.md`: #7 points here (with this spec); it comes off when the phase lands
- `CLAUDE.md`: the module in the project overview
- `README.md`: the module list, if it lists modules
- The phase docs that justified duplication (2, 7, 8b, 13) are history and stay as written;
  the design doc is what changes

## Testing

- **The module tests each piece once.** The five `RequestLoggingFilterTest` copies become one.
  Every generic error case is tested in the module.
- **Each service proves the wiring.** The existing per-service tests pass with their status and
  `code` assertions unchanged. An assertion on `detail` prose may be reworded, never deleted.
  Each of the four API services has at least one test showing a generic error case comes from
  the module, and one showing its own handler still wins over the catch-all.
- **notification-service proves what it doesn't get:** no security filter chain, no generic
  error handling in its context.
- **Live:** `tools/check-trace-logs.ps1` still passes (request line, with `traceId`, in every
  hop), `/actuator/info` and `application_info` still report the build on all six services, and
  one transfer through the Bank UI still works with a real token.
- Full suite `./mvnw test` and the E2E suite both green, run by the coordinator, not only
  reported by a subagent (CLAUDE.md).

## Review Focus

1. **Handler precedence.** A shared catch-all registered ahead of a service's handler compiles,
   starts and passes most tests, then turns `ACCOUNT_NOT_FOUND` into a 500. Check that every
   service-specific code still comes back, not just that generic ones do.
2. **Nothing leaks into notification-service.** Check its context and dependency tree, not just
   that it starts.
3. **The request line still carries `traceId`.** Its position in the filter chain is the whole
   point of Phase 13's ordering note, and it must survive the move.
4. **Every Dockerfile builds** with `--build`, not only the Maven reactor.
5. **No contract crept in:** no service name, path, role or domain type in the module.
6. **No status or `code` assertion changed** in any per-service test. One that did is a
   behaviour change the user didn't agree to.
