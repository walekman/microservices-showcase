# Investigation: `AccountControllerIT` concurrency-test CI flakiness

## TL;DR

Two of `AccountControllerIT`'s concurrency tests —
`concurrentDebitsWithTheSameIdempotencyKeyNeverDoubleApplyAndTheLoserGets500` and (suspected,
not directly observed) `returns409ForConcurrentUpdateConflict` — rely on N barrier-released
HTTP requests genuinely overlapping in the database. On GitHub Actions CI they intermittently
fail with **every** request returning `200 OK` and **zero** returning the expected error: the
race never happens, every request finds the prior one already committed and safely replays.
This has never once reproduced on an unmodified local run.

The confirmed mechanism: **the number of DB connections genuinely available at the moment the
burst fires directly caps how many requests can ever actually race.** A connection is only
released back to the pool after its holder's transaction commits, so if fewer connections are
available than concurrent requests, the surplus requests queue and each one only starts *after*
the previous holder has already committed — which is exactly the serialization these tests
exist to rule out. This was proven locally, reliably, by forcing HikariCP's pool down to a
single connection.

A fix based on this mechanism (force-warming HikariCP's pool to a size safely above
`concurrentRequests`, synchronously, before firing the burst) was implemented, validated
extensively locally, and merged as **PR #63**. It measurably reduces the flake but **does not
eliminate it** — a real CI run still failed with the identical signature after the fix was
applied, with the fix's own precondition assertions (pool reached full configured size) having
passed. This means available connections are a necessary but not sufficient condition: **at
least one more, still-unidentified factor can independently cause the same full-serialization
failure on CI.** This document exists so the next person (or the next investigation) doesn't
have to re-derive any of this from scratch.

## Background: what the tests are trying to prove

`AccountControllerIT` has two tests that fire `concurrentRequests` HTTP debit calls at the same
account, all released simultaneously via a `CyclicBarrier`, via a `TestRestTemplate`-backed
`ExecutorService`:

- **`returns409ForConcurrentUpdateConflict`** — each request uses its own idempotency key, so
  every one of them always attempts to write. The only way to avoid a race is if Hibernate's
  optimistic-lock version check on the `Account` row never collides, which requires at least two
  requests' reads to overlap before either commits.
- **`concurrentDebitsWithTheSameIdempotencyKeyNeverDoubleApplyAndTheLoserGets500`** — every
  request shares the *same* idempotency key. `AccountService.apply()` first checks
  `AccountOperationRepository.findById(idempotencyKey)`; if present, it treats the operation as
  already applied and returns `200 OK` **without attempting any write** (a safe idempotent
  replay). If absent, it mutates the account balance and `saveAndFlush`es a new
  `AccountOperation` row whose primary key *is* the idempotency key. Hibernate's default flush
  order sends inserts before updates, so the first transaction to commit "wins" the insert; every
  other transaction whose insert lands after that PK already exists gets a
  `DataIntegrityViolationException`, unmapped, → generic `500`. This proves, live against real
  Postgres, that a losing request fails loudly instead of silently double-applying — see
  `AccountService.apply()`'s own comment and Phase 3's final review (`docs/roadmap.md`) for why
  this was deliberately left un-caught rather than mapped to `409`.

Both tests are structurally the same shape: N transactions, real Postgres, a `CyclicBarrier` to
release them together, and an assertion that at least one of them experiences the intended
conflict. Both are therefore exposed to the same class of flakiness: **if the underlying
transactions never actually overlap, no conflict occurs, and every request just succeeds.**

## The CI failure, verbatim

On both a private (2 vCPU / 8GB) and a later public (4 vCPU / 16GB) GitHub Actions
`ubuntu-latest` runner, `concurrentDebitsWithTheSameIdempotencyKeyNeverDoubleApplyAndTheLoserGets500`
failed with:

```
Expecting ArrayList:
  [200 OK, 200 OK, 200 OK, 200 OK, 200 OK, 200 OK, 200 OK, 200 OK, 200 OK, 200 OK]
to contain:
  [500 INTERNAL_SERVER_ERROR]
but could not find the following element(s):
  [500 INTERNAL_SERVER_ERROR]
```

Every observed failure completed in well under a second (e.g. `Time elapsed: 0.155 s` for
N=10) — clean and fast, not a struggling/timing-out run. This detail matters: it rules out
"CI is just slow/overloaded" as a sufficient explanation on its own, since a slow environment
would more plausibly produce timeouts or visibly labored timing, not a fast, orderly, fully
serial outcome.

Observed failure rates (small samples, treat as indicative, not statistically rigorous):

