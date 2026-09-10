# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This repository is currently in the **planning phase** — it contains a design spec and implementation plans, but no application code yet (no `pom.xml`, no `src/`, no `docker-compose.yml`). Before writing code here, read `docs/microservices-showcase-design.md` for the full architecture and `docs/plan-1-foundation-account-service.md` for the first slice's exact task-by-task plan (file layout, dependency versions, commands, and code). Do not re-derive architecture decisions that are already settled in those two files.

## Documentation conventions

- **Design spec:** `docs/microservices-showcase-design.md` — the source of truth for architecture, service boundaries, and tech stack. Update it (not just the plans) if an implementation decision changes the actual architecture.
- **Implementation plans:** `docs/plan-N-<feature-name>.md` (e.g. `docs/plan-1-foundation-account-service.md`) — one per build increment, in execution order. This project uses this location/naming instead of the `docs/superpowers/plans/` default; keep new plans consistent with it.

## Local environment

- Java 21+ is a Global Constraint (see the plan docs) — required for virtual threads. This machine has multiple JDKs installed outside the usual `Program Files` location, and the default `java` on PATH is **not** the right one:
  - `C:\dev\openjdk-17.0.19` — JDK 17, below the floor. Do not build with this.
  - `C:\dev\openjdk-26.0.1` — JDK 26. Use this one; set `JAVA_HOME` to it before running Maven (`maven.compiler.release=21` cross-compiles fine from it).

## Git workflow

- Never commit directly to `master`. All work happens on a `feature/*` branch.
- Branch naming: `feature/plan-<N>-task-<M>-<short-description>` (e.g. `feature/plan-1-task-1-project-scaffolding`), matching the plan/task the branch implements.
- Merge to `master` only via a pull request — no direct pushes or merges to `master`.
- Every PR must be reviewed by a subagent before merging.
