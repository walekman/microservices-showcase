# Auth (Keycloak/JWT) Implementation Phase (Phase 7)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the system real authentication and coarse authorization. A new `keycloak` container issues JWTs; Gateway, Account, Transfer, and Fraud each independently validate them (Spring Security OAuth2 Resource Server) rather than trusting that "the Gateway already checked it." Authorization stays coarse — capability-scoped realm roles checked directly in each service, no per-account ownership. Notification Service is untouched (it has no HTTP endpoints). Per-account ownership (a user can only touch accounts they own) is a deliberately separate follow-up — see Scope Boundary.

**Architecture:** One Keycloak realm (`showcase`), imported automatically on container startup from a checked-in realm-export JSON — no manual admin-console configuration, consistent with this project's "pre-configured, not hand-clicked" approach to every other piece of infrastructure. Two Keycloak clients: `showcase-ui` (public, represents any human caller — today's curl/Postman testing and a future login UI) and `transfer-service` (confidential, service-account-enabled — Transfer Service's own machine identity for calls that have no end-user request to draw a token from). Authorization is capability-scoped realm roles (`transfer-executor`, `account-reader`, `account-editor`, `fraud-checker`), not a single coarse "customer" check baked into each service — `customer` exists only as a composite role bundling all four for real users; every service checks one specific permission, never the composite name.

**Tech Stack:** Keycloak (official image, exact patch version verified at implementation time — see Design Decisions), `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` on Gateway/Account/Transfer/Fraud, `spring-boot-starter-oauth2-client` additionally on Transfer (the only service that mints its own tokens). Testcontainers-based integration tests use a community Keycloak Testcontainers module (exact coordinates/version verified at implementation time, same reasoning).

**Spec:** This document (brainstormed with the user on 2026-09-17) and [docs/microservices-showcase-design.md](microservices-showcase-design.md) §2–§4 (component table, tech stack — "Auth: Keycloak (OAuth2/OIDC), JWT validated at the Gateway and by each resource service via Spring Security Resource Server," which this phase implements) and `docs/phase-6-api-gateway.md`'s Scope Boundary (which deliberately deferred all of this here).

## Global Constraints

- Java 21 floor; Spring Boot 3.3.8. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- Spring MVC (blocking), not WebFlux; virtual threads enabled on every service, unchanged by this phase.
- Never commit directly to `master`; work happens on `feature/phase-7-task-<N>-*` branches, one PR per task, stop after each. Subagent review is manual, on request — not automatic. (CLAUDE.md)
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review — this phase's "money-creation bug" equivalent is a role check that's silently missing or inverted on a debit/credit path, exactly the class of defect Phase 2's high-stakes final review was for. Always name the model explicitly. (CLAUDE.md)
- **Do not add per-account ownership checks in this phase.** `Account` keeps its free-text `ownerName` field, unchanged. If an implementer notices "any customer can debit any account" and is tempted to fix it here, that's Phase 7b's job, not this phase's — see Scope Boundary for why they were split.

## Design Decisions Worth Knowing Before You Start

**Why capability-scoped permissions (`transfer-executor`, `account-reader`, `account-editor`, `fraud-checker`) instead of one coarse role.** An earlier iteration of this design used a single blanket "is this a trusted caller" check. That's wrong for two reasons found during brainstorming: (1) it can't express that `transfer-service`'s machine identity should be able to move money but never needs to create or list accounts, and (2) it can't express that a real user's own token needs to satisfy Account's `debit`/`credit` check too — not because a human calls those endpoints directly (there's no Gateway route to them, per Phase 6), but because the live saga *relays* the calling user's own token when Transfer Service calls Account on their behalf. Capability roles make both of these precise: `customer` (assigned to real users) is a **composite role** that expands to all four permissions, so a relayed user token already carries `account-editor` and `fraud-checker` without any per-endpoint special-casing; `transfer-service`'s service account is granted `account-editor` + `fraud-checker` **directly** (not as a composite — see below), since it never initiates a transfer and never reads an account (verified against `AccountClient`/`CompensationScheduler`'s actual call sites, not assumed).

**Why `transfer-service` gets direct role grants, not its own composite role.** A composite named `transfer-service` would collide, confusingly, with the *client* already named `transfer-service` (same realm, two different Keycloak object kinds, same name) — and composites only pay for themselves through reuse across many identities, which a single service-account grant of exactly two roles doesn't have. If a later phase adds another service needing the identical two-permission bundle, a shared composite becomes worth introducing then, not speculatively now.

**Why `account-editor` covers both account creation and debit/credit, but `account-reader` is split out.** Reads (`GET /accounts`, `GET /accounts/{id}`) and mutations (`POST /accounts`, `debit`, `credit`) are a meaningfully different risk class worth distinguishing — `transfer-service`'s machine identity, confirmed by reading `CompensationScheduler`, never calls a `GET` endpoint at all (it only replays `debit`/`credit`/fraud-check), so it has no reason to hold `account-reader`. Splitting creation out from `account-editor` into its own fourth permission was considered and rejected: nothing in this phase's callers needs that finer distinction, and inventing a permission with zero consumers is exactly the premature-abstraction YAGNI calls out.

**Why token relay + client-credentials fallback, not one uniform mechanism, for Transfer's outbound calls.** `AccountClient`/`FraudClient` are invoked from two different contexts: the live saga (an inbound Gateway-forwarded user request, with a `SecurityContext` already populated by Transfer's own Resource Server filter) and `CompensationScheduler`'s `@Scheduled` sweep (a background thread with no inbound request, hence no user token to relay at all). A single `ClientHttpRequestInterceptor`, registered once via a `RestClientCustomizer` bean (Spring Boot auto-applies it to every injected `RestClient.Builder`, so it reaches both `AccountClientConfig` and `FraudClientConfig` without editing either), picks the current user's JWT off `SecurityContextHolder` when one exists, and falls back to fetching (and Spring-cached-and-refreshed) a `transfer-service` client-credentials token via `AuthorizedClientServiceOAuth2AuthorizedClientManager` when it doesn't. `AccountClient`/`FraudClient` themselves need no changes — the header is attached before either class's code runs.

