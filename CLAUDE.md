# CLAUDE.md

Guidance for Claude Code in this repository.

## Project

Phases 1–12 (plus 7b, 8b) are done; `docs/roadmap.md` indexes them. Architecture lives in
`docs/microservices-showcase-design.md` and each phase's build in `docs/phase-N-*.md`.
**Do not re-derive decisions already settled there.**

Six Spring Boot 3.5 / Java 21 services and a static UI, all started by `docker compose up`:

| Service | Port | Role |
|---|---|---|
| `gateway-service` | 8080 | Routing only (`GatewayRoutesConfig`): `/transfers/**`, five client-safe Account paths, `GET /fx/rates`. No route to Account debit/credit, Fraud, Notification. |
| `account-service` | 8081 | Balances; debit/credit with optimistic locking and the `AccountOperation` idempotency ledger. |
| `transfer-service` | 8082 | Saga over sync HTTP to Account/Fraud/FX, Resilience4j, `CompensationScheduler`, transactional outbox → Kafka. |
| `notification-service` | 8083 | Kafka consumer; one row per outcome, idempotent; DB errors retried in place, all else → `<topic>-dlt`. |
| `fraud-service` | 8084 | Blocklist in its own DB; `PUT`/`DELETE /fraud/blocklist/{id}`, `fraud-admin` only. |
| `fx-service` | 8085 | Frankfurter/ECB rates behind Redis (fresh TTL, last-known fallback, single-flight lock). No DB. |
| `web-ui` | 8090 | Plain HTML/JS on nginx, Auth Code + PKCE via Keycloak `showcase-ui`. |

Also: Postgres (database per service), Kafka (KRaft), Redis, Keycloak (8180), OTel Collector →
Tempo, Prometheus, Grafana (3001). Every service validates the JWT itself and returns RFC 7807
errors with a stable `code`. Versions are in the root `pom.xml` (Spring Cloud in
`gateway-service/pom.xml`); Lombok and Testcontainers throughout.

## Saga invariant

**Preserve this when touching the saga or compensator:** a call failing with
`ACCOUNT_SERVICE_UNAVAILABLE` has an **unknown** outcome — a read timeout can't be told apart
from non-delivery. Never compensate on a guess (it invents money on the debit leg, duplicates
it on the credit leg); reconcile by replaying the same idempotency key against Account.

So a live debit with unknown outcome stays `PENDING` (API answers `503`,
`transferStatus: PENDING`) and the stale-`PENDING` sweep settles it by replaying
`<transferId>:debit`. Never settle such a row `FAILED`: nothing revisits `FAILED`. Background:
design doc §4, deferral table in `docs/phase-2-transfer-service-saga.md`.

## Documentation conventions

- Design doc is the source of truth — update it when an implementation changes the architecture.
- Phases go in `docs/phase-N-<feature-name>.md` (not `docs/superpowers/plans/`).
- `docs/roadmap.md`: update the phase's row when it starts, finishes, or its scope diverges.
- `docs/open-items.md`: add what a phase defers (pointing to that phase doc); remove it when it lands.

## Local environment

- Maven needs `JAVA_HOME=C:\dev\openjdk-21.0.2` — the `java` on PATH is the wrong version.
- `./mvnw test` from the root is the full suite (Surefire runs `*IT` too; needs Docker; a few
  minutes). One class: `./mvnw -pl <module> -Dtest=<Class> test`. CI runs `./mvnw -B test`.
- After a code/`pom.xml` change: `docker compose up -d --build --no-deps <service>`.
  Set `GIT_SHA=$(git rev-parse --short HEAD)` (PowerShell: `$env:GIT_SHA = git rev-parse --short HEAD`)
  or `/actuator/info` shows `commit: unknown`. An offline (`-o`) build on a cold cache fails
  on the Boot plugin's `build-info` — run one online build first.
- `.env` is gitignored: copy it into a fresh worktree, or Postgres fails on unset passwords.
- No Flyway: schemas use Hibernate `ddl-auto: update`, and a new `unique`/`NOT NULL` over
  existing rows is only a warning. After a schema-changing pull, `docker compose down -v` first.
- Lombok for entities: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
  (see `Account.java`); hand-write constructors that have logic.
- E2E: `./mvnw -Pe2e -pl e2e-tests verify` (local only). It runs its own stack (project
  `showcase-e2e-*`, random ports) and removes it; `-De2e.keepStack=true` keeps it. Leftovers:
  `docker compose ls`, then `docker compose -p <name> down -v --rmi local`. This machine's
  ~7.4 GB Docker VM can't hold two stacks — `docker compose stop` the dev stack first, `start` after.
- `AccountControllerIT`'s concurrency tests hold the row lock in Postgres on purpose; don't
  revert them to a barrier (`docs/investigation-account-controller-it-concurrency-flake.md`).

## Git workflow

- Never commit or push to `master`; work on `feature/*` or `fix/*` and merge only via PR.
- Branch names: `feature/phase-<N>-task-<M>-<short-description>`; outside a phase,
  `feature/<short-description>` or `fix/<short-description>`.
- Never merge a PR yourself: open it and stop. "merge it" from the user authorises a merge —
  check `gh pr view <n> --json mergeable,statusCheckRollup` first.
- Don't dispatch a subagent review unless asked; the user reviews PRs.
- **Phases (from Phase 10):** a phase lives on `feature/phase-<N>`, starting with its spec
  commit, with a draft PR into `master` opened at once and merged by the user when the phase is
  done. Each task branches off the current phase branch, gets its own PR back into it, and then
  you stop.

## Executing implementation phases

Each of these cost a real defect.

- **Sync review fixes back into the phase doc**, re-copying the block verbatim from merged
  source. Later phases copy those blocks, so a stale one reproduces the bug (Phase 2 left six
  fixes code-only). Audit by grepping a distinctive string from each fix against the doc.
- **Tell implementer subagents to read the brief critically:** *"If something in the brief is
  wrong or impossible, report it rather than silently working around it — read it critically
  rather than transcribing it."* Transcribers shipped the brief's defects; critical readers caught them.
- **When an implementer says the specified approach can't work, check before overriding** —
  both such claims in Phase 2 were right.
- **Verify subagent claims that matter:** re-run the suite, read the config. Reported test
  counts have been module-scoped, and a "verified" Docker build had never run.
- **On a Spring Boot bump, re-check every pin by exercising the feature.** Boot's BOM doesn't
  manage springdoc, resilience4j or testcontainers; the 3.5 bump left springdoc 2.6.0, which
  started healthy but 500'd on every `/v3/api-docs` (`OpenApiDocsIT` now guards it).
- **One worktree per task: the coordinator, never a subagent, stops other worktrees' compose
  stacks before live verification.** Fixed `container_name`s collide across checkouts, and in
  Phase 9 an implementer deleted another worktree's container to unblock itself.

## Subagent model policy

- Always name the model. Default `haiku` for implementers and routine reviews — cost matters.
- Use a stronger model for high-stakes gates, especially the final whole-branch review of a
  phase (in Phase 2 it caught a money-creation bug six per-task reviews missed).
- Give smaller models explicit checklists with a required result per item, not "check this hard".
