# Account Service in Hexagonal Architecture

**Goal:** Restructure `account-service` into strict hexagonal architecture (ports and
adapters), so it becomes the project's reference example of a framework-free domain core. It
was chosen over the other services because it has real domain invariants (balance rules, the
idempotency-ledger conflict rule, ownership, currency), and because the infrastructure
currently leaks straight into them:

- `Account` and `AccountOperation` are JPA `@Entity`s (`@Version`, `Persistable`).
- The Spring Data repositories live in `domain/`.
- `AccountService` catches `DataIntegrityViolationException` and depends on `saveAndFlush` timing.
- `ApiExceptionHandler` maps Spring's `ObjectOptimisticLockingFailureException`.

**Status:** Built (tasks 1–3, PRs #135, #136 and the docs PR). It lives on
`feature/hexagonal-architecture`, and merging it into `master` is not decided. Until then it has no phase number or roadmap row, and this branch is
the only place its doc changes appear.

**Spec:** This document, agreed with the user on 2026-10-20.

## Global Constraints

**Zero external behaviour change.** The refactor must keep all of the following:

- The HTTP API, and every RFC 7807 status and `code`.
- The database schema: the same table and column names. There is no Flyway; this relies on
  `ddl-auto: update`.
- Idempotent-replay semantics, which Transfer's saga invariant depends on (`<transferId>:debit`
  replays from `CompensationScheduler`).
- Optimistic locking (`409 CONCURRENT_MODIFICATION`).

The existing integration tests (`AccountControllerIT`, `AccountSecurityIT`, `OpenApiDocsIT`,
`TracingBridgeIT`, `BuildInfoIT`) are the behaviour-preservation net. Only their imports may
change, never their assertions.

## Package Layout (`com.showcase.account`)

```
domain/                   Account, AccountOperation, AccountOperationType, SupportedCurrency,
                          all *Exception. Pure Java: no Spring, no jakarta.persistence.
application/port/in/      CreateAccountUseCase, AccountOperationUseCase (debit/credit), AccountQueries
application/port/out/     AccountStore, AccountOperationLedger, TransactionRunner
application/              AccountApplicationService: implements the in-ports, plain class
adapter/in/web/           AccountController, request/response DTOs, ApiExceptionHandler, Problems
adapter/out/persistence/  JPA entities, Spring Data repositories, mapping, port implementations,
                          SpringTransactionRunner
config/                   security/JWT/metrics config, and AccountApplicationConfig, which wires
                          the application service as a bean
```

## Design Decisions

1. **The domain model is framework-free.** `Account` keeps its creation constructor
   (validation as today). It gains a `restore(...)` factory that the persistence mapper uses to
   rebuild it from stored state, including `version` as a plain `long`. The `debit` and
   `credit` logic is unchanged.
2. **Legacy-null quirks are persistence concerns.** A pre-Phase-12 row with a null `currency`
   reads as `EUR`. That fallback now lives in the mapper, not in `Account.getCurrency()`. A
   pre-Phase-7b null `ownerId` stays handled by `Objects.equals` in the application service.
3. **Transactions without Spring in the application layer.** The out-port `TransactionRunner`
   (`inTransaction`, `readOnly`) is implemented by `SpringTransactionRunner` over
   `TransactionTemplate`. A `RuntimeException` still rolls back. A ledger-insert race therefore
   still rolls the balance change back with it.
4. **Optimistic locking is preserved, and translated in the adapter.**
   `AccountStore.save(Account)` loads the managed entity. Inside the caller's transaction that
   is the instance its earlier read loaded, so it carries the version that read saw. If the
   domain copy's `version` differs, it rejects the copy as stale. Otherwise it copies the
   balance onto the entity and calls `saveAndFlush`. Flushing inside the adapter makes a lost
   `WHERE version = ?` race fail there rather than at commit. The adapter catches
   `OptimisticLockingFailureException` and throws the domain
   `AccountConcurrentlyModifiedException`. The web adapter maps that exception to the same
   `409 CONCURRENT_MODIFICATION` as before.
4a. **The ledger entry is written before the balance, and the order is load-bearing.** It was
   found while building this. Before the refactor, a single flush wrote both rows, and
   Hibernate's insert-before-update ordering put the ledger INSERT first. Now each port writes
   immediately, so `AccountApplicationService.apply()` states the order itself:
   `ledger.record(...)` first, then `accountStore.save(...)`. This decides how a same-key race
   loses:
   - **Ledger first (correct):** the loser blocks on the winner's uncommitted ledger row and
     fails with a 500. Transfer treats a 500 as an unknown outcome and reconciles by replaying
     the key.
   - **Balance first (wrong):** the loser blocks on the account row and fails with a 409.
     Transfer reads a 409 as a definite rejection, yet the winner's debit, under the same key,
     did land. That breaks the saga invariant in CLAUDE.md.

   `AccountControllerIT`'s same-key race test pins this. With the order swapped, it fails with
   `[200, 409]`; this was checked by mutation.
