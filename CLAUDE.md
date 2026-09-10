# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Plan 1 (Foundation + Account Service) is implemented and merged to `master`: a working Account Service (Spring Boot 3.3.4, Java 21, JPA/Postgres, Testcontainers-tested, Lombok, Docker Compose deployable) exists under `account-service/`. See `docs/microservices-showcase-design.md` for the overall architecture and `docs/plan-1-foundation-account-service.md` for what Plan 1 built. Future plans (Plan 2+) will add the remaining services (Transfer, Fraud, Notification, Auth, API Gateway). Do not re-derive architecture decisions that are already settled in those two files.

## Documentation conventions

- **Design spec:** `docs/microservices-showcase-design.md` — the source of truth for architecture, service boundaries, and tech stack. Update it (not just the plans) if an implementation decision changes the actual architecture.
- **Implementation plans:** `docs/plan-N-<feature-name>.md` (e.g. `docs/plan-1-foundation-account-service.md`) — one per build increment, in execution order. This project uses this location/naming instead of the `docs/superpowers/plans/` default; keep new plans consistent with it.
- **Roadmap:** `docs/roadmap.md` — tracks which plans are done/not-started and their forecast scope. Update its status/scope row whenever a plan starts, finishes, or its actual scope diverges from the forecast.

## Local environment

- Java 21+ is a Global Constraint (see the plan docs) — required for virtual threads. The default `java` on PATH is not the right version for this project; use `C:\dev\openjdk-21.0.2` and set `JAVA_HOME` to it before running Maven.
- Lombok is used for entity boilerplate (getters, JPA no-arg constructors) — see `account-service`'s `pom.xml`/`Account.java` for the pattern (`@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; hand-write any constructor with custom logic rather than forcing it through Lombok).

## Git workflow

- Never commit directly to `master`. All work happens on a `feature/*` branch.
- Branch naming: `feature/plan-<N>-task-<M>-<short-description>` (e.g. `feature/plan-1-task-1-project-scaffolding`), matching the plan/task the branch implements.
- Merge to `master` only via a pull request — no direct pushes or merges to `master`.
- Every PR must be reviewed by a subagent before merging.
- Never merge a PR autonomously. Open it, get the subagent review, then stop — the user reviews and merges it themselves.
