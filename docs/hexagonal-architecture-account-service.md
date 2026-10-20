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

**Status:** Exploratory. It lives on `feature/hexagonal-architecture`, and merging it into
`master` is not decided. Until then it has no phase number or roadmap row, and this branch is
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
   `AccountStore.save(Account)` maps the domain object to a detached entity that carries `id`
   and `version`, and calls `saveAndFlush`. The merge compares versions. Flushing inside the
   adapter makes a stale version fail there rather than at commit. The adapter catches
   `ObjectOptimisticLockingFailureException` and throws the domain
   `AccountConcurrentlyModifiedException`. The web adapter maps that exception to the same
   `409 CONCURRENT_MODIFICATION` as before.
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

- `domain` depends only on `java..`.
- `application` depends on neither `org.springframework..`, `jakarta.persistence..` nor `..adapter..`.
- `adapter.in` and `adapter.out` do not depend on each other.
- Only `adapter.out.persistence` touches `jakarta.persistence` or Spring Data.

`com.tngtech.archunit:archunit-junit5` is pinned in the root `pom.xml`, because Boot's BOM
does not manage it.

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

1. Domain, ports and the application service, plus ArchUnit.
2. The persistence adapter (JPA entities, mapping, translations, `TransactionRunner`) and its
   tests.
3. Move the web adapter, add the exception mapping and the wiring config, delete the old
   `service/` package and the old repositories from `domain/`, and enable every ArchUnit rule.
4. Docs: update the design doc's Account Service section, and finish this document.

The final whole-branch review runs on a strong model, because the money and locking paths are
high-stakes.

## Verification

- `./mvnw -pl account-service test`, then the full `./mvnw test`.
- No `springframework` or `jakarta.persistence` imports under `domain/` or `application/`.
- Live: rebuild `account-service` in Compose. Then, through the UI, create an account and make a
  same-currency and a cross-currency transfer.
- E2E: `./mvnw -Pe2e -pl e2e-tests verify` with the dev stack stopped. It covers the idempotent
  repeat, the lost-debit replay by the stale-PENDING sweep, and compensation.
