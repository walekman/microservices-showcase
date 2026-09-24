# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Phases 1–9 (including 7b and 8b) and Phase 11 (Notification persistence + Kafka consumer error handling) are implemented and merged to `master`. Phase 10 (end-to-end saga tests) has not started. `docs/roadmap.md` indexes every phase and its scope.

Five Spring Boot / Java 21 services plus a static UI, all brought up by `docker compose up`:

- **Gateway Service** (`gateway-service/`, port 8080) — routing-only Spring Cloud Gateway Server MVC in front of Transfer (`/transfers/**`) and five client-safe Account paths (`POST /accounts`, `GET /accounts`, `/accounts/mine`, `/accounts/{id}`, `/accounts/{id}/summary`) — see `GatewayRoutesConfig`. Account's `debit`/`credit` and all of Fraud/Notification have no route.
- **Account Service** (`account-service/`, port 8081) — accounts and balances, debit/credit with optimistic locking and an idempotency ledger. JPA on Postgres.
- **Transfer Service** (`transfer-service/`, port 8082) — orchestrates the transfer saga over synchronous HTTP into Account and Fraud, with Resilience4j, a compensation scheduler and a transactional outbox to Kafka. JPA on Postgres.
- **Notification Service** (`notification-service/`, port 8083) — Kafka consumer of the outbox topics; stores one row per transfer outcome (JPA on Postgres), idempotently. Transient DB failures are retried in place without limit; everything else is dead-lettered to `<topic>-dlt` (`KafkaErrorHandlingConfig`).
- **Fraud Service** (`fraud-service/`, port 8084) — stateless account-blocklist screen, no database.
- **Bank UI** (`web-ui/`, host port 8090) — plain HTML/CSS/JS served by `nginx:alpine`, no build step; calls the Gateway from the browser with OAuth2 Authorization Code + PKCE via Keycloak's `showcase-ui` client.

Supporting containers: Postgres (database-per-service), Kafka (KRaft), Keycloak (`showcase` realm, host port 8180), OTel Collector → Tempo, Prometheus, Grafana (host port 3001). Every service validates the JWT itself, and returns RFC 7807 `application/problem+json` errors carrying a stable `code` property.

Versions live in the root `pom.xml`: Spring Boot 3.5.16 (bumped from 3.3.8 in Phase 8 Task 1), springdoc-openapi 2.8.17, Resilience4j 2.4.0, Testcontainers 1.21.4; Spring Cloud 2025.0.3 is pinned in `gateway-service/pom.xml`. Lombok and Testcontainers are used throughout.

See `docs/microservices-showcase-design.md` for the architecture, and `docs/phase-N-*.md` for what each phase built. **Do not re-derive architecture decisions already settled in those files.**

## Saga invariant