| Repo state | Runner | `concurrentRequests` | Failures / attempts |
|---|---|---|---|
| Private | 2 vCPU / 8GB | 10 | 1 / 1 |
| Private | 2 vCPU / 8GB | 50 | 1 / 1 |
| Public | 4 vCPU / 16GB | 50 | 2 / 4 |
| Public (after PR #63's fix) | 4 vCPU / 16GB | 10 | 1 / 4 |

## Local reproduction: five failed attempts, then a breakthrough

The test has **never once failed on an unmodified local run** (60+ combined runs across many
variants below), which made this unusually hard to chase. In rough chronological order:

1. **`-XX:ActiveProcessorCount=1` / `=2`** — changes what `Runtime.availableProcessors()`
   reports (virtual-thread carrier pool size, `ForkJoinPool.commonPool()`, GC thread count) but
   is not an OS-level restriction; the OS can still freely schedule across all real cores.
   No reproduction.
2. **Swapping `TestRestTemplate`'s HTTP client** off Spring Boot's auto-detected
   `JdkClientHttpRequestFactory` (`java.net.http.HttpClient`, which multiplexes all connections
   through one shared selector thread) onto `SimpleClientHttpRequestFactory` (one blocking
   `HttpURLConnection` per call). No change.
3. **Independent per-thread jitter** (`Thread.sleep(random 0..N ms)` right after the barrier
   release), tested at 0/5/10/20/50 ms, 3 runs each. No reproduction at any magnitude.
4. **Real OS-level CPU affinity pinning** (Windows `start /affinity 3`, pinning the whole
   `mvnw`→JVM process tree to 2 logical CPUs — a genuine OS-enforced restriction, stronger than
   (1)). No reproduction. (Caveat: Postgres ran inside Docker Desktop's separate WSL2 VM here,
   unconstrained by this affinity mask — only the client/app side was restricted.)
5. **A maximally CI-like environment**: a dedicated Ubuntu 24.04 WSL2 VM, globally capped via
   `.wslconfig` (`processors=2`, `memory=8GB`, verified via `nproc` and
   `docker info --format '{{.NCPU}}'`), running its **own native `dockerd`** (avoiding Docker
   Desktop's separate engine and the Windows↔WSL2 network hop entirely), same JDK build as CI
   (OpenJDK 21.0.12), whole Maven build run natively inside that Linux VM — i.e. app process and
   Postgres container both plain Linux processes under one kernel on a genuinely 2-vCPU-limited
   machine. Ran 10 times. **No reproduction — 10/10 passed.**

At this point the working theory shifted from "CPU/scheduling is too slow or too jittery" to
something structurally different. An Opus-model brainstorming pass (asked for fresh ideas after
five failed attempts) surfaced the winning one:

6. **Shrink HikariCP's connection pool.** `-Dspring.datasource.hikari.maximum-pool-size=1`
   reliably reproduces the **exact** CI failure signature — all N requests `200 OK`, zero `500`,
   fast — on every run (6/6 confirmed independently). Pool sizes of 2 and 3 do not reproduce it;
   the race is caught fine. **This is the one reproduction technique that worked.**

### Why this makes sense

A JDBC transaction holds its pooled connection from `begin` to `commit`. With `pool size = 1`,
there is exactly one physical connection, so transactions are serialized *by construction* — no
test-side change can possibly make two transactions overlap under that setting. With a small
but >1 pool, the mechanism is: request 2 blocks waiting for a connection; that connection is
only freed once request 1's transaction commits; by the time request 2 acquires it and runs its
own `findById(idempotencyKey)` check, request 1 has already committed, so request 2 always sees
"already applied" and safely replays instead of racing.

### Why this doesn't happen locally with the default pool size (10)

Enabling `logging.level.com.zaxxer.hikari.pool.HikariPool=DEBUG` showed: HikariCP opens exactly
**one** connection eagerly at startup; the remaining connections up to `minimumIdle` (which
defaults to `maximumPoolSize`) are filled by a **single-threaded background "connection-adder"
executor**, one real TCP + Postgres-auth handshake at a time. Locally this fill takes roughly
440ms for 9 connections (~48ms each) and finishes several seconds before any test method
actually runs (Spring context startup alone takes a few seconds). On a loaded CI runner, each
handshake is plausibly much slower, and/or the pool simply never gets exercised to more than a
connection or two by the time this specific test method runs (the earlier test methods in the
class are all single-threaded and never create demand for more than 1 connection at a time, so
nothing forces the pool to grow past whatever its current floor is).

### Two important non-reproductions, and what they mean

Two further experiments — done specifically to *avoid* the flaw of pool-size overrides (see next
section) — also failed to reproduce the CI failure, and their negative results are informative,
not throwaway:

- **Network-wide latency** (`tc netem delay 300ms` on the Docker bridge) — confounded: it also
  slows Spring context startup's own DB round trips proportionally, giving the background pool
  filler *more* wall-clock time too, not less. Context startup went from ~2.6s to ~10.7s; the
  relative timing between "pool finishes filling" and "burst fires" never actually shifted.
- **Surgical connection-creation-only delay** — a `@TestConfiguration`-provided `DataSource`
  that wraps the real one in a `java.lang.reflect.Proxy`, sleeping only inside `getConnection()`
  calls (i.e. only when HikariCP is creating a *new* physical connection; already-pooled
  connections and their query round trips are untouched). Tested at 300ms and 2000ms per new
  connection. Context startup barely grew (confirming it mostly reuses one connection rather
  than needing many), and the race still reproduced correctly every time — **no failure even at
  2000ms per connection.**

This second result is a genuine, unresolved gap: it was designed as a clean, non-confounded test
of the "cold single-threaded filler" theory, and it did not reproduce the failure even at a
delay far larger than anything plausible on a real CI runner. So while pool=1 conclusively
proves *the mechanism* (available connections directly gate the race), it has **not** been
possible to locally reconstruct *the specific CI condition* that leaves the pool effectively too
small — the "slow cold fill" explanation remains a plausible, evidence-backed hypothesis (real
DEBUG log evidence, a real and directly relevant mechanism), not a fully proven one.

## Fix attempts

### Attempt 1 (PR #61, closed, not merged): deterministic proxy bean

A `@Primary` dynamic-proxy bean overriding the `AccountOperationRepository` Spring bean, gating
every `findById` call for the shared race key on a same-sized `CyclicBarrier`, so all N requests
were forced to read "not yet applied" before any of them could proceed to insert. This worked —
100% reliably, deterministically, on any hardware, because it didn't depend on real-world timing
at all.

**Rejected** by the project owner: it reaches into the Spring wiring of `AccountService` (the
bean actually under test) and couples the test to `apply()`'s internal call order — "you're
modifying the bean you're testing." A fair and correct objection; noted here so it isn't
reproposed without a materially different angle.

### Attempt 2 (PR #62, closed, superseded): widen `concurrentRequests`

Simply widened `concurrentRequests` from 10 to 50, on the same theory that fixed
`returns409ForConcurrentUpdateConflict`'s own historical flakiness (2 → 10 threads, documented
in that test's comment). **Confirmed ineffective by two separate real CI failures** (N=10 and
N=50, both showing the identical all-`200-OK` signature). In hindsight this was never going to
help: HikariCP's `maximum-pool-size` was never overridden from its default of 10, so at most 10
requests could ever hold a connection simultaneously regardless of how many total were fired —
the extra 40 requests in the N=50 case just queued behind the exact same bottleneck.

### Attempt 3 (PR #63, merged): force HikariCP pool warm-up

Two test-local changes to `AccountControllerIT`:

1. `@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=20",
   "spring.datasource.hikari.minimum-idle=20"})` — the test class owns its pool sizing outright,
   with headroom above `concurrentRequests=10` (a pool sized *exactly* to `concurrentRequests`
   was measured to still leave threads queued for a connection at burst time).
2. `warmUpConnectionPool()` — holds `maximumPoolSize` connections simultaneously (each
   `getConnection()` blocks until HikariCP has actually created it), forcing the pool's
   background best-effort fill to complete synchronously *before* the barrier-released burst,
   then releases them and asserts the pool actually reached its configured size.

This is a test-only change: `AccountService` and its Spring wiring are completely untouched;
only the `DataSource` (infrastructure underneath the code under test) is touched, so the race
remains genuine.

**Validated locally**: baseline (unmodified code) reliably reproduces the CI failure under a
forced `maximum-pool-size=1`; full `AccountControllerIT` class 17/17; full `account-service`
module suite 72/72.

**Important methodological caveat, discovered via user review before merging**: re-running the
`maximum-pool-size=1` repro command against the *fixed* code also "passes" — but this is
circular and proves nothing about the fix's real value. With literally one physical connection,
transactions are serialized by construction; no test-side change can produce a race under that
setting. The reported "pass" only happens because the fix's own `@SpringBootTest(properties=...)`
overrides the pool size back to 20, and Spring's property-source precedence puts that above a
command-line `-D` system property — so the effective pool was never actually 1 once the fix's
properties applied. **Do not use the `maximum-pool-size=1` command as a regression check against
fixed code; it only proves the mechanism against unmodified code.**

**Validated on real CI, and here's the honest result**: 4 runs of the same commit —
3 passed, 1 failed with the identical original signature (all 10 `200 OK`, 0 `500`,
`Time elapsed: 0.155s`). Critically, the failure occurred *after*
`warmUpConnectionPool()`'s own precondition assertion (`getTotalConnections() == poolSize`)
had already passed — meaning the pool genuinely had reached its full configured size of 20
connections before the burst fired, and the race still didn't happen.

**Conclusion: having enough available connections is necessary but not sufficient.** At least
one more, currently unidentified factor can independently cause the same full-serialization
outcome on CI, even with the connection-pool precondition fully satisfied. This fix measurably
improves the odds (going from what looked like a very high failure rate to roughly 1-in-4 in a
tiny sample) but does not eliminate the flake. It was merged anyway as a real, net-positive,
low-risk improvement — see "Recommendations" below for what's left.

## What's confirmed vs. still open

**Confirmed:**
- The number of genuinely available DB connections directly caps how many requests can race
  (proven via `maximum-pool-size=1`, independently, twice).
- HikariCP fills its pool via a single-threaded background executor, one connection at a time
  (proven via `HikariPool` DEBUG logs).
- Widening `concurrentRequests` alone does nothing while `maximum-pool-size` stays at its
  default of 10 (proven via two real CI failures, N=10 and N=50, identical signature).
- Forcing the pool to a verified-larger size before the burst measurably reduces, but does not
  eliminate, CI failures (proven via a real CI failure after the fix, with the fix's own
  precondition assertion having passed).

**Still open:**
- What is the *second* factor that can independently cause full serialization even with a
  verified-warm, adequately-sized connection pool? Candidates not yet fully investigated or
  ruled out:
  - Genuine request-dispatch/scheduling-level serialization (JDK `HttpClient`'s shared selector
    thread, Tomcat's single acceptor thread, virtual-thread carrier scheduling under real cloud
    hypervisor "noisy neighbor" CPU steal time) — explored for the *original* flake (attempts 1-5
    above) without success, but not re-tested specifically against the pool-warmed fixed code on
    a genuinely loaded/throttled environment.
  - Something specific to GitHub Actions' runner image (container storage driver, network
    driver, `cgroup` version) not replicable via a locally-built approximation.
