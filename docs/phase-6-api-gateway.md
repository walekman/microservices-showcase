# API Gateway Implementation Phase (Phase 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the system a single client-facing entry point. A new `gateway-service` module routes external traffic to Transfer Service (its full API — already the sole client-facing service per the design doc) and to Account Service's client-safe paths only (create/list/get an account). Account's `debit`/`credit` endpoints, and all of Fraud and Notification, get no route at all — they stay reachable only on the Docker-internal network. This phase is routing/topology only: no JWT validation, no Keycloak, no Spring Security anywhere. Auth is a deliberate, separate follow-up (Phase 7 — see Scope Boundary).

**Architecture:** A new stateless `gateway-service` module built on Spring Cloud Gateway **Server MVC** — the blocking, Servlet-based flavor of Spring Cloud Gateway (as opposed to the original WebFlux/Reactor-based one), matching the project's stated "Spring MVC, not WebFlux" stack choice and its virtual-threads convention. Routes are declared as `RouterFunction<ServerResponse>` beans (or equivalent) with explicit path predicates — only the paths a client should reach get a route; there is no blocklist filter to bypass or misconfigure, because the forbidden paths simply have no route.

**Tech Stack:** Spring Boot 3.3.4 / Java 21 (same as every other service), `spring-cloud-starter-gateway-server-webmvc`, Spring Cloud BOM `2023.0.x` (the 2023.0 release train adds explicit Spring Boot 3.3.x support — implementer pins the latest available `2023.0.x` patch via `dependencyManagement` at implementation time). No database, no Kafka, no Spring Security. springdoc-openapi is not added to this module — a gateway has no business logic of its own to document; its Swagger surface is whatever Transfer/Account already publish.

**Spec:** This document (brainstormed with the user on 2026-09-16) and [docs/microservices-showcase-design.md](microservices-showcase-design.md) §2–§4, §7 (component table, tech stack, deployment — describes the Gateway generically and bundles it with Auth; this phase's Scope Boundary supersedes that bundling by splitting Auth into its own Phase 7).

## Global Constraints

- Java 21 floor; Spring Boot 3.3.4. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
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