**Preserve this when touching the saga or compensator:** a downstream call that fails with `ACCOUNT_SERVICE_UNAVAILABLE` has an **unknown** outcome, not a failed one — a read timeout cannot be distinguished from a non-delivery. Compensating blindly invents money on the debit leg and duplicates it on the credit leg, so the compensator reconciles by replaying the same idempotency key against Account (Phase 3's `AccountOperation` ledger) rather than crediting anything back on a guess. The original findings are in the deferral table of `docs/phase-2-transfer-service-saga.md`.

A live-saga debit whose outcome is unknown is therefore left `PENDING` (the API answers `503` with `transferStatus: PENDING`), and `CompensationScheduler`'s stale-`PENDING` sweep settles it by replaying `<transferId>:debit` — see §4 of the design doc. Never settle such a row as `FAILED`: nothing revisits `FAILED`.

## Documentation conventions

- **Design spec:** `docs/microservices-showcase-design.md` — the source of truth for architecture, service boundaries, and tech stack. Update it (not just the phases) if an implementation decision changes the actual architecture.
- **Implementation phases:** `docs/phase-N-<feature-name>.md` (e.g. `docs/phase-1-foundation-account-service.md`) — one per build increment, in execution order. This project uses this location/naming instead of the `docs/superpowers/plans/` default; keep new phases consistent with it.
- **Roadmap:** `docs/roadmap.md` — tracks which phases are done/not-started and their forecast scope. Update its status/scope row whenever a phase starts, finishes, or its actual scope diverges from the forecast.
- **Open items:** `docs/open-items.md` — every open gap, planned feature, tech-debt item and deliberate non-goal, each pointing back to the phase doc that raised it. Add to it when a phase defers something; remove an item when it lands.

## Local environment

- Java 21+ is a Global Constraint (see the phase docs) — required for virtual threads. The default `java` on PATH is not the right version for this project; use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` to it before running Maven.
- Build and test with the wrapper from the repo root: `./mvnw test`. Surefire runs the `*IT` classes too, so this is the whole suite; it needs Docker running (Testcontainers) and takes a few minutes. One class in one module: `./mvnw -pl <module> -Dtest=<Class> test`. Compose builds each image from source, so after a code or `pom.xml` change `docker compose up -d --build --no-deps <service>` picks it up.
- Lombok is used for entity boilerplate (getters, JPA no-arg constructors) — see `account-service`'s `pom.xml`/`Account.java` for the pattern (`@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic rather than forcing it through Lombok).
- Pass the git commit into the images with `GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build` (PowerShell: `$env:GIT_SHA = git rev-parse --short HEAD; docker compose up -d --build`); without it `/actuator/info` and the Service Versions dashboard show `commit: unknown`. Also: `build-info` runs at `generate-resources`, so an offline (`-o`) Maven build on a cold cache fails to resolve the Boot plugin — run one online build first.
- A fresh `git worktree` checkout does not carry the `.env` file (it's gitignored, untracked); copy `.env` from an existing checkout before running `docker compose up`, or `docker compose up` will fail at Postgres with unset `POSTGRES_PASSWORD` and related variables.
- There is no Flyway/Liquibase: schemas evolve through Hibernate's `ddl-auto: update`, and Compose's Postgres keeps data in the named `postgres-data` volume. A schema change that existing rows violate (a new `unique` or `NOT NULL`) is only logged as a warning, and the service boots without the constraint. After pulling a schema-changing phase, run `docker compose down -v` before `up` (see `docs/phase-9-bank-ui.md`, "Running Task 2 against an existing local stack").
- CI (`.github/workflows/ci.yml`) runs `./mvnw -B test` on JDK 21 for every PR and every push to `master` — the same full suite, Testcontainers included. `AccountControllerIT`'s two concurrency tests hold the account row locked in Postgres until every request is parked mid-transaction, rather than timing requests with a barrier. Don't revert them to a barrier: see `docs/investigation-account-controller-it-concurrency-flake.md` for why that flaked on CI.

## Git workflow

- Never commit directly to `master`. All work happens on a `feature/*` (or `fix/*`) branch.
- Branch naming: `feature/phase-<N>-task-<M>-<short-description>` (e.g. `feature/phase-1-task-1-project-scaffolding`), matching the phase/task the branch implements. Work outside a phase uses `feature/<short-description>` (or `fix/<short-description>` for a bug fix).
- Merge to `master` only via a pull request — no direct pushes or merges to `master`.
- **Subagent review is not automatic.** The user reviews PRs manually; only dispatch a subagent review when the user asks for one.
- Never merge a PR autonomously. Open it, then stop — the user reviews and merges it themselves. The user says "merge it" to authorise one; treat that as genuine authorisation, but check `gh pr view <n> --json mergeable,statusCheckRollup` before merging.
- **One PR per task, and stop after each.** When executing a multi-task phase, each task gets its own branch off the *current* `master` and its own PR — then stop until the user merges. The next branch then starts from merged code, so each PR's diff shows only that task's work.

## Executing implementation phases

Hard-won during Phase 2. These are not style preferences; each one cost a real defect.

- **Sync review fixes back into the phase document, not just the code.** When a review finds a defect in code that came from a `docs/phase-N-*.md` code block, fix the phase doc too — re-sync the affected block verbatim from the merged source rather than hand-editing it. Phase 2 accumulated six fixes that lived only in code, including a surrogate-pair guard, a `PENDING`-orphan guard, and a client that read a 3xx redirect as a committed debit. That is not cosmetic staleness: later phases copy these code blocks into new services, so a stale block reproduces the bug. Audit by grepping a distinctive string from each fix against the phase doc.
- **Tell implementer subagents to read the brief critically rather than transcribe it.** Include: *"If something in the brief is wrong or impossible, report it rather than silently working around it — read it critically rather than transcribing it."* Phase 2 measured the difference: a faithful transcription shipped three design defects from the brief, while implementers told to push back caught an impossible test instruction and a missing exception handler (the latter proved with a probe). A reviewer reviewing a transcription is reviewing the phase author's design, not the implementation — so defects surface a round later.
- **When an implementer says a specified approach cannot work, check the claim before overriding it.** Both such claims in Phase 2 were correct.
- **Verify subagent claims that matter rather than accepting them.** Re-run the suite yourself, read the config, check the numbers. Reported test counts have been module-scoped rather than reactor-wide, and a "verified" Docker build had not actually been run.
- **A Spring Boot bump silently breaks pinned dependencies — re-check every pin at runtime, not just at startup.** Boot's BOM does not manage `springdoc-openapi`, `resilience4j` or `testcontainers` (the root `pom.xml` properties). Phase 8 Task 1 moved Boot 3.3.8 → 3.5.16 and left springdoc at 2.6.0: it compiled and started healthy, but every `GET /v3/api-docs` returned a 500 (`NoSuchMethodError` against Spring Framework 6.2). Swagger UI's static page still returned 200, so nobody noticed until they opened it. Fixed by bumping to 2.8.17, with an `OpenApiDocsIT` in Account, Transfer and Fraud that requests the spec. On the next bump, check each pin against the new Boot line and exercise the feature itself. The "do not bump springdoc `2.6.0`" constraints in `docs/phase-2` to `phase-4` held only for Boot 3.3.x, and are marked superseded there.
- **When executing via subagent-driven-development with one git worktree per task, the coordinator — never an implementer subagent — brings down other worktrees' `docker compose` stacks before a task's live-verification step.** `docker-compose.yml` uses fixed `container_name` values (`showcase-keycloak`, etc.), not scoped per checkout, so two worktrees' stacks collide on the same names. During Phase 9 Task 6, an implementer hit this collision and unilaterally removed another worktree's stopped container to unblock itself — harmless in that instance (Keycloak carries no persistent volume here, so nothing was actually lost) but exactly the kind of cross-worktree side effect a subagent should never resolve on its own initiative. Stop other stacks yourself, in the coordinating session, before dispatching any task whose brief needs Docker.

## Subagent model policy

- **Default to `haiku` for implementer and routine review subagents.** Cost matters on this project; opus for everything is not affordable.
- **Use a more capable model for the high-stakes gates** — in particular the final whole-branch review at the end of a phase. In Phase 2 that single dispatch caught a money-creation bug (a 3xx response read as a committed debit) that six per-task reviews had missed.
- Give smaller models **explicit checklists** rather than open-ended "check this hard" framing — the ability to generate its own lines of attack is the first thing that degrades. State a required result per item.
- Always name the model explicitly when dispatching; an omitted model inherits the session's, which is usually the expensive one.
