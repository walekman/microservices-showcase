# Bank UI Implementation Phase (Phase 9)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this phase task-by-task. **Read this brief critically rather than transcribing it: if something in it is wrong or impossible, report it instead of silently working around it.**

**Goal:** A self-service browser UI for a bank customer: sign up (which provisions exactly one account), log in via Keycloak, see account balance, send a transfer, browse transfer history, and quick-transfer to a previously-used account — all against the existing Gateway/Account/Transfer APIs, with two small, deliberate additions to those APIs.

**Architecture:** A new `web-ui/` folder at the repo root — plain HTML/CSS/JS, no build tooling, no framework, no Node toolchain — served by a stock `nginx:alpine` container (static bind mount, no custom image) as a new `web-ui` service in `docker-compose.yml` on its own port. The browser authenticates against Keycloak's already-present `showcase-ui` public client using the OAuth2 Authorization Code + PKCE flow, redirecting to (and back from) Keycloak's own hosted login/registration pages — which get a small custom theme so they visually match the bank UI. After token exchange, the UI calls the Gateway (a different origin) directly with `Authorization: Bearer`; the Gateway gains a CORS policy for the UI's origin but otherwise stays routing-only — no static-asset serving is added to it. Account Service gains two additions: a `mine`-scoped account list and a one-account-per-owner rule enforced at `POST /accounts`; Transfer Service gains a `mine`-scoped transfer list; Account Service also gains a narrow, explicit exception to Phase 7b's ownership rule — an any-caller "who owns this account" name lookup — so a recipient in the UI is a name, not a bare UUID.

**Tech Stack:** Plain HTML/CSS/JS served by `nginx:alpine`; no new Java framework. Backend additions are ordinary Spring MVC endpoints in the existing Account/Transfer modules (Spring Boot 3.5.16, unchanged from the rest of the project). Keycloak realm-config changes only (no custom SPI/plugin) plus a Keycloak login/registration theme (FreeMarker/HTML/CSS, mounted as a volume).

**Spec:** This document (brainstormed with the user on 2026-09-22), `docs/microservices-showcase-design.md` §2 (Services) and §7 (Deployment), `docs/phase-7-auth-keycloak-jwt.md` (JWT/Keycloak wiring this phase's auth flow builds on), and `docs/phase-7b-account-ownership-authorization.md` (the ownership model this UI's new endpoints must respect, and the one place they deliberately carve out an exception).

## Implementation Status

**Phase 9 is now implemented and merged.** All ten tasks (Tasks 1–10) have been merged to `master`. One deviation discovered during Task 9 implementation is worth recording: the `renderHistory` code block below (under Task 9) originally showed a raw account ID in the transfer history's "To" column (line 1581, `t.toAccountId`), contradicting this document's own Design Decisions that specify counterparty names, not IDs. During implementation, this was caught and fixed to resolve the recipient name via `GET /accounts/{id}/summary`, matching the pattern used in `renderQuickTransfers`. The corrected implementation (resolving names before rendering) is in `web-ui/js/app.js` on `master`, not literally what this document's historical code block shows — if this document is used as a reference for a similar future UI, use the corrected approach, not the original code block.

Task 10's whole-branch review fix wave then changed this same block further: `renderHistory`'s `nameByAccountId[...]` interpolation and `renderDashboard`'s `${account.ownerName}` are now passed through an `escapeHtml` helper (stored `ownerName` values are another self-registered user's free-text input, so they're untrusted and were a stored-XSS vector via `innerHTML`); and a successful transfer's confirmation now survives the dashboard re-render via an optional `flashMessage` option on `renderDashboard`, instead of being wiped immediately by the post-transfer `renderDashboard(refreshedAccounts[0])` call. The Task 9 code block below has been re-synced verbatim from that fixed source.

## Global Constraints