5. **Uniqueness races are translated in the adapter.**
   - `AccountStore.insert(Account)` returns the saved account (its id is still
     `@GeneratedValue`), and turns `DataIntegrityViolationException` into
     `AccountAlreadyExistsException`.
   - `AccountOperationLedger.record(...)` keeps `Persistable.isNew() == true` on its JPA entity.
     On a duplicate key it throws the domain `ConcurrentOperationException`. That maps to the
     same generic 500 the race produces today, so the race stays deliberately unhandled, as
     Phase 3 decided.
6. **Ports are named for the domain, not for CRUD.**
   - `AccountStore`: `findById`, `findAllByOwnerId`, `findAll`, `existsByOwnerId`, `insert`, `save`.
   - `AccountOperationLedger`: `find(idempotencyKey)`, `record(operation)`.
7. **Comments travel with the code.** Each existing rationale comment (saveAndFlush,
   `Persistable`, the replay branch, the ownership exemptions) moves to wherever its logic lands.

## Enforcement: ArchUnit

`HexagonalArchitectureTest` enforces these rules:

- `domain` depends only on `java..`, plus Lombok's `@Generated` marker on the getters it
  writes at compile time.
- `application` depends only on `java..`, `domain` and itself.
- Adapters reach the core only through `application.port..`, never through
  `AccountApplicationService` itself. `config.AccountApplicationConfig` is the one place that
  wires the two together.
- `adapter.in` and `adapter.out` do not depend on each other.
- Only `adapter.out.persistence` touches `jakarta.persistence`, Hibernate, Spring Data, Spring
  ORM/DAO exceptions or Spring transactions.
- Every type in `application.port..` is an interface.

The rules check production code only. Tests may cross layers: `AccountSecurityIT` mocks the
application service. Each rule was checked by mutation: a Spring type referenced from the
domain, and the controller referencing the service class, made three rules fail.

`archunit.version` is pinned in the root `pom.xml`, because Boot's BOM does not manage it. It
is used by `account-service/pom.xml`.

## Testing

- `AccountTest` and `AccountOperationTest` become pure domain unit tests. Their assertions are
  unchanged.
- `AccountServiceTest` becomes `AccountApplicationServiceTest`. It runs against in-memory fakes
  of the out-ports, which proves the core runs without Spring.
- The repository tests become `@DataJpaTest` adapter tests. They add cases for: a stale-version
  save, a duplicate owner, and a null-currency row read as EUR.
- The integration tests are unchanged apart from imports.

## Delivery

Each task branches off `feature/hexagonal-architecture` as
`feature/hexagonal-architecture-task-<M>-<desc>`, and gets its own PR back into it.

1. Domain, ports, application service, persistence adapter, wiring config, and the domain
   exception mapping in the existing controller. These ship as one task because a framework-free
   `Account` cannot compile without the JPA entity that replaces it.
2. Move `api/` to `adapter/in/web/`, and add ArchUnit with every rule enabled.
3. Docs: update the design doc's Account Service section, and finish this document.

The final whole-branch review runs on a strong model, because the money and locking paths are
high-stakes.

## Verification

- `./mvnw -pl account-service test`, then the full `./mvnw test`.
- No `springframework` or `jakarta.persistence` imports under `domain/` or `application/`.
- Live: rebuild `account-service` in Compose. Then, through the UI, create an account and make a
  same-currency and a cross-currency transfer.
- E2E: `./mvnw -Pe2e -pl e2e-tests verify` with the dev stack stopped. It covers the idempotent
  repeat, the lost-debit replay by the stale-PENDING sweep, and compensation.

## Implementation Notes (as built)

- **Differences from this spec as first written.** Decision 4a, the explicit
  ledger-before-balance order, was found during task 1. The original split of tasks 1 and 2
  could not compile, so they were merged (see Delivery). The ArchUnit domain rule allows Lombok's
  `@Generated` marker.
- **Behaviour preservation.** No integration-test assertion changed; the edits were imports and
  comments only. The database schema is unchanged, because the entities keep the same table and
  column names. The only test moved between layers is the legacy null-currency → EUR case, from
  `AccountTest` to `AccountPersistenceAdapterTest`, because that fallback now lives in the
  mapping.
- **Verification run on 2026-10-20:**
  - **`./mvnw -B test`, full root suite:** green. account-service ran 106 tests; 445 in total
    across all modules.
  - **Mutation checks:**
    - Swapping the ledger/balance write order makes the same-key race IT fail with `[200, 409]`.
    - A Spring type referenced from `domain`, plus the controller referencing
      `AccountApplicationService`, makes three ArchUnit rules fail.
  - **`./mvnw -Pe2e -pl e2e-tests verify`:** 6/6 scenarios pass against images built from this
    branch: happy path with an idempotent repeat, cross-currency, blocked source, blocked
    destination compensated, Account unreachable (retry, breaker, recovery), and the lost debit
    settled once by the stale-PENDING sweep.
    - The first attempt never reached the tests. The stack's Postgres went unhealthy while
      every container started at once on a freshly started Docker Desktop (a ~7.4 GB VM).
      Started alone with the same config, Postgres was healthy within seconds, and the rerun
      passed. Environmental, not code.
  - **Not done:** a manual click-through on the dev stack through the UI. The E2E suite drives
    the same flows through the Gateway against a full stack.