**Why each service uses a manually-configured `JwtDecoder` instead of the single `issuer-uri` property.** Keycloak issues tokens whose `iss` claim is fixed to wherever `KC_HOSTNAME` says it is; the four resource servers reach Keycloak as `keycloak:8080` (Docker Compose's internal DNS) while a human on the host reaches it as `localhost:8180`. Setting `KC_HOSTNAME` to the host-facing address and using the single `issuer-uri` property would make every container try to dereference `localhost:8180` for OIDC discovery, which fails (a container's `localhost` is itself, not the host) — and the reverse (pointing `issuer-uri` at `keycloak:8080`) would make tokens obtained from the host-facing URL fail issuer validation. The fix, standard for exactly this split, decouples the two concerns: a `NimbusJwtDecoder` built from `jwkSetUri` = the internal `http://keycloak:8080/realms/showcase/...` address (an actual network call, container-to-container, works over Compose's DNS) with its validator swapped for `JwtValidators.createDefaultWithIssuer(...)` against the external `http://localhost:8180/realms/showcase` string (never dereferenced — just compared against the token's `iss` claim). This avoids editing the host machine's hosts file, which was a hard requirement from this brainstorm. Duplicated per service (one small `JwtDecoderConfig` class each) rather than extracted into a shared module, consistent with this project's existing "duplicate small per-service config rather than force a lockstep-redeploy shared module" precedent (see the RFC 7807 handler in the design doc's Tech Stack section).

**Why Swagger UI gets `permitAll` rather than its own `oauth2Login` redirect.** Redirecting an unauthenticated browser hit on `/swagger-ui/**` to Keycloak's login page needs a second, session-cookie-based security filter chain (`oauth2Login()`) living alongside each service's otherwise fully stateless `oauth2ResourceServer()` chain, plus its own OAuth2 client registration and a Keycloak-registered redirect URI per service — real added complexity for a benefit (hiding the *existence* of documented endpoints) that `permitAll` doesn't actually give up much of: the docs page and OpenAPI JSON become visible, but no data and no ability to call anything, since "Try it out" still needs a real bearer token via a configured OpenAPI OAuth2 `SecurityScheme` and springdoc's `Authorize` button.

**Why `showcase-ui` gets both Standard Flow and Direct Access Grants enabled now.** A real login UI is planned for a future phase and will use Authorization Code + PKCE against Keycloak's own hosted login page. Enabling Direct Access Grants (password grant) on that same client today, alongside Standard Flow, means curl/Postman/integration tests can get a token now with `grant_type=password` against the two demo users, and the future UI phase just starts using the redirect flow against the same client/realm — no realm rework needed when that phase starts. This is realm/client configuration only; no UI code is written in this phase.

## Keycloak Realm

Realm `showcase`, imported from a checked-in export on Keycloak container startup.

**Clients:**

| Client | Type | Grant(s) | Purpose |
|---|---|---|---|
| `showcase-ui` | Public | Standard Flow (Authorization Code + PKCE) + Direct Access Grants (password) | Represents any human caller — curl/Postman today, a future login UI later, same client for both. |
| `transfer-service` | Confidential, service accounts enabled | Client Credentials | Transfer Service's own machine identity, used only when there's no inbound user request to relay a token from (`CompensationScheduler`'s sweep). |

**Realm roles (permissions):**

| Role | Kind | Granted to |
|---|---|---|
| `transfer-executor` | permission | `customer` (via composite) |
| `account-reader` | permission | `customer` (via composite) |
| `account-editor` | permission | `customer` (via composite), `transfer-service` (direct grant) |
| `fraud-checker` | permission | `customer` (via composite), `transfer-service` (direct grant) |
| `customer` | composite role, expands to all four permissions above | demo users |

**Demo users:** two users (e.g. `ada` / `bob`, continuing this project's existing example naming from Phase 6's README) with the `customer` role and a fixed demo password, seeded in the realm export for manual testing and for a future login UI to authenticate against.

## Per-Endpoint Authorization Matrix

Every service below independently validates the JWT (signature, expiry, issuer) — no service trusts that the Gateway already checked it. `/actuator/health` stays `permitAll` everywhere (Docker Compose's healthchecks curl it unauthenticated today; breaking that breaks `docker compose up`). Swagger's `/swagger-ui/**` and `/v3/api-docs/**` stay `permitAll` on Account/Transfer/Fraud (see Design Decisions).

| Service | Endpoint | Required permission |
|---|---|---|
| Gateway | `POST /transfers/**`, `GET /transfers/**` | `transfer-executor` |
| Gateway | `GET /accounts`, `GET /accounts/{id}` | `account-reader` |
| Gateway | `POST /accounts` | `account-editor` |
| Account | `GET /accounts`, `GET /accounts/{id}` | `account-reader` |
| Account | `POST /accounts`, `POST /accounts/{id}/debit`, `POST /accounts/{id}/credit` | `account-editor` |
| Transfer | `POST /transfers`, `GET /transfers`, `GET /transfers/{id}` | `transfer-executor` |
| Fraud | `GET /fraud-check` | `fraud-checker` |
| Notification | n/a | No HTTP endpoints exist (Kafka-only) — untouched by this phase. |

## Token Propagation

- **Gateway → Account/Transfer:** Gateway validates the JWT itself, then proxies via `spring-cloud-starter-gateway-mvc`'s `http()` handler, which forwards request headers (including `Authorization`) unchanged today — this needs to be **proven with an integration test**, not assumed (see Testing), since it's load-bearing for every downstream Resource Server check to even see a token.
- **Transfer → Account/Fraud:** one `AuthorizationPropagatingInterceptor` (`ClientHttpRequestInterceptor`), attached to every `RestClient.Builder` via a single `RestClientCustomizer` bean. Relays the current request's JWT from `SecurityContextHolder` when present (live saga path); falls back to a cached/auto-refreshed `transfer-service` client-credentials token via `AuthorizedClientServiceOAuth2AuthorizedClientManager` when absent (`CompensationScheduler`'s background path). See Design Decisions for why both paths are needed.

## Docker Compose Wiring

- New `keycloak` service: official Keycloak image (exact tag verified against what's actually current when this phase is implemented, not assumed from this brainstorm), `start-dev --import-realm`, realm export mounted read-only, port `8180:8080`, `KC_HOSTNAME` fixed to the host-facing address (exact env var names/values finalized during implementation — see Design Decisions for the split-issuer approach they need to support).
- Gateway, Account, Transfer, Fraud each get `depends_on: { keycloak: { condition: service_healthy } }` — their Resource Server auto-config fetches JWKS from Keycloak at startup.
- Each service's `application.yml` gets its `JwtDecoder` wiring (internal JWKS URI + external issuer string) and, for Transfer only, the `transfer-service` OAuth2 client registration (client ID, secret, token-endpoint URI).

## Testing

- **Existing tests break, deliberately, until fixed as part of this phase.** Every existing MockMvc-based controller test across Gateway/Account/Transfer/Fraud calls endpoints with no security context; once the filter chain is live they start failing with `401`. Each needs `spring-security-test`'s `SecurityMockMvcRequestPostProcessors.jwt().authorities(...)` carrying the right permission for that test's scenario.
- **New authorization tests per protected endpoint:** no token → `401`; valid token missing the required permission → `403`; valid token with it → `200`.
- **Gateway header-forwarding integration test:** an actual round-trip proving `Authorization` survives the proxy hop unchanged — the propagation mechanism this whole phase depends on, not something to leave implicit.
- **Realm-backed integration tests:** Testcontainers Keycloak (community module, exact coordinates verified at implementation time) using the same realm-export JSON as Compose, so tests exercise the real role/composite configuration rather than a hand-mocked JWT with hand-picked claims.
- **End-to-end:** none new in this phase beyond the above — the Phase 8-planned E2E suite (design doc §6) will be the first to exercise the full stack through a real Keycloak-issued token.

## Scope Boundary

**In scope:** Keycloak container + realm import; JWT validation (Resource Server) on Gateway/Account/Transfer/Fraud; the four capability permissions + `customer` composite; token relay + client-credentials fallback for Transfer's outbound calls; Swagger `permitAll` + OAuth2 `SecurityScheme` for "Try it out"; Docker Compose wiring; roadmap updates.

**Explicitly out of scope** — named follow-ups, not oversights:

| Deferred | Why not now | Lands in |
|---|---|---|
| Per-account ownership (a user can only act on accounts they own) | Materially bigger scope than JWT wiring alone — needs an owner-id column on `Account` (today just free-text `ownerName`), a way to bind account creation to the caller's JWT subject, and enforcement in both Account and Transfer. Flagged and deliberately split out during this brainstorm, same pattern Phase 6 used to split Auth out of Gateway. | Phase 7b (new) |
| Real login UI | Future work the user mentioned wanting eventually (username/password login for a transfers/balance UI); today's `showcase-ui` client config is deliberately forward-compatible with it (Standard Flow already enabled) but no UI code exists yet. | Not yet scheduled |
| CORS, refresh-token handling, logout/session management | All meaningless without a real UI making cross-origin browser calls; revisit when the UI phase starts. | Future UI phase |
| Keycloak fine-grained "Authorization Services" (resource/scope/policy engine) | Realm roles + composites already express everything this project needs; the heavier engine solves a problem (per-resource dynamic policies) that per-account ownership will actually need, and even then as app-level logic (JWT `sub` vs. an owner-id column), not Keycloak policies. | Not currently planned |
| `oauth2Login` redirect for Swagger UI | Needs a second stateful filter chain + per-service OAuth2 client registration for a benefit (hiding docs, not data) `permitAll` mostly already covers. | Not currently planned — revisit only if hiding the docs page itself becomes a real requirement |

## Roadmap Changes

- `docs/roadmap.md` Phase 7 row: status to "✅ Done" once merged, link added, scope description updated to match what actually shipped (same pattern as every prior phase's roadmap sync).
- New Phase 7b row inserted directly after Phase 7: "Account Ownership Authorization" — owner-id column on `Account`, binding account creation to the caller's JWT subject, ownership enforcement in Account and Transfer. Phase 8 (Observability) keeps its number; 7b slots in without renumbering anything after it.