- Java 21 floor for the two backend modules. Use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` before running Maven. (CLAUDE.md)
- No Node/build toolchain for `web-ui/` — plain files only, served as-is. This is an explicit user requirement ("as simple as possible, I'm not a frontend developer"), not a default — do not introduce a bundler, framework, or package.json.
- Gateway stays routing-only; the only Gateway change in this phase is a CORS policy bean. Do not add static-resource serving, templates, or a UI route to `gateway-service`. (CLAUDE.md's description of Gateway; also this document's own architecture decision below.)
- No Keycloak custom SPI/event-listener plugin. Account provisioning at signup is UI-driven (onboarding screen calling `POST /accounts`), not automatic on the `REGISTER` event — see Design Decisions.
- Never commit directly to `master`; one branch per task, `feature/phase-9-task-<N>-<short-description>`, cut from a freshly fetched `origin/master`; one PR per task, stop after each; subagent review is manual, on request. (CLAUDE.md)
- Default to `haiku` for implementer/routine-review subagents; use a more capable model for the final whole-branch review; always name the model explicitly; give smaller models explicit checklists. (CLAUDE.md)
- Sync any review fix back into this document verbatim from the merged source, not by hand. (CLAUDE.md)

### Running Task 2 against an existing local stack

Task 2 adds `unique = true` to `Account.ownerId`, applied via Hibernate's `ddl-auto: update`. On a **fresh** database (Testcontainers, CI, or a brand-new local stack) this works cleanly. But `docker-compose.yml`'s Postgres uses a **named, persistent volume** (`postgres-data`), so a local dev stack that predates this phase can already have duplicate `owner_id` rows (e.g. `ada`/`bob` demo accounts created before one-account-per-owner existed). Against that data, Hibernate's `ALTER TABLE ADD CONSTRAINT` silently fails (it logs a warning and boots anyway) — so the DB-level half of the one-account-per-owner guarantee (the `saveAndFlush` + `DataIntegrityViolationException` catch) won't actually be backed by a real constraint, even though the `existsByOwnerId` pre-check still catches the common sequential case. There's no Flyway/Liquibase in this project to carry the schema change, so before the first `up` on an existing local stack, run `docker compose down -v` (or manually deduplicate `account.owner_id`) to get a clean volume.

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

## Test-Infrastructure Consequence of the One-Account-Per-Owner Rule (found during planning)

`account-service/src/test/java/.../AccountControllerIT.java` shares one Testcontainers Postgres instance across all its `@Test` methods (documented in its own class comment), and ~14 of them call a `createAccount(BigDecimal)` helper that posts as the class-wide ambient customer identity installed once in `@BeforeEach authenticateAsCustomer`. Enforcing one account per owner means the *second* such call anywhere in the class now returns `409 ACCOUNT_ALREADY_EXISTS` instead of a fresh account — breaking the majority of this file's existing tests, not just adding a new one. Task 2 below fixes this at the root: `authenticateAsCustomer` mints a **fresh** random customer identity before every test method (instead of installing the same fixed token once and skipping thereafter), so every test method gets its own private owner and every existing call site keeps working unmodified. `listsAllAccounts` — the one method that deliberately creates two accounts *within the same test* — is the one call site that does need an explicit second identity, since both its accounts now can't share the per-method default. See Task 2's Step 3 for the exact diff.

## Task Breakdown

| # | Task | Deliverable |
|---|---|---|
| 1 | Account: `GET /accounts/mine` | Owner-filtered account list |
| 2 | Account: one-account-per-owner | `409 ACCOUNT_ALREADY_EXISTS` on a second `POST /accounts`; test-infra fix above |
| 3 | Account: `GET /accounts/{id}/summary` | Any-caller owner-name lookup |
| 4 | Transfer: `GET /transfers/mine` | Initiator-filtered transfer list |
| 5 | Gateway: routes + CORS | The three new endpoints reachable through the Gateway; UI origin allowed |
| 6 | Keycloak: PKCE, self-registration, default role, theme | `showcase-realm.json` + `docker/keycloak/themes/bank-ui/` |
| 7 | Bank UI: skeleton + auth | `web-ui/` served by nginx; PKCE login/logout round-trip |
| 8 | Bank UI: onboarding + dashboard | One-time account setup; balance display |
| 9 | Bank UI: transfer, history, quick-transfers | The rest of the customer flow |
| 10 | Docs sync + whole-branch review | README/service-links/design-doc consistency; final review pass |

---

### Task 1: Account Service — `GET /accounts/mine`

**Files:**
- Modify: `account-service/src/main/java/com/showcase/account/domain/AccountRepository.java`
- Modify: `account-service/src/main/java/com/showcase/account/service/AccountService.java`
- Modify: `account-service/src/main/java/com/showcase/account/api/AccountController.java`
- Test: `account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java`

**Interfaces:**
- Produces: `AccountService.getMyAccounts(UUID ownerId): List<Account>`, `GET /accounts/mine` (any authenticated caller, own accounts only).

- [ ] **Step 1: Write the failing test**

Add to `AccountControllerIT`:

```java
@Test
void listsOnlyMyAccounts() {
    UUID myAccountId = createAccount(new BigDecimal("100.00"));

    ResponseEntity<AccountResponse[]> response = restTemplate.getForEntity("/accounts/mine", AccountResponse[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).extracting(AccountResponse::id).containsExactly(myAccountId);
}

// Regression guard for the literal-vs-variable route ambiguity: "/accounts/mine" and
// "/accounts/{id}" are both single-segment GET paths on this controller. Spring MVC's
// PathPattern comparator always prefers the more specific (literal) match, so this must
// return the caller's own accounts, never attempt UUID.fromString("mine") for getAccount.
@Test
void accountsMineIsNotSwallowedByTheIdRoute() {
    ResponseEntity<AccountResponse[]> response = restTemplate.getForEntity("/accounts/mine", AccountResponse[].class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
}
```

- [ ] **Step 2: Run to verify both fail**

Run: `./mvnw -pl account-service -Dtest=AccountControllerIT test`
Expected: both new tests FAIL — `listsOnlyMyAccounts` with 404 (no route), `accountsMineIsNotSwallowedByTheIdRoute` the same.

- [ ] **Step 3: Add the repository query**

In `AccountRepository`:

```java
public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByOwnerId(UUID ownerId);
}
```

(add `import java.util.List;`)

- [ ] **Step 4: Add the service method**

In `AccountService`, after `getAllAccounts`:

```java
// "Mine" for the caller -- filtered by the same ownerId every other owner-gated method
// reads from the JWT. Unlike getAllAccounts, this is safe for any authenticated caller:
// each caller only ever sees their own list.
@Transactional(readOnly = true)
public List<Account> getMyAccounts(UUID ownerId) {
    return accountRepository.findAllByOwnerId(ownerId);
}
```

- [ ] **Step 5: Add the controller method**

In `AccountController`, after `getAllAccounts`:

```java
@GetMapping("/mine")
public List<AccountResponse> getMyAccounts(@AuthenticationPrincipal Jwt jwt) {
    return accountService.getMyAccounts(UUID.fromString(jwt.getSubject())).stream()
            .map(AccountResponse::from)
            .toList();
}
```

- [ ] **Step 6: Run to verify both pass**

Run: `./mvnw -pl account-service -Dtest=AccountControllerIT test`
Expected: PASS.

- [ ] **Step 7: Run the whole module's tests, commit, push, open the PR, stop**

```bash
./mvnw -pl account-service test
git checkout -b feature/phase-9-task-1-accounts-mine
git add account-service/src/main/java/com/showcase/account/domain/AccountRepository.java \
        account-service/src/main/java/com/showcase/account/service/AccountService.java \
        account-service/src/main/java/com/showcase/account/api/AccountController.java \
        account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java
git commit -m "feat(account): add GET /accounts/mine (Phase 9, Task 1)"
git push -u origin feature/phase-9-task-1-accounts-mine
gh pr create --title "feat(account): GET /accounts/mine (Phase 9, Task 1)" --body "..."
```

Stop here — wait for review/merge before starting Task 2.

---

### Task 2: Account Service — one-account-per-owner

**Files:**
- Create: `account-service/src/main/java/com/showcase/account/domain/AccountAlreadyExistsException.java`
- Modify: `account-service/src/main/java/com/showcase/account/domain/Account.java`
- Modify: `account-service/src/main/java/com/showcase/account/domain/AccountRepository.java`
- Modify: `account-service/src/main/java/com/showcase/account/service/AccountService.java`
- Modify: `account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java`
- Modify: `account-service/src/test/java/com/showcase/account/support/TestSecurityConfig.java`
- Modify: `account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java`

**Interfaces:**
- Consumes: `AccountRepository` (Task 1).
- Produces: `409 ACCOUNT_ALREADY_EXISTS` on a second `POST /accounts` for the same owner; `TestSecurityConfig.freshCustomerToken(): String` for later tasks' tests that need a distinct customer identity.

- [ ] **Step 1: Fix the test infrastructure first (see the note above this table)**

In `TestSecurityConfig.java`, add a dynamic token registry and a way to mint fresh identities, and change `authenticateAsCustomer` to install a fresh one per test instead of reusing the fixed one:

```java
import java.util.concurrent.ConcurrentHashMap;
// ...existing imports stay...

@TestConfiguration
public class TestSecurityConfig {

    public static final String CUSTOMER_TOKEN = "test-customer-token";
    public static final String CUSTOMER2_TOKEN = "test-customer2-token";
    public static final String ADMIN_TOKEN = "test-admin-token";
    public static final UUID CUSTOMER_SUBJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID CUSTOMER2_SUBJECT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // Phase 9: one-account-per-owner means every test needs its OWN owner, not the one fixed
    // CUSTOMER_SUBJECT above -- AccountControllerIT shares one Testcontainers Postgres instance
    // across all its @Test methods, so a fixed shared identity would make the second test that
    // creates an account 409 against the first. freshCustomerToken() mints a new (token,
    // subject) pair the stub decoder below also recognises.
    private static final Map<String, UUID> DYNAMIC_CUSTOMER_SUBJECTS = new ConcurrentHashMap<>();

    public static String freshCustomerToken() {
        String token = "test-customer-token-" + UUID.randomUUID();
        DYNAMIC_CUSTOMER_SUBJECTS.put(token, UUID.randomUUID());
        return token;
    }

    @Bean
    @Primary
    public JwtDecoder stubJwtDecoder() {
        return token -> {
            if (CUSTOMER_TOKEN.equals(token)) {
                return customerJwt(token, CUSTOMER_SUBJECT);
            }
            if (CUSTOMER2_TOKEN.equals(token)) {
                return customerJwt(token, CUSTOMER2_SUBJECT);
            }
            if (ADMIN_TOKEN.equals(token)) {
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of("account-admin")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }
            UUID dynamicSubject = DYNAMIC_CUSTOMER_SUBJECTS.get(token);
            if (dynamicSubject != null) {
                return customerJwt(token, dynamicSubject);
            }
            throw new JwtException("Unrecognised test token: " + token);
        };
    }

    private static Jwt customerJwt(String token, UUID subject) {
        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject.toString())
                .claim("realm_access", Map.of("roles",
                        List.of("transfer-executor", "account-reader", "account-editor", "fraud-checker")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    /**
     * Installs a FRESH customer identity before every test method, replacing any previous
     * interceptor rather than skipping when one is already present (Phase 9 changed this from
     * idempotent-install-once: see the class-level comment above DYNAMIC_CUSTOMER_SUBJECTS).
     * The underlying RestTemplate bean is still shared/cached across this test class's methods,
     * so removal-then-add is what makes each test's default identity distinct.
     */
    public static void authenticateAsCustomer(TestRestTemplate restTemplate) {
        var interceptors = restTemplate.getRestTemplate().getInterceptors();
        interceptors.removeIf(BearerAuthInterceptor.class::isInstance);
        interceptors.add(new BearerAuthInterceptor(freshCustomerToken()));
    }

    private record BearerAuthInterceptor(String token) implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        }
    }
}
```

(add `import java.util.concurrent.ConcurrentHashMap;` and `import java.util.Map;` stays as-is — `Map` was already imported.)

- [ ] **Step 2: Fix `listsAllAccounts`, the one test that needs two owners in one method**

In `AccountControllerIT`, replace:

```java
UUID firstId = createAccount(new BigDecimal("100.00"));
UUID secondId = createAccount(new BigDecimal("50.00"));
```

with:

```java
UUID firstId = createAccount(new BigDecimal("100.00"), TestSecurityConfig.freshCustomerToken());
UUID secondId = createAccount(new BigDecimal("50.00"), TestSecurityConfig.freshCustomerToken());
```

Add the overload next to the existing `createAccount` helper:

```java
private UUID createAccount(BigDecimal initialBalance) {
    return createAccount(initialBalance, null);
}

// ownerToken == null uses the ambient per-test identity from authenticateAsCustomer.
// An explicit token lets one test method create accounts for two DIFFERENT owners --
// needed now that one account per owner is enforced. See listsAllAccounts.
private UUID createAccount(BigDecimal initialBalance, String ownerToken) {
    HttpHeaders headers = new HttpHeaders();
    if (ownerToken != null) {
        headers.setBearerAuth(ownerToken);
    }
    ResponseEntity<AccountResponse> response = restTemplate.exchange(
            "/accounts", HttpMethod.POST,
            new HttpEntity<>(new CreateAccountRequest("Ada Lovelace", initialBalance), headers),
            AccountResponse.class);
    return response.getBody().id();
}
```

- [ ] **Step 3: Run the full existing suite to confirm the infra fix alone doesn't break anything**

Run: `./mvnw -pl account-service -Dtest=AccountControllerIT test`
Expected: PASS — every pre-existing test, unchanged in its own body, still passes because each now gets its own private owner.

- [ ] **Step 4: Write the failing test for the new rule**

```java
@Test
void rejectsASecondAccountForTheSameOwner() {
    createAccount(new BigDecimal("100.00"));

    ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
            "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("50.00")), ProblemDetail.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody().getProperties()).containsEntry("code", "ACCOUNT_ALREADY_EXISTS");
}
```

- [ ] **Step 5: Run to verify it fails**

Run: `./mvnw -pl account-service -Dtest=AccountControllerIT test`
Expected: FAIL — the second `POST /accounts` currently succeeds with 201.

- [ ] **Step 6: Add the exception, repository check, entity constraint, service enforcement, and handler**

`AccountAlreadyExistsException.java`:

```java
package com.showcase.account.domain;

import java.util.UUID;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(UUID ownerId) {
        super("Account already exists for owner: " + ownerId);
    }
}
```

In `AccountRepository`, add:

```java
boolean existsByOwnerId(UUID ownerId);
```

In `Account.java`, change the `ownerId` column:

```java
@Column(nullable = false, unique = true)
private UUID ownerId;
```

In `AccountService.java`, replace `createAccount`:

```java
@Transactional
public Account createAccount(UUID ownerId, String ownerName, BigDecimal initialBalance) {
    if (accountRepository.existsByOwnerId(ownerId)) {
        throw new AccountAlreadyExistsException(ownerId);
    }
    try {
        // saveAndFlush, not save: a plain save() only queues the INSERT for the transaction's
        // commit-time flush, which happens AFTER this method returns -- too late to catch here.
        // Flushing now surfaces a genuinely concurrent duplicate (the unique constraint above)
        // as a DataIntegrityViolationException inside this method, same idiom as
        // AccountOperation's idempotency-key race in apply() below.
        return accountRepository.saveAndFlush(new Account(ownerId, ownerName, initialBalance));
    } catch (DataIntegrityViolationException ex) {
        throw new AccountAlreadyExistsException(ownerId);
    }
}
```

(add `import com.showcase.account.domain.AccountAlreadyExistsException;` if not in the same package already — it is, same `domain` package, no import needed; add `import org.springframework.dao.DataIntegrityViolationException;`)

In `ApiExceptionHandler.java`, add:

```java
@ExceptionHandler(AccountAlreadyExistsException.class)
public ProblemDetail handleAlreadyExists(AccountAlreadyExistsException ex) {
    return Problems.of(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", "Account already exists", ex.getMessage());
}
```

(add `import com.showcase.account.domain.AccountAlreadyExistsException;`)

- [ ] **Step 7: Run to verify it passes**

Run: `./mvnw -pl account-service -Dtest=AccountControllerIT test`
Expected: PASS.

- [ ] **Step 8: Run the whole module's tests**

Run: `./mvnw -pl account-service test`
Expected: PASS. If any pre-existing test still fails with `ACCOUNT_ALREADY_EXISTS`, it's a call site Step 2 missed — search for every `createAccount(` call in the file and confirm each is either a lone call in its method or uses the two-owner overload.

- [ ] **Step 9: Commit, push, open the PR, stop**

```bash
git checkout -b feature/phase-9-task-2-one-account-per-owner
git add account-service/src/main/java/com/showcase/account/domain/AccountAlreadyExistsException.java \
        account-service/src/main/java/com/showcase/account/domain/Account.java \
        account-service/src/main/java/com/showcase/account/domain/AccountRepository.java \
        account-service/src/main/java/com/showcase/account/service/AccountService.java \
        account-service/src/main/java/com/showcase/account/api/ApiExceptionHandler.java \
        account-service/src/test/java/com/showcase/account/support/TestSecurityConfig.java \
        account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java
git commit -m "feat(account): enforce one account per owner (Phase 9, Task 2)"
git push -u origin feature/phase-9-task-2-one-account-per-owner
gh pr create --title "feat(account): one account per owner (Phase 9, Task 2)" --body "..."
```

Stop here.

---

### Task 3: Account Service — `GET /accounts/{id}/summary`

**Files:**
- Create: `account-service/src/main/java/com/showcase/account/api/AccountSummaryResponse.java`
- Modify: `account-service/src/main/java/com/showcase/account/service/AccountService.java`
- Modify: `account-service/src/main/java/com/showcase/account/api/AccountController.java`
- Modify: `account-service/src/main/java/com/showcase/account/config/SecurityConfig.java`
- Test: `account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java`

**Interfaces:**
- Produces: `GET /accounts/{id}/summary` → `{ "ownerName": "..." }`, any authenticated caller, no ownership check.

- [ ] **Step 1: Write the failing test**

```java
// Deliberately the one place a caller can read something about an account they don't own --
// the display name, never the balance or ownerId. See docs/phase-9-bank-ui.md's Design
// Decisions.
@Test
void anyAuthenticatedCallerCanReadAnAccountsSummary() {
    UUID id = createAccount(new BigDecimal("100.00"));

    HttpHeaders otherCustomer = new HttpHeaders();
    otherCustomer.setBearerAuth(TestSecurityConfig.freshCustomerToken());
    ResponseEntity<AccountSummaryResponse> response = restTemplate.exchange(
            "/accounts/" + id + "/summary", HttpMethod.GET, new HttpEntity<>(otherCustomer), AccountSummaryResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().ownerName()).isEqualTo("Ada Lovelace");
}

@Test
void summaryReturns404ForAnUnknownAccount() {
    ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
            "/accounts/" + UUID.randomUUID() + "/summary", ProblemDetail.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getProperties()).containsEntry("code", "ACCOUNT_NOT_FOUND");
}
```

- [ ] **Step 2: Run to verify both fail (404 route-not-found)**

Run: `./mvnw -pl account-service -Dtest=AccountControllerIT test`

- [ ] **Step 3: Add the DTO, service method, controller method, and security matcher**

`AccountSummaryResponse.java`:

```java
package com.showcase.account.api;

import com.showcase.account.domain.Account;

public record AccountSummaryResponse(String ownerName) {

    public static AccountSummaryResponse from(Account account) {
        return new AccountSummaryResponse(account.getOwnerName());
    }
}
```

In `AccountService.java`, add:

```java
// No ownership check -- see AccountController.getAccountSummary's javadoc.
@Transactional(readOnly = true)
public Account getAccountSummary(UUID id) {
    return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
}
```

In `AccountController.java`, add (near `getAccount`):

```java
// Any authenticated caller, deliberately NOT owner-gated -- the one exception to this
// controller's ownership rule. Returns only ownerName, never balance or ownerId, so a
// recipient can be shown as a name instead of a bare UUID without leaking their balance.
// See docs/phase-9-bank-ui.md's Design Decisions.
@GetMapping("/{id}/summary")
public AccountSummaryResponse getAccountSummary(@PathVariable UUID id) {
    return AccountSummaryResponse.from(accountService.getAccountSummary(id));
}
```

In `SecurityConfig.java`, add (before the `/accounts/*` matcher, for readability — the two patterns don't overlap):

```java
// Any authenticated caller, not just account-reader -- matches getAccountSummary's
// deliberately open access. HEAD included for the same reason as every other GET matcher
// here (Spring MVC serves HEAD from the same handler).
.requestMatchers(HttpMethod.GET, "/accounts/*/summary").authenticated()
.requestMatchers(HttpMethod.HEAD, "/accounts/*/summary").authenticated()
```

- [ ] **Step 4: Run to verify both pass**

Run: `./mvnw -pl account-service -Dtest=AccountControllerIT test`

- [ ] **Step 5: Run the whole module's tests, commit, push, open the PR, stop**

```bash
./mvnw -pl account-service test
git checkout -b feature/phase-9-task-3-account-summary
git add account-service/src/main/java/com/showcase/account/api/AccountSummaryResponse.java \
        account-service/src/main/java/com/showcase/account/service/AccountService.java \
        account-service/src/main/java/com/showcase/account/api/AccountController.java \
        account-service/src/main/java/com/showcase/account/config/SecurityConfig.java \
        account-service/src/test/java/com/showcase/account/api/AccountControllerIT.java
git commit -m "feat(account): add GET /accounts/{id}/summary (Phase 9, Task 3)"
git push -u origin feature/phase-9-task-3-account-summary
gh pr create --title "feat(account): GET /accounts/{id}/summary (Phase 9, Task 3)" --body "..."
```

Stop here.

---

### Task 4: Transfer Service — `GET /transfers/mine`

**Files:**
- Modify: `transfer-service/src/main/java/com/showcase/transfer/domain/TransferRepository.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java`
- Modify: `transfer-service/src/main/java/com/showcase/transfer/api/TransferController.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/api/TransferControllerTest.java`
- Test: `transfer-service/src/test/java/com/showcase/transfer/api/TransferSecurityIT.java`

**Interfaces:**
- Produces: `TransferService.listMyTransfers(UUID initiatorId, TransferStatus status): List<Transfer>`, `GET /transfers/mine` (`transfer-executor`, same as every other non-admin transfer read).

- [ ] **Step 1: Write the failing tests**

In `TransferControllerTest.java`, add:

```java
@Test
void listsMyTransfersUsingTheCallersSubject() throws Exception {
    Transfer transfer = pendingTransfer();
    transfer.markCompleted();
    when(transferService.listMyTransfers(SUBJECT, null)).thenReturn(List.of(transfer));

    mockMvc.perform(get("/transfers/mine").with(transferExecutor()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("COMPLETED"));
}
```

In `TransferSecurityIT.java`, add:

```java
@Test
void listMyTransfersReturns401WithNoToken() throws Exception {
    mockMvc.perform(get("/transfers/mine"))
            .andExpect(status().isUnauthorized());
}

@Test
void listMyTransfersReturns200WithTransferExecutorAuthority() throws Exception {
    mockMvc.perform(get("/transfers/mine").with(jwt().authorities(() -> "transfer-executor")))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: Run to verify all three fail**

Run: `./mvnw -pl transfer-service -Dtest=TransferControllerTest,TransferSecurityIT test`
Expected: FAIL — no `/transfers/mine` route exists yet.

- [ ] **Step 3: Add the repository queries, service method, and controller method**

In `TransferRepository.java`, add:

```java
List<Transfer> findByInitiatorId(UUID initiatorId);

List<Transfer> findByInitiatorIdAndStatus(UUID initiatorId, TransferStatus status);
```

In `TransferService.java`, add (near `listTransfers`):

```java
public List<Transfer> listMyTransfers(UUID initiatorId, TransferStatus status) {
    return status == null
            ? transferRepository.findByInitiatorId(initiatorId)
            : transferRepository.findByInitiatorIdAndStatus(initiatorId, status);
}
```

In `TransferController.java`, add (before the admin-only `listTransfers`):

```java
@GetMapping("/mine")
public List<TransferResponse> listMyTransfers(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(required = false) TransferStatus status) {
    return transferService.listMyTransfers(UUID.fromString(jwt.getSubject()), status).stream()
            .map(TransferResponse::from)
            .toList();
}
```

- [ ] **Step 4: Run to verify all three pass**

Run: `./mvnw -pl transfer-service -Dtest=TransferControllerTest,TransferSecurityIT test`

- [ ] **Step 5: Run the whole module's tests, commit, push, open the PR, stop**

```bash
./mvnw -pl transfer-service test
git checkout -b feature/phase-9-task-4-transfers-mine
git add transfer-service/src/main/java/com/showcase/transfer/domain/TransferRepository.java \
        transfer-service/src/main/java/com/showcase/transfer/service/TransferService.java \
        transfer-service/src/main/java/com/showcase/transfer/api/TransferController.java \
        transfer-service/src/test/java/com/showcase/transfer/api/TransferControllerTest.java \
        transfer-service/src/test/java/com/showcase/transfer/api/TransferSecurityIT.java
git commit -m "feat(transfer): add GET /transfers/mine (Phase 9, Task 4)"
git push -u origin feature/phase-9-task-4-transfers-mine
gh pr create --title "feat(transfer): GET /transfers/mine (Phase 9, Task 4)" --body "..."
```

Stop here.

---

### Task 5: Gateway — routes + CORS

**Files:**
- Create: `gateway-service/src/main/java/com/showcase/gateway/config/BankUiProperties.java`
- Modify: `gateway-service/src/main/java/com/showcase/gateway/config/GatewayRoutesConfig.java`
- Modify: `gateway-service/src/main/java/com/showcase/gateway/config/SecurityConfig.java`
- Modify: `gateway-service/src/main/resources/application.yml`
- Test: `gateway-service/src/test/java/com/showcase/gateway/config/GatewayRoutesConfigTest.java`
- Test: `gateway-service/src/test/java/com/showcase/gateway/GatewaySecurityIT.java`

**Interfaces:**
- Consumes: Tasks 1–4's three new endpoints.
- Produces: those endpoints reachable through the Gateway at :8080; CORS allowing the Bank UI's origin.

- [ ] **Step 1: Write the failing route-matching tests**

In `GatewayRoutesConfigTest.java`, add:

```java
@Test
void accountMineMatches() {
    assertThat(matches(accountRoutes, "GET", "/accounts/mine")).isTrue();
}

@Test
void accountSummaryMatches() {
    assertThat(matches(accountRoutes, "GET", "/accounts/123/summary")).isTrue();
}
```

- [ ] **Step 2: Run to verify (see note)**

Run: `./mvnw -pl gateway-service -Dtest=GatewayRoutesConfigTest test`
`accountMineMatches` may already PASS today — `/accounts/{id}` is a path-variable pattern and "mine" is a legal single-segment value for it. Confirm the actual result rather than assuming; either way, Step 3 adds an explicit predicate for `/accounts/mine` so the allow-list documents it deliberately rather than by accidental overlap. `accountSummaryMatches` must FAIL (no two-segment predicate exists yet).

- [ ] **Step 3: Add the explicit route predicates and the CORS bean**

`BankUiProperties.java`:

```java
package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank-ui")
public record BankUiProperties(String origin) {
}
```

In `GatewayRoutesConfig.java`, change `accountRoutes`:

```java
@Bean
public RouterFunction<ServerResponse> accountRoutes(AccountServiceProperties properties) {
    RequestPredicate accountPaths = POST("/accounts")
            .or(GET("/accounts"))
            .or(GET("/accounts/mine"))
            .or(GET("/accounts/{id}"))
            .or(GET("/accounts/{id}/summary"));
    return route(accountPaths, http(properties.baseUrl()));
}
```

In `SecurityConfig.java`, add the CORS bean and wire it in:

```java
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

// ...

@Bean
public CorsConfigurationSource corsConfigurationSource(BankUiProperties bankUiProperties) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(bankUiProperties.origin()));
    configuration.setAllowedMethods(List.of("GET", "POST"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}

@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
        CorsConfigurationSource corsConfigurationSource) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

    http.cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers("/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/prometheus").permitAll()
                    .requestMatchers("/actuator/info").permitAll()
                    .requestMatchers("/transfers/**").hasAnyAuthority("transfer-executor", "transfer-admin")
                    .requestMatchers(HttpMethod.GET, "/accounts/*/summary").authenticated()
                    .requestMatchers(HttpMethod.GET, "/accounts", "/accounts/*")
                    .hasAnyAuthority("account-reader", "account-admin")
                    .requestMatchers(HttpMethod.POST, "/accounts").hasAuthority("account-editor")
                    .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(converter)));
    return http.build();
}
```

(the `/accounts/*/summary` matcher must come before `/accounts/*` — Spring Security evaluates matchers in order and the first match wins, and `/accounts/*` would otherwise never fire for the 2-segment path anyway, but keeping it above documents the intent per the existing file's own convention.)

In `application.yml`, add:

```yaml
bank-ui:
  origin: ${BANK_UI_ORIGIN:http://localhost:8090}
```

- [ ] **Step 4: Write the failing CORS/security tests**

In `GatewayRoutesConfigTest.java`, add (if `accountMineMatches` from Step 1 already passed, keep it as a regression guard regardless):

```java
@Test
void accountMineMatches() {
    assertThat(matches(accountRoutes, "GET", "/accounts/mine")).isTrue();
}
```

In `GatewaySecurityIT.java`, add:

```java
@Test
void accountSummaryRouteAcceptsAnyAuthenticatedCaller() {
    ResponseEntity<String> response = requestWithAuthorities("/accounts/123/summary", HttpMethod.GET, "transfer-executor");
    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
}

@Test
void transfersMineRouteAcceptsTransferExecutorAuthority() {
    ResponseEntity<String> response = requestWithAuthorities("/transfers/mine", HttpMethod.GET, "transfer-executor");
    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
}
```

- [ ] **Step 5: Run to verify everything passes**

Run: `./mvnw -pl gateway-service test`

- [ ] **Step 6: Live verification (Docker required)**

```bash
$env:GIT_SHA = git rev-parse --short HEAD
docker compose up -d --build --no-deps gateway-service
```

Confirm a browser-style preflight is answered correctly:

```bash
curl -i -X OPTIONS http://localhost:8080/accounts/mine \
  -H "Origin: http://localhost:8090" \
  -H "Access-Control-Request-Method: GET"
```

Expected: `200`/`204` with `Access-Control-Allow-Origin: http://localhost:8090` in the response headers.

- [ ] **Step 7: Commit, push, open the PR, stop**

```bash
git checkout -b feature/phase-9-task-5-gateway-routes-cors
git add gateway-service/src/main/java/com/showcase/gateway/config/BankUiProperties.java \
        gateway-service/src/main/java/com/showcase/gateway/config/GatewayRoutesConfig.java \
        gateway-service/src/main/java/com/showcase/gateway/config/SecurityConfig.java \
        gateway-service/src/main/resources/application.yml \
        gateway-service/src/test/java/com/showcase/gateway/config/GatewayRoutesConfigTest.java \
        gateway-service/src/test/java/com/showcase/gateway/GatewaySecurityIT.java
git commit -m "feat(gateway): route the three new Bank UI endpoints, add CORS (Phase 9, Task 5)"
git push -u origin feature/phase-9-task-5-gateway-routes-cors
gh pr create --title "feat(gateway): Bank UI routes + CORS (Phase 9, Task 5)" --body "..."
```

Stop here.

---

### Task 6: Keycloak — PKCE, self-registration, default role, theme

**Files:**
- Modify: `docker/keycloak/showcase-realm.json`
- Create: `docker/keycloak/themes/bank-ui/login/theme.properties`
- Create: `docker/keycloak/themes/bank-ui/login/resources/css/bank-ui.css`
- Modify: `docker-compose.yml`

This task has two genuinely uncertain points that planning could not verify without a running Keycloak (no Docker daemon was available while writing this plan). Both are called out explicitly below with a live-verification gate — **do not skip either gate**, and if the verification fails, use the documented fallback rather than shipping an unverified guess.

- [ ] **Step 1: Realm changes**

In `showcase-realm.json`, add `"registrationAllowed": true` and `"loginTheme": "bank-ui"` at the realm level (alongside `"enabled": true`), add `attributes` to the `showcase-ui` client:

```json
{
  "clientId": "showcase-ui",
  "name": "Showcase UI",
  "description": "Represents any human caller: the Bank UI (Authorization Code + PKCE) and curl/Postman (password grant)",
  "enabled": true,
  "publicClient": true,
  "protocol": "openid-connect",
  "standardFlowEnabled": true,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": true,
  "serviceAccountsEnabled": false,
  "redirectUris": ["http://localhost:8090/"],
  "webOrigins": ["http://localhost:8090"],
  "attributes": {
    "pkce.code.challenge.method": "S256",
    "post.logout.redirect.uris": "http://localhost:8090/"
  }
}
```

**Update, post-Phase 9:** this block originally shipped `showcase-ui` with Phase 7's placeholder `"redirectUris": ["http://localhost:*", "http://localhost:3000/*"]` and `"webOrigins": ["*"]`, leaving a live Authorization Code flow on a public client that accepted any localhost redirect. It is now synced to the realm file: the one URI the UI uses (`window.location.origin + '/'` in `auth.js`), for both login and logout.

Add a default-roles composite so a self-registered user (who otherwise has zero realm roles) gets `customer`'s capabilities automatically. Add to `roles.realm`:

```json
{
  "name": "default-roles-showcase",
  "description": "Assigned automatically to every new user, including self-registered ones",
  "composite": true,
  "composites": { "realm": ["customer"] }
}
```

and add a top-level `defaultRole` field (a sibling of `"realm"`, `"roles"`, `"clients"`, `"users"`) referencing it:

```json
"defaultRole": {
  "name": "default-roles-showcase",
  "composite": true,
  "composites": { "realm": ["customer"] }
}
```

- [ ] **Step 2: Live-verify the default role actually applies (Docker required) — GATE, do not skip**

```bash
docker compose up -d --build keycloak
```

Wait for it healthy (`docker compose ps keycloak`), then register a test user through Keycloak's own registration page (`http://localhost:8180/realms/showcase/account/` or the registration link off the login page reached via the `showcase-ui` client's authorize URL), or via the admin REST API if faster. Then fetch a token for that user and decode it:

```bash
curl -s -X POST http://localhost:8180/realms/showcase/protocol/openid-connect/token \
  -d "client_id=showcase-ui" -d "grant_type=password" \
  -d "username=<the test user>" -d "password=<its password>" | jq -r .access_token
```

Paste the token at jwt.io (or `| cut -d. -f2 | base64 -d`) and confirm `realm_access.roles` contains `transfer-executor`, `account-reader`, `account-editor`, `fraud-checker`.

**If it does not** (empty roles, or the `defaultRole` field is rejected/ignored on import): open the Keycloak admin console (`http://localhost:8180`, `admin`/`admin`) → Realm settings → User registration → Default roles, and add `customer` there manually to confirm the *concept* works, then adjust the JSON's shape to match what a fresh Keycloak export of that state actually contains (Realm settings → Action → Partial export, or `docker exec` + `kcadm.sh get realms/showcase`) rather than re-guessing.

- [ ] **Step 3: Theme scaffold**

`docker/keycloak/themes/bank-ui/login/theme.properties`:

```properties
parent=keycloak
import=common/keycloak
styles=css/login.css css/bank-ui.css
```

`docker/keycloak/themes/bank-ui/login/resources/css/bank-ui.css` — a starting point targeting the classic "keycloak" base theme's long-stable element ids/classes (`#kc-header`, `#kc-header-wrapper`, `.login-pf-page`, `.card-pf`, `#kc-form-buttons input[type="submit"]`); adjust selectors in Step 4 against what actually renders:

```css
:root {
    --bank-ui-primary: #1b4332;
    --bank-ui-accent: #2d6a4f;
}

body.login-pf-page {
    background: var(--bank-ui-primary);
}

#kc-header-wrapper {
    color: #ffffff;
    text-transform: none;
    font-weight: 600;
}

.card-pf {
    border-top: 4px solid var(--bank-ui-accent);
}

#kc-form-buttons input[type="submit"] {
    background-color: var(--bank-ui-accent);
    border-color: var(--bank-ui-accent);
}
```

In `docker-compose.yml`'s `keycloak` service, add a volume mount:

```yaml
volumes:
  - ./docker/keycloak/showcase-realm.json:/opt/keycloak/data/import/showcase-realm.json:ro
  - ./docker/keycloak/themes/bank-ui:/opt/keycloak/themes/bank-ui:ro
```

- [ ] **Step 4: Live-verify the theme renders (Docker required) — GATE, do not skip**

```bash
docker compose up -d --build keycloak
```

Open `http://localhost:8180/realms/showcase/protocol/openid-connect/auth?client_id=showcase-ui&response_type=code&redirect_uri=http://localhost:8090&scope=openid` in a browser. Confirm the page visibly picks up the CSS (dark header, accent-colored submit button). If it doesn't, open browser dev tools, find the login page's actual root class/ids in this Keycloak image, and adjust the selectors in `bank-ui.css` (and `parent` in `theme.properties` if `keycloak` turns out not to be served — try `keycloak.v2` next) until it does. Keycloak's ephemeral H2 (no volume mounted for its own data dir) means every `docker compose up`/container recreate re-imports the realm from JSON fresh — no manual reset needed between iterations.

- [ ] **Step 5: Commit, push, open the PR, stop**

```bash
git checkout -b feature/phase-9-task-6-keycloak-pkce-registration-theme
git add docker/keycloak/showcase-realm.json docker/keycloak/themes/bank-ui docker-compose.yml
git commit -m "feat(keycloak): PKCE, self-registration default role, Bank UI theme (Phase 9, Task 6)"
git push -u origin feature/phase-9-task-6-keycloak-pkce-registration-theme
gh pr create --title "feat(keycloak): PKCE + self-registration + theme (Phase 9, Task 6)" --body "..."
```

Stop here.

---

### Task 7: Bank UI — skeleton + PKCE auth

**Files:**
- Create: `web-ui/index.html`
- Create: `web-ui/css/styles.css`
- Create: `web-ui/js/auth.js`
- Create: `web-ui/js/app.js`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: Keycloak's `showcase-ui` client (Task 6).
- Produces: `Auth.login()`, `Auth.logout()`, `Auth.handleRedirectCallback(): Promise<boolean>`, `Auth.getToken(): string|null`, `Auth.isAuthenticated(): boolean` — every later UI task builds on these.

- [ ] **Step 1: `web-ui/js/auth.js`**

```javascript
const KEYCLOAK_BASE = 'http://localhost:8180';
const REALM = 'showcase';
const CLIENT_ID = 'showcase-ui';
const REDIRECT_URI = window.location.origin + '/';

const AUTHORIZE_URL = `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/auth`;
const TOKEN_URL = `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/token`;
const END_SESSION_URL = `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/logout`;

const TOKEN_KEY = 'bank_ui_access_token';
const ID_TOKEN_KEY = 'bank_ui_id_token';
const VERIFIER_KEY = 'bank_ui_pkce_verifier';

function base64UrlEncode(bytes) {
    let binary = '';
    bytes.forEach((b) => { binary += String.fromCharCode(b); });
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomString() {
    const bytes = new Uint8Array(32);
    crypto.getRandomValues(bytes);
    return base64UrlEncode(bytes);
}

async function sha256(input) {
    const data = new TextEncoder().encode(input);
    const digest = await crypto.subtle.digest('SHA-256', data);
    return base64UrlEncode(new Uint8Array(digest));
}

const Auth = {
    isAuthenticated() {
        return sessionStorage.getItem(TOKEN_KEY) !== null;
    },

    getToken() {
        return sessionStorage.getItem(TOKEN_KEY);
    },

    async login() {
        const verifier = randomString();
        sessionStorage.setItem(VERIFIER_KEY, verifier);
        const challenge = await sha256(verifier);

        const params = new URLSearchParams({
            client_id: CLIENT_ID,
            response_type: 'code',
            scope: 'openid',
            redirect_uri: REDIRECT_URI,
            code_challenge: challenge,
            code_challenge_method: 'S256',
        });
        window.location.href = `${AUTHORIZE_URL}?${params.toString()}`;
    },

    // Call once on page load. Returns true if a login redirect was just completed (and
    // strips the ?code=... from the URL bar), false if there was nothing to handle.
    async handleRedirectCallback() {
        const params = new URLSearchParams(window.location.search);
        const code = params.get('code');
        if (!code) {
            return false;
        }
        const verifier = sessionStorage.getItem(VERIFIER_KEY);
        const body = new URLSearchParams({
            grant_type: 'authorization_code',
            client_id: CLIENT_ID,
            redirect_uri: REDIRECT_URI,
            code,
            code_verifier: verifier,
        });
        const response = await fetch(TOKEN_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: body.toString(),
        });
        if (!response.ok) {
            throw new Error('Token exchange failed: ' + response.status);
        }
        const tokens = await response.json();
        sessionStorage.setItem(TOKEN_KEY, tokens.access_token);
        if (tokens.id_token) {
            sessionStorage.setItem(ID_TOKEN_KEY, tokens.id_token);
        }
        sessionStorage.removeItem(VERIFIER_KEY);
        window.history.replaceState({}, document.title, window.location.pathname);
        return true;
    },

    // Called on any 401 from the Gateway, and by the logout button.
    clearSession() {
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(ID_TOKEN_KEY);
    },

    logout() {
        const idToken = sessionStorage.getItem(ID_TOKEN_KEY);
        this.clearSession();
        const params = new URLSearchParams({
            post_logout_redirect_uri: REDIRECT_URI,
        });
        if (idToken) {
            params.set('id_token_hint', idToken);
        }
        window.location.href = `${END_SESSION_URL}?${params.toString()}`;
    },
};
```

- [ ] **Step 2: `web-ui/js/app.js`, `web-ui/index.html`, `web-ui/css/styles.css` — bootstrap only, screens come in Tasks 8–9**

`web-ui/js/app.js`:

```javascript
async function bootstrap() {
    const justLoggedIn = await Auth.handleRedirectCallback();
    if (!Auth.isAuthenticated()) {
        await Auth.login();
        return;
    }
    document.getElementById('logout-button').addEventListener('click', () => Auth.logout());
    document.getElementById('app').hidden = false;
    document.getElementById('loading').hidden = true;
    // Task 8 replaces this line with the real onboarding/dashboard check.
    console.log('Authenticated. justLoggedIn =', justLoggedIn);
}

bootstrap().catch((err) => {
    console.error('Bootstrap failed', err);
    document.getElementById('loading').textContent = 'Something went wrong. Please refresh.';
});
```

`web-ui/index.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Bank UI</title>
    <link rel="stylesheet" href="css/styles.css" />
</head>
<body>
    <div id="loading">Loading...</div>
    <div id="app" hidden>
        <header>
            <h1>Bank UI</h1>
            <button id="logout-button" type="button">Log out</button>
        </header>
        <main id="main-content"></main>
    </div>
    <script src="js/auth.js"></script>
    <script src="js/app.js"></script>
</body>
</html>
```

`web-ui/css/styles.css`:

```css
:root {
    --bank-primary: #1b4332;
    --bank-accent: #2d6a4f;
    --bank-bg: #f4f6f5;
}

body {
    font-family: system-ui, sans-serif;
    margin: 0;
    background: var(--bank-bg);
    color: #1a1a1a;
}

header {
    background: var(--bank-primary);
    color: #ffffff;
    padding: 1rem 1.5rem;
    display: flex;
    justify-content: space-between;
    align-items: center;
}

main {
    max-width: 640px;
    margin: 2rem auto;
    padding: 0 1rem;
}

button {
    background: var(--bank-accent);
    color: #ffffff;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    cursor: pointer;
}

.card {
    background: #ffffff;
    border-radius: 8px;
    padding: 1.5rem;
    margin-bottom: 1rem;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}
```

- [ ] **Step 3: `docker-compose.yml` — add the `web-ui` service**

```yaml
  web-ui:
    image: nginx:alpine
    container_name: showcase-web-ui
    volumes:
      - ./web-ui:/usr/share/nginx/html:ro
    ports:
      - "8090:80"
```

- [ ] **Step 4: Live verification (Docker required)**

```bash
docker compose up -d --build web-ui
```

Open `http://localhost:8090` in a browser. Expected: immediate redirect to the (themed) Keycloak login page; after logging in as `ada`/`password` (or a freshly self-registered user), redirect back to `http://localhost:8090/` with the app shell visible and a working "Log out" button that returns to the Keycloak login page.

- [ ] **Step 5: Commit, push, open the PR, stop**

```bash
git checkout -b feature/phase-9-task-7-web-ui-skeleton-auth
git add web-ui docker-compose.yml
git commit -m "feat(web-ui): static skeleton with PKCE login/logout (Phase 9, Task 7)"
git push -u origin feature/phase-9-task-7-web-ui-skeleton-auth
gh pr create --title "feat(web-ui): skeleton + PKCE auth (Phase 9, Task 7)" --body "..."
```

Stop here.

---

### Task 8: Bank UI — onboarding + dashboard

**Files:**
- Create: `web-ui/js/api.js`
- Modify: `web-ui/js/app.js`
- Modify: `web-ui/index.html`

**Interfaces:**
- Consumes: `Auth.getToken()`, `Auth.clearSession()` (Task 7); `GET /accounts/mine`, `POST /accounts` (Tasks 1–2, via the Gateway).
- Produces: `Api.get(path)`, `Api.post(path, body)` — used by Task 9 too.

- [ ] **Step 1: `web-ui/js/api.js`**

```javascript
const GATEWAY_BASE = 'http://localhost:8080';

const ERROR_MESSAGES = {
    ACCOUNT_NOT_FOUND: 'That account could not be found.',
    ACCOUNT_ALREADY_EXISTS: 'You already have an account.',
    INSUFFICIENT_FUNDS: 'Insufficient funds for this transfer.',
    CONCURRENT_MODIFICATION: 'That account was just updated elsewhere — please try again.',
    SAME_ACCOUNT_TRANSFER: 'You cannot transfer to your own account.',
    SOURCE_ACCOUNT_BLOCKED: 'Your account is currently blocked from sending money.',
    DESTINATION_ACCOUNT_BLOCKED: 'The destination account is currently blocked.',
    ACCOUNT_SERVICE_UNAVAILABLE: 'The banking system is temporarily unavailable. Please try again shortly.',
    SOURCE_FRAUD_SERVICE_UNAVAILABLE: 'The banking system is temporarily unavailable. Please try again shortly.',
    DESTINATION_FRAUD_SERVICE_UNAVAILABLE: 'The banking system is temporarily unavailable. Please try again shortly.',
    COMPENSATION_REQUIRED: 'The transfer could not be completed and is being reversed automatically.',
    VALIDATION_FAILED: 'Please check the values you entered.',
};

class ApiError extends Error {
    constructor(problem) {
        super(problem.detail || 'Request failed');
        this.code = problem.code;
        this.problem = problem;
    }

    friendlyMessage() {
        return ERROR_MESSAGES[this.code] || 'Something went wrong. Please try again.';
    }
}

async function request(method, path, body) {
    const response = await fetch(GATEWAY_BASE + path, {
        method,
        headers: {
            'Content-Type': 'application/json',
            Authorization: 'Bearer ' + Auth.getToken(),
        },
        body: body ? JSON.stringify(body) : undefined,
    });

    if (response.status === 401) {
        Auth.clearSession();
        await Auth.login();
        throw new Error('Session expired, redirecting to login');
    }

    if (!response.ok) {
        const problem = await response.json().catch(() => ({ code: 'UNEXPECTED_ERROR', detail: response.statusText }));
        throw new ApiError(problem);
    }

    if (response.status === 204) {
        return null;
    }
    return response.json();
}

const Api = {
    get: (path) => request('GET', path),
    post: (path, body) => request('POST', path, body),
};
```

- [ ] **Step 2: Onboarding + dashboard rendering in `app.js`**

Replace `bootstrap()`'s last lines:

```javascript
async function bootstrap() {
    await Auth.handleRedirectCallback();
    if (!Auth.isAuthenticated()) {
        await Auth.login();
        return;
    }
    document.getElementById('logout-button').addEventListener('click', () => Auth.logout());
    document.getElementById('app').hidden = false;
    document.getElementById('loading').hidden = true;

    const accounts = await Api.get('/accounts/mine');
    if (accounts.length === 0) {
        renderOnboarding();
    } else {
        await renderDashboard(accounts[0]);
    }
}

function renderOnboarding() {
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card">
            <h2>Welcome! Let's set up your account.</h2>
            <p>You'll start with a balance of $1000.00.</p>
            <label for="owner-name">Your name</label>
            <input id="owner-name" type="text" />
            <button id="create-account-button" type="button">Create my account</button>
            <p id="onboarding-error" class="error" hidden></p>
        </div>`;

    document.getElementById('create-account-button').addEventListener('click', async () => {
        const ownerName = document.getElementById('owner-name').value.trim();
        const errorEl = document.getElementById('onboarding-error');
        errorEl.hidden = true;
        if (!ownerName) {
            errorEl.textContent = 'Please enter your name.';
            errorEl.hidden = false;
            return;
        }
        try {
            const account = await Api.post('/accounts', { ownerName, initialBalance: '1000.00' });
            renderDashboard(account);
        } catch (err) {
            errorEl.textContent = err.friendlyMessage ? err.friendlyMessage() : err.message;
            errorEl.hidden = false;
        }
    });
}

function renderDashboard(account) {
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card">
            <h2>${account.ownerName}</h2>
            <p class="balance">$${Number(account.balance).toFixed(2)}</p>
            <button id="send-money-button" type="button">Send money</button>
        </div>
        <div id="transfer-section"></div>
        <div id="history-section"></div>`;
    // Task 9 wires send-money-button and fills transfer-section/history-section.
}
```

Add `.error { color: #b00020; }` and `.balance { font-size: 2rem; font-weight: 700; }` to `styles.css`.

In `index.html`, add `<script src="js/api.js"></script>` before `<script src="js/app.js"></script>`.

- [ ] **Step 3: Live verification (Docker required)**

```bash
$env:GIT_SHA = git rev-parse --short HEAD
docker compose up -d --build gateway-service account-service web-ui
```

Register a brand-new user via Keycloak's registration page, get redirected back to `http://localhost:8090`, confirm the onboarding screen appears; submit a name, confirm the dashboard shows a $1000.00 balance; refresh the page and confirm onboarding does NOT reappear (dashboard shows directly).

- [ ] **Step 4: Commit, push, open the PR, stop**

```bash
git checkout -b feature/phase-9-task-8-onboarding-dashboard
git add web-ui/js/api.js web-ui/js/app.js web-ui/index.html web-ui/css/styles.css
git commit -m "feat(web-ui): onboarding and dashboard (Phase 9, Task 8)"
git push -u origin feature/phase-9-task-8-onboarding-dashboard
gh pr create --title "feat(web-ui): onboarding + dashboard (Phase 9, Task 8)" --body "..."
```

Stop here.

---

### Task 9: Bank UI — transfer, history, quick-transfers

**Files:**
- Modify: `web-ui/js/app.js`

**Interfaces:**
- Consumes: `Api.get/post` (Task 8); `POST /transfers`, `GET /transfers/mine`, `GET /accounts/{id}/summary` (Tasks 1–4).

- [ ] **Step 1: Replace Task 8's `renderDashboard` (not append — this supersedes that definition) to wire the new sections**

```javascript
// Added in Task 10's fix wave: escapes untrusted text (another self-registered user's
// free-text ownerName) before it is interpolated into innerHTML, closing a stored-XSS hole.
function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function renderDashboard(account, { flashMessage } = {}) {
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card">
            <h2>${escapeHtml(account.ownerName)}</h2>
            <p class="balance">$${Number(account.balance).toFixed(2)}</p>
            <button id="send-money-button" type="button">Send money</button>
        </div>
        <p id="dashboard-flash" class="success" hidden></p>
        <div id="transfer-section"></div>
        <div id="quick-transfers-section"></div>
        <div id="history-section"></div>`;

    if (flashMessage) {
        const flashEl = document.getElementById('dashboard-flash');
        flashEl.textContent = flashMessage;
        flashEl.hidden = false;
    }

    const transfers = await Api.get('/transfers/mine');
    document.getElementById('send-money-button')
        .addEventListener('click', () => renderTransferForm(account, transfers));
    await renderQuickTransfers(transfers);
    await renderHistory(transfers);
}

function renderTransferForm(account, transfers, prefillAccountId) {
    const section = document.getElementById('transfer-section');
    section.innerHTML = `
        <div class="card">
            <h3>Send money</h3>
            <label for="to-account">To account ID</label>
            <input id="to-account" type="text" value="${prefillAccountId || ''}" />
            <label for="amount">Amount</label>
            <input id="amount" type="number" step="0.01" min="0.01" />
            <button id="submit-transfer-button" type="button">Send</button>
            <p id="transfer-error" class="error" hidden></p>
            <p id="transfer-success" hidden></p>
        </div>`;

    document.getElementById('submit-transfer-button').addEventListener('click', async () => {
        const toAccountId = document.getElementById('to-account').value.trim();
        const amount = document.getElementById('amount').value;
        const errorEl = document.getElementById('transfer-error');
        const successEl = document.getElementById('transfer-success');
        errorEl.hidden = true;
        successEl.hidden = true;
        try {
            await Api.post('/transfers', { fromAccountId: account.id, toAccountId, amount });
            const refreshedAccounts = await Api.get('/accounts/mine');
            await renderDashboard(refreshedAccounts[0], { flashMessage: 'Transfer completed.' });
        } catch (err) {
            errorEl.textContent = err.friendlyMessage ? err.friendlyMessage() : err.message;
            errorEl.hidden = false;
        }
    });
}

async function renderQuickTransfers(transfers) {
    const section = document.getElementById('quick-transfers-section');
    const recentFirst = [...transfers].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    const seen = new Set();
    const distinctRecipients = [];
    for (const transfer of recentFirst) {
        if (!seen.has(transfer.toAccountId)) {
            seen.add(transfer.toAccountId);
            distinctRecipients.push(transfer.toAccountId);
        }
    }

    if (distinctRecipients.length === 0) {
        section.innerHTML = '';
        return;
    }

    // Only ever offer a quick-transfer to an account that still exists. GET /accounts/{id}/summary
    // has no ownership restriction (any authenticated caller can look up any id), so a failed
    // lookup here reliably means the account itself is gone -- e.g. a past transfer attempt to a
    // mistyped or otherwise nonexistent id -- not a permissions issue. Those are excluded rather
    // than shown with a fallback label, since a quick-transfer button that just fails again isn't
    // useful.
    const summaryResults = await Promise.all(
        distinctRecipients.map((id) => Api.get(`/accounts/${id}/summary`)
            .then((summary) => ({ id, summary }))
            .catch(() => null)));
    const existingRecipients = summaryResults.filter((result) => result !== null);

    if (existingRecipients.length === 0) {
        section.innerHTML = '';
        return;
    }

    section.innerHTML = `
        <div class="card">
            <h3>Quick transfers</h3>
            <ul id="quick-transfers-list"></ul>
        </div>`;
    const list = document.getElementById('quick-transfers-list');
    existingRecipients.forEach(({ id, summary }) => {
        const li = document.createElement('li');
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = summary.ownerName;
        button.addEventListener('click', async () => {
            const accounts = await Api.get('/accounts/mine');
            renderTransferForm(accounts[0], transfers, id);
        });
        li.appendChild(button);
        list.appendChild(li);
    });
}

async function renderHistory(transfers) {
    const section = document.getElementById('history-section');
    const distinctRecipients = [...new Set(transfers.map((t) => t.toAccountId))];
    const summaries = await Promise.all(
        distinctRecipients.map((id) => Api.get(`/accounts/${id}/summary`).catch(() => ({ ownerName: id }))));
    const nameByAccountId = Object.fromEntries(
        distinctRecipients.map((id, index) => [id, summaries[index].ownerName]));

    const rows = [...transfers]
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
        .map((t) => `<tr><td>${new Date(t.createdAt).toLocaleString()}</td><td>${escapeHtml(nameByAccountId[t.toAccountId])}</td>
            <td>$${Number(t.amount).toFixed(2)}</td><td>${t.status}</td></tr>`)
        .join('');
    section.innerHTML = `
        <div class="card">
            <h3>Transfer history</h3>
            <table>
                <thead><tr><th>Date</th><th>To</th><th>Amount</th><th>Status</th></tr></thead>
                <tbody>${rows || '<tr><td colspan="4">No transfers yet.</td></tr>'}</tbody>
            </table>
        </div>`;
}
```

- [ ] **Step 2: Manual verification (Docker required) — full walkthrough**

```bash
$env:GIT_SHA = git rev-parse --short HEAD
docker compose up -d --build
```

Walk through, using two different self-registered users (or `ada`/`bob`, both pre-seeded with `customer` — note: `ada`/`bob` predate Task 2's rule and may already violate it if accounts were created for them before this phase; prefer two freshly self-registered users for a clean run):
1. Register user A, onboard, note the dashboard shows $1000.00.
2. Register user B, onboard, copy B's account ID (visible via `GET /accounts/mine` in dev tools, or have B check their own dashboard — the UI itself never shows another user's raw ID by design, so use dev tools/network tab for this manual step).
3. As A, send $50.00 to B's account ID. Confirm A's balance drops to $950.00 and the transfer appears in A's history as COMPLETED with B's name shown.
4. As A, attempt a transfer for more than the remaining balance. Confirm a friendly "Insufficient funds" message, no balance change.
5. Refresh A's dashboard. Confirm the Quick Transfers panel shows B's name; click it; confirm the transfer form prefills B's account ID.
6. Log out as A; confirm redirect to Keycloak login. Log back in as A; confirm the dashboard (not onboarding) loads directly.

- [ ] **Step 3: Commit, push, open the PR, stop**

```bash
git checkout -b feature/phase-9-task-9-transfer-history-quick-transfers
git add web-ui/js/app.js
git commit -m "feat(web-ui): transfer form, history, and quick-transfers (Phase 9, Task 9)"
git push -u origin feature/phase-9-task-9-transfer-history-quick-transfers
gh pr create --title "feat(web-ui): transfer + history + quick-transfers (Phase 9, Task 9)" --body "..."
```

Stop here.

---

### Task 10: Docs sync + whole-branch review

**Files:**
- Modify: `README.md`
- Modify: `docs/service-links.html`
- Modify: `docs/phase-9-bank-ui.md` (this document — flip any "Not started" language, record actual outcomes/deviations)

- [ ] **Step 1: README and service-links sync**

Add the Bank UI's URL (`http://localhost:8090`) to `README.md`'s services list and `docs/service-links.html`, matching how the other five services' ports are already documented there (`grep -n "8080\|8081\|8180" README.md docs/service-links.html` to find the existing pattern to follow).

- [ ] **Step 2: Whole-branch review (dispatch a more capable model than the per-task implementers — see CLAUDE.md's Subagent Model Policy)**

Give the reviewing model this exact checklist, one stated result per item, not "looks fine":

1. `grep -rn "ownerId" account-service/src/main/java/com/showcase/account/domain/Account.java` shows `unique = true` on the column, and `./mvnw -pl account-service -Dtest=AccountControllerIT test` passes reactor-wide (not just the new tests) — confirms Task 2's test-infra fix didn't leave any call site broken.
2. `grep -rn "ACCOUNT_ALREADY_EXISTS\|ACCOUNT_NOT_FOUND\|INSUFFICIENT_FUNDS" web-ui/js/api.js` — every code Account/Transfer/Fraud can actually return on a path the UI calls has an entry in `ERROR_MESSAGES` (cross-check against each service's `ApiExceptionHandler`/`TransferFailureCode`, not just what's listed in this plan).
3. `grep -rn "Authorization" gateway-service/src/main/java/com/showcase/gateway/config/SecurityConfig.java` confirms the CORS `allowedHeaders` list includes `Authorization` — its absence would make every UI call fail CORS preflight silently.
4. Confirm no `/accounts/{id}/debit` or `/accounts/{id}/credit` route was accidentally added to `GatewayRoutesConfig` while touching `accountRoutes` — those must stay unreachable through the Gateway (Phase 6 invariant).
5. `docker compose up -d --build` (full stack, GIT_SHA set) succeeds and every service reports healthy; the Task 9 Step 2 manual walkthrough passes end to end against the freshly built stack, not a partially-rebuilt one.
6. `grep -rln "Phase 9" docs/*.md CLAUDE.md` — confirm every remaining reference to "Phase 9" in the repo now means the Bank UI, not the (renumbered) saga tests.

- [ ] **Step 3: Commit, push, open the PR, stop**

```bash
git checkout -b feature/phase-9-task-10-docs-sync-review
git add README.md docs/service-links.html docs/phase-9-bank-ui.md
git commit -m "docs: sync README/service-links, record Phase 9 outcome (Phase 9, Task 10)"
git push -u origin feature/phase-9-task-10-docs-sync-review
gh pr create --title "docs: Phase 9 wrap-up (Task 10)" --body "..."
```

Stop here — this is the last task in the phase.