- Whether `returns409ForConcurrentUpdateConflict` (which shares the same barrier-release
  pattern and was not directly observed failing in this investigation, only inferred from its
  own pre-existing comment about historical flakiness) is exposed to the identical risk. It
  received the same `warmUpConnectionPool()` treatment in PR #63 as a precaution, but was not
  independently CI-validated the way the idempotency-key test was.

## Recommendations for whoever picks this up next

1. **Instrument instead of guessing further.** Local reproduction of the *exact* CI condition
   has now failed seven different ways (five before the pool discovery, two more after it). CI
   itself reproduces this reliably and cheaply (~1-4 minutes per run via `gh run rerun`). Add
   lightweight per-request timestamp logging (thread entry, connection-acquired, post-`findById`,
   commit) directly to a CI run and read the actual arrival pattern, rather than continuing to
   construct local proxies for an environment that has resisted five different faithful
   approximations.
2. **If a fully deterministic guarantee is required**, the only mechanism found that actually
   achieves it is Attempt 1's synchronization proxy (PR #61) — revisit it if the "reaches into
   the SUT's wiring" objection can be addressed differently (e.g., synchronizing at a layer that
   is more clearly test infrastructure, such as the HTTP client or the DataSource, rather than a
   Spring bean actually injected into `AccountService`).
3. **Consider whether this class of test belongs at the HTTP-integration level at all.** Every
   layer between the test's `CyclicBarrier.await()` and the actual database transaction (HTTP
   client, Tomcat, Spring MVC dispatch, virtual thread scheduling) is a potential place for
   "concurrent by wall-clock" to fail to mean "concurrent in the database," as this investigation
   demonstrated repeatedly. A test that drove `AccountService.debit(...)` directly with real
   JVM threads (still against real Testcontainers Postgres, still no mocking) would remove
   several of those layers at once without needing to modify `AccountService`'s own wiring, since
   the synchronization would be applied by the test's own thread orchestration around a plain
   method call, not by intercepting anything inside the class under test.
4. **Audit the rest of the repo for the same latent pattern.** Any barrier-released concurrency
   test relying on the ambient connection pool size has the same exposure. A grep for
   `CyclicBarrier` across `transfer-service` and the other modules was not done as part of this
   investigation.
