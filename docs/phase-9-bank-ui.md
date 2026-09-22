# Bank UI Implementation Phase (Phase 9)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. **Read this brief critically rather than transcribing it: if something in it is wrong or impossible, report it instead of silently working around it.**

**Goal:** A self-service browser UI for a bank customer: sign up (which provisions exactly one account), log in via Keycloak, see account balance, send a transfer, browse transfer history, and quick-transfer to a previously-used account — all against the existing Gateway/Account/Transfer APIs, with two small, deliberate additions to those APIs.

**Architecture:** A new `web-ui/` folder at the repo root — plain HTML/CSS/JS, no build tooling, no framework, no Node toolchain — served by a stock `nginx:alpine` container (static bind mount, no custom image) as a new `web-ui` service in `docker-compose.yml` on its own port. The browser authenticates against Keycloak's already-present `showcase-ui` public client using the OAuth2 Authorization Code + PKCE flow, redirecting to (and back from) Keycloak's own hosted login/registration pages — which get a small custom theme so they visually match the bank UI. After token exchange, the UI calls the Gateway (a different origin) directly with `Authorization: Bearer`; the Gateway gains a CORS policy for the UI's origin but otherwise stays routing-only — no static-asset serving is added to it. Account Service gains two additions: a `mine`-scoped account list and a one-account-per-owner rule enforced at `POST /accounts`; Transfer Service gains a `mine`-scoped transfer list; Account Service also gains a narrow, explicit exception to Phase 7b's ownership rule — an any-caller "who owns this account" name lookup — so a recipient in the UI is a name, not a bare UUID.

**Tech Stack:** Plain HTML/CSS/JS served by `nginx:alpine`; no new Java framework. Backend additions are ordinary Spring MVC endpoints in the existing Account/Transfer modules (Spring Boot 3.5.16, unchanged from the rest of the project). Keycloak realm-config changes only (no custom SPI/plugin) plus a Keycloak login/registration theme (FreeMarker/HTML/CSS, mounted as a volume).

**Spec:** This document (brainstormed with the user on 2026-09-22), `docs/microservices-showcase-design.md` §2 (Services) and §7 (Deployment), `docs/phase-7-auth-keycloak-jwt.md` (JWT/Keycloak wiring this phase's auth flow builds on), and `docs/phase-7b-account-ownership-authorization.md` (the ownership model this UI's new endpoints must respect, and the one place they deliberately carve out an exception).

## Global Constraints

- Java 21 floor for the two backend modules. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- No Node/build toolchain for `web-ui/` — plain files only, served as-is. This is an explicit user requirement ("as simple as possible, I'm not a frontend developer"), not a default — do not introduce a bundler, framework, or package.json.
- Gateway stays routing-only; the only Gateway change in this phase is a CORS policy bean. Do not add static-resource serving, templates, or a UI route to `gateway-service`. (CLAUDE.md's description of Gateway; also this document's own architecture decision below.)
- No Keycloak custom SPI/event-listener plugin. Account provisioning at signup is UI-driven (onboarding screen calling `POST /accounts`), not automatic on the `REGISTER` event — see Design Decisions.
- Never commit directly to `master`; one branch per task, `feature/phase-9-task-<N>-<short-description>`, cut from a freshly fetched `origin/master`; one PR per task, stop after each; subagent review is manual, on request. (CLAUDE.md)
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review; always name the model explicitly; give smaller models explicit checklists. (CLAUDE.md)
- Sync any review fix back into this document verbatim from the merged source, not by hand. (CLAUDE.md)

## Design Decisions Worth Knowing Before You Start

**Why a separate static-file container, not served through the Gateway.** The Gateway's `SecurityConfig` gates `anyRequest()` behind `.authenticated()`, but the UI's HTML/JS must be reachable with *zero* token — it's the very page that kicks off the redirect to Keycloak. Folding it into the Gateway would mean carving `permitAll` exceptions for UI asset paths into the same filter chain that guards the money-moving API, and would make Gateway a content server, contradicting its "routing-only" role (CLAUDE.md). A standalone `nginx:alpine` container, its own origin, needs none of that — the only cost is one CORS policy on the Gateway, which is a normal, narrow addition (a `CorsConfigurationSource` bean scoped to the UI's origin and the methods/headers it actually uses).

**Why Authorization Code + PKCE, not a UI-built login form against Keycloak's password grant.** The `showcase-realm.json` already ships a public `showcase-ui` client (`publicClient: true`, `standardFlowEnabled: true`, no secret) with a comment calling it "a future login UI later" — this phase is that UI. A public browser client doing the resource-owner-password grant means the UI's own JS handles the raw password; redirecting to Keycloak's hosted page means it never does, and it's the flow Keycloak (and OAuth2 generally) actually intends for this client type. This phase adds explicit PKCE (`S256`) to the client, since a public client without PKCE is vulnerable to authorization-code interception — one realm-JSON attribute, no code-side cost given the redirect handling exists either way.

