# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Phases 1 and 2 are implemented and merged to `master`:

- **Account Service** (`account-service/`, port 8081) — accounts and balances, debit/credit with optimistic locking.
- **Transfer Service** (`transfer-service/`, port 8082) — orchestrates the transfer saga over synchronous HTTP into Account Service.

Both are Spring Boot 3.3.8 / Java 21, JPA on Postgres (database-per-service), Testcontainers-tested, Lombok, and reachable via `docker compose up`. Both return RFC 7807 `application/problem+json` errors carrying a stable `code` property. (Boot bumped 3.3.4 → 3.3.8 in Phase 6, project-wide via the root `pom.xml`'s parent version — see `docs/phase-6-api-gateway.md`'s Design Decisions for why.)

See `docs/microservices-showcase-design.md` for the architecture, and `docs/phase-1-*.md` / `docs/phase-2-*.md` for what each phase built. **Do not re-derive architecture decisions already settled in those files.**

Phase 3+ adds the remaining pieces (Resilience4j + compensation, the outbox + Kafka + Notification, Fraud, Gateway/Auth, observability) — see `docs/roadmap.md` for the sequence.

**Before starting Phase 3, read the deferral table in `docs/phase-2-transfer-service-saga.md`.** It records four blocking preconditions for the compensator, all found during Phase 2's reviews. The critical one: a downstream call that fails with `ACCOUNT_SERVICE_UNAVAILABLE` has an **unknown** outcome, not a failed one — a read timeout cannot be distinguished from a non-delivery. Compensating blindly invents money on the debit leg and duplicates it on the credit leg, so the compensator must reconcile against Account before crediting anything back.

## Documentation conventions

- **Design spec:** `docs/microservices-showcase-design.md` — the source of truth for architecture, service boundaries, and tech stack. Update it (not just the phases) if an implementation decision changes the actual architecture.
- **Implementation phases:** `docs/phase-N-<feature-name>.md` (e.g. `docs/phase-1-foundation-account-service.md`) — one per build increment, in execution order. This project uses this location/naming instead of the `docs/superpowers/plans/` default; keep new phases consistent with it.
- **Roadmap:** `docs/roadmap.md` — tracks which phases are done/not-started and their forecast scope. Update its status/scope row whenever a phase starts, finishes, or its actual scope diverges from the forecast.

## Local environment

- Java 21+ is a Global Constraint (see the phase docs) — required for virtual threads. The default `java` on PATH is not the right version for this project; use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` to it before running Maven.
- Lombok is used for entity boilerplate (getters, JPA no-arg constructors) — see `account-service`'s `pom.xml`/`Account.java` for the pattern (`@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic rather than forcing it through Lombok).

## Git workflow

- Never commit directly to `master`. All work happens on a `feature/*` branch.
- Branch naming: `feature/phase-<N>-task-<M>-<short-description>` (e.g. `feature/phase-1-task-1-project-scaffolding`), matching the phase/task the branch implements.
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

## Subagent model policy

- **Default to `haiku` for implementer and routine review subagents.** Cost matters on this project; opus for everything is not affordable.
- **Use a more capable model for the high-stakes gates** — in particular the final whole-branch review at the end of a phase. In Phase 2 that single dispatch caught a money-creation bug (a 3xx response read as a committed debit) that six per-task reviews had missed.
- Give smaller models **explicit checklists** rather than open-ended "check this hard" framing — the ability to generate its own lines of attack is the first thing that degrades. State a required result per item.
- Always name the model explicitly when dispatching; an omitted model inherits the session's, which is usually the expensive one.