**Why `GET /accounts/{id}/summary` is a deliberate, narrow exception to Phase 7b's ownership rule, not a bypass of it.** Phase 7b made `GET /accounts/{id}` ownership-scoped specifically so a caller can't read someone else's balance. That invariant is untouched — `summary` returns only `ownerName`, never balance or `ownerId`, and is reachable by any authenticated caller. Without it, quick-transfer and history screens could only show a bare UUID for the other party, which defeats "known accounts for quick transfers" as a usable feature. The distinction (identity is discoverable, money is not) mirrors how real bank UIs show "paying John Smith" without exposing John's balance.

**Why one-account-per-owner is enforced in Account Service, not just hidden in the UI.** The user's requirement was "no adding new account for existing users" as a rule, not a UI convenience — `POST /accounts` now checks for an existing account owned by the caller and returns `409 ACCOUNT_ALREADY_EXISTS` (same `Problems.of(...)` pattern as the existing `ACCOUNT_NOT_FOUND`/`INSUFFICIENT_FUNDS` handlers) if one exists. This closes the loophole for any caller — curl, Postman, a future admin tool — not just the UI, consistent with how every other business rule in this project lives in the service, not the client.

**Why Keycloak self-registration + a UI onboarding step, not a custom Keycloak event-listener SPI.** Keycloak can trigger a REGISTER event a custom Java provider could hook to call Account Service automatically — but that means writing, packaging, and mounting a Keycloak plugin, a materially heavier build for a "simple" UI phase. Enabling `registrationAllowed` plus a UI check ("do I own zero accounts? then show onboarding") gets the same one-time, automatic-feeling result (new user always ends up with exactly one account before reaching the dashboard) with no new Java project, at the cost of the account only existing after the user's first post-registration login rather than at the instant Keycloak creates them — an acceptable gap since there is no scenario in this project where a registered-but-account-less user is observed by anything other than the UI itself.

**Why the seed balance is a fixed 1000.00, not user-entered.** User decision (2026-09-22): removes a field from the onboarding screen and matches how a real bank's opening/welcome balance works (the user doesn't get to type their own starting balance). The existing `POST /accounts` contract (`initialBalance` in the request body) is unchanged — the UI simply always sends `1000.00` on the onboarding call, never exposing the field.

**Why quick-transfers are derived from transfer history client-side, not a new "saved beneficiaries" table.** User decision (2026-09-22): the existing `Transfer` history already contains every account the user has sent money to; a distinct-`toAccountId`, most-recent-first computation over `GET /transfers/mine` gets "known accounts for quick transfers" with no new persistence, no add/remove UI, and no new concept to design, test, or explain.

## New Backend Endpoints

| Endpoint | Service | Auth | Returns | Purpose |
|---|---|---|---|---|
| `GET /accounts/mine` | Account | any authenticated user (own accounts only, filtered by `ownerId` from JWT) | list of the caller's accounts (today: exactly zero or one) | Dashboard |
| `GET /accounts/{id}/summary` | Account | any authenticated user, **no ownership check** | `{ ownerName }` only — no balance, no `ownerId` | Recipient display name in quick-transfers/history |
| `GET /transfers/mine` | Transfer | any authenticated user (filtered by `initiatorId` from JWT; reuses the existing optional `status` filter) | list of the caller's transfers | Transfer history; quick-transfers derives from this client-side |

## New Backend Enforcement

`POST /accounts` (Account Service): if the caller (`ownerId` = JWT subject) already owns an account, reject with `409 Conflict`, `code: ACCOUNT_ALREADY_EXISTS`, following the existing `Problems.of(HttpStatus, code, title, detail)` pattern in `ApiExceptionHandler`/`Problems`.

## Keycloak Changes (`docker/keycloak/showcase-realm.json`)

- `showcase-ui` client: add `attributes: { "pkce.code.challenge.method": "S256" }`.
- Realm: `registrationAllowed: true` (self-service signup).
- New users from self-registration get the `customer` composite role by default (Keycloak "default roles" mechanism) — without this, a freshly registered user has no realm roles and every API call 403s.
- New login/registration theme (`docker/keycloak/themes/bank-ui/...`) mounted into the Keycloak container, giving both pages the bank UI's look — matching the user's "same bank app login page" expectation from brainstorming. Scope is visual only (colors/logo/copy in the existing Keycloak templates) — no custom registration fields, no email verification flow (no mail server in this stack).

## UI Screens & Flows

- **Load / Login:** on page load, check for a valid token; if absent, redirect immediately to Keycloak's (themed) login/registration page. On return with an authorization `code` in the URL, exchange it for tokens (PKCE) and store the token in `sessionStorage`.
- **Onboarding (first login only):** call `GET /accounts/mine`; if empty, show a one-time screen (owner name only — balance is fixed at 1000.00, not a field) that calls `POST /accounts`, then proceeds to the dashboard. This screen never reappears once the caller owns an account, and there is no "add another account" affordance anywhere in the UI.
- **Dashboard:** the caller's single account and its balance (from `GET /accounts/mine`), and a "Send money" action.
- **New Transfer:** source is fixed (the caller's one account); destination is a free-text account ID or a one-click pick from the Quick Transfers panel; amount; submit via `POST /transfers`. Success shows the result; failure surfaces the RFC 7807 `code`/`detail` (see Error Handling).
- **Transfer History:** table from `GET /transfers/mine`, optionally filtered by status; each row's counterparty resolved to a name via `GET /accounts/{id}/summary`.
- **Quick Transfers panel:** client-side computation over `GET /transfers/mine` — distinct `toAccountId` values, most-recent first, each labeled via `summary`; clicking one prefills the New Transfer form.
- **Logout:** clear the stored token, redirect to Keycloak's end-session endpoint.

## Auth & Token Lifecycle

Authorization Code + PKCE against the `showcase-ui` client. Token held in `sessionStorage` (not `localStorage` — cleared when the tab closes, a reasonable default for a banking demo). Every Gateway call attaches `Authorization: Bearer <token>`. A `401` response from any call clears the stored token and redirects to Keycloak login (covers both expiry and an invalid/tampered token) — no silent refresh-token handling in this phase (see Scope Boundary).

## Error Handling

The UI keeps a small `code → friendly message` map (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_FOUND`, `ACCOUNT_BLOCKED` or whatever Fraud's actual block code is — confirm against `fraud-service`'s `ApiExceptionHandler` during implementation, `ACCOUNT_ALREADY_EXISTS`, `ACCOUNT_SERVICE_UNAVAILABLE`, `TRANSFER_...` codes) read from the RFC 7807 body's `code` property, with a generic fallback message for anything unmapped — never branches on `detail` prose, consistent with the project's stated error contract (CLAUDE.md, `microservices-showcase-design.md` §3).

## Testing

No new JS test framework/toolchain (user decision, 2026-09-22: "manual testing is fine for now"). Manual verification via a running `docker compose up` and a browser, covering: registration → onboarding → dashboard; a successful transfer; a fraud-blocked transfer; an insufficient-funds transfer; quick-transfer prefill; logout and re-login; a second `POST /accounts` attempt correctly rejected.

On the Java side, ordinary additions to the existing per-service Testcontainers IT suites (`./mvnw test`), no new test module: an IT for `GET /accounts/mine`, an IT for `GET /accounts/{id}/summary` (including the cross-owner case, since that's the deliberate exception), an IT for the `409 ACCOUNT_ALREADY_EXISTS` case, and an IT for `GET /transfers/mine`.

## Scope Boundary

Deliberately not in this phase:

- **Multi-account support.** One account per user, created once at signup, is a hard rule this phase enforces server-side — not a placeholder for later. Revisiting it is a new design decision, not a natural extension.
- **Saved/explicit beneficiaries.** Quick-transfers is derived from history only (see Design Decisions); no add/remove/label UI or storage.
- **Keycloak custom event-listener SPI** for automatic account provisioning at the instant of registration (see Design Decisions) — the UI-driven onboarding step is the whole mechanism.
- **Automated browser/JS tests** (e.g. Playwright) — manual verification only, user decision 2026-09-22. A follow-up phase could add this without touching the UI's own code.
- **Silent token refresh.** A `401` re-triggers the full login redirect; no refresh-token rotation or background renewal.
- **Email verification / password reset / "forgot password" flows** — no mail server in this stack; self-registration is username/password only, matching the existing demo users' setup.
- **Admin views in this UI** (list-all accounts/transfers) — those already exist as separate `account-admin`/`transfer-admin`-gated APIs (Phase 7b) with no UI of their own; out of scope here, which is a customer-only UI.
- **Keycloak theming beyond login/registration** (e.g. account-management console, email templates) — only the two pages the customer flow actually touches.

## Roadmap Changes

`docs/roadmap.md`: insert a new row **9** ("Bank UI", this phase, "Not started"); the former row 9 ("End-to-End Saga Tests") becomes row **10**, filename references updated to `docs/phase-10-end-to-end-saga-tests.md` wherever it is eventually written (no such file exists yet — only the roadmap row and two mentions in `docs/phase-8-observability.md` referred to it as "Phase 9", both updated to "Phase 10" as part of this change). `docs/microservices-showcase-design.md` §2 (Services table) gains a **Bank UI** row (stateless, static assets); §7 (Deployment) gains a line noting the new `web-ui` container.
