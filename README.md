# Banking Microservices Showcase

[![CI](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml/badge.svg)](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml)

A money-transfer system built as six Spring Boot 3 / Java 21 services: a transfer saga over
synchronous HTTP with idempotent compensation, a transactional outbox to Kafka, a Redis-cached
FX rate service, Keycloak JWT auth, and OpenTelemetry tracing — plus a small browser UI.

- Architecture: [docs/microservices-showcase-design.md](docs/microservices-showcase-design.md)
- What each phase built: [docs/roadmap.md](docs/roadmap.md)
- Known gaps and non-goals: [docs/open-items.md](docs/open-items.md)

## Running locally

    cp .env.example .env        # first time only
    docker compose up --build

| What | URL |
|---|---|
| Bank UI | http://localhost:8090 |
| API Gateway | http://localhost:8080 |
| Swagger UI — Account / Transfer / Fraud / FX | http://localhost:{8081,8082,8084,8085}/swagger-ui.html |
| Notification Service | http://localhost:8083 (Kafka consumer, health only) |
| Keycloak | http://localhost:8180 |
| Grafana (anonymous) / Prometheus / Tempo | http://localhost:3001 / :9090 / :3200 |

Upgrading an older checkout: add any variable your `.env` is missing from `.env.example`, then
run `docker compose down -v` before `up`. Per-service databases are created by an init script
that only runs on an empty volume, and schemas evolve through Hibernate `ddl-auto: update`,
which cannot add a new `NOT NULL` column over existing rows. This discards local data.

Pass `GIT_SHA=$(git rev-parse --short HEAD)` to `docker compose up --build` to have
`/actuator/info` and the Service Versions dashboard show the commit instead of `unknown`.

## Authentication

Everything except `/actuator/{health,info,prometheus}` and the Swagger pages needs a bearer JWT
from Keycloak's `showcase` realm. Demo users, all with password `password`:

- `ada`, `bob` — `customer`: own accounts, send transfers
- `admin` — `account-admin`, `transfer-admin`, `fraud-admin`: list everything, edit the blocklist

The Bank UI signs in with Authorization Code + PKCE. For `curl`, use the password grant:

    token() {
      curl -s -X POST http://localhost:8180/realms/showcase/protocol/openid-connect/token \
        -d "grant_type=password&client_id=showcase-ui&username=$1&password=password" \
        | jq -r .access_token
    }
    export TOKEN=$(token ada) ADMIN_TOKEN=$(token admin)

For Swagger UI, paste a token into the **Authorize** button.

## API Gateway

`http://localhost:8080` routes only the client-facing paths: `/transfers/**`,
`POST /accounts`, `GET /accounts`, `/accounts/mine`, `/accounts/{id}`,
`/accounts/{id}/summary`, and `GET /fx/rates`. Account's `debit`/`credit` and all of
Fraud and Notification have no route — they are internal calls on the Docker network.
The Gateway and each service validate the token independently.

Each service's own port is still published for local dev, but that grants nothing extra:
`debit` only works on an account the caller owns (anyone else's 404s), and `credit` needs
`account-crediter`, which only Transfer Service's machine identity holds.

## Try it (curl)

Add `-H "Authorization: Bearer $TOKEN"` to each command unless it shows `$ADMIN_TOKEN`.

    # Create an account (currency: EUR, USD, GBP or PLN) and fetch it -- only its owner can
    curl -X POST http://localhost:8080/accounts -H "Content-Type: application/json" \
      -d '{"ownerName": "Ada Lovelace", "initialBalance": 100.00, "currency": "EUR"}'
    curl http://localhost:8080/accounts/<id>

    # Transfer -- Idempotency-Key is required; resending it returns the first result
    curl -X POST http://localhost:8080/transfers -H "Content-Type: application/json" \
      -H "Idempotency-Key: $(uuidgen)" \
      -d '{"fromAccountId": "<from>", "toAccountId": "<to>", "amount": 40.00}'
    curl http://localhost:8080/transfers/mine

    # Admin: list everything, filter by status
    curl http://localhost:8080/accounts -H "Authorization: Bearer $ADMIN_TOKEN"
    curl "http://localhost:8080/transfers?status=COMPENSATED" -H "Authorization: Bearer $ADMIN_TOKEN"

    # Admin: blocklist an account (Fraud directly, no Gateway route); DELETE lifts it.
    # A transfer FROM it fails cleanly; one TO it is debited, then refunded by the sweep.
    curl -X PUT http://localhost:8084/fraud/blocklist/<id> -H "Authorization: Bearer $ADMIN_TOKEN"

Errors are RFC 7807 `application/problem+json` with a stable `code` property, e.g.
`422 INSUFFICIENT_FUNDS`, `422 SOURCE_ACCOUNT_BLOCKED`, `409 IDEMPOTENCY_KEY_CONFLICT`,
`409 TRANSFER_IN_PROGRESS`, `503 ACCOUNT_SERVICE_UNAVAILABLE`.

## How a transfer works

Transfer Service runs a saga — check both accounts, screen the source with Fraud, debit,
screen the destination, credit — with each step committing on its own and every Account call
carrying a deterministic idempotency key (`<transferId>:debit`, `<transferId>:credit`). Calls
are wrapped in a Resilience4j circuit breaker and retry.

The outcomes:

- **`COMPLETED`** — money moved.
- **`FAILED`** — rejected before the debit (unknown account, blocked source, insufficient
  funds, no FX rate…). Nothing moved.
- **`COMPENSATION_REQUIRED`** — debited, but the credit failed or the destination is blocked.
  A background sweep (every 15s) replays the credit; if it is definitively refused, the source
  is credited back → **`COMPENSATED`**, or, if even that is refused, **`COMPENSATION_FAILED`**
  (manual review).
- **`PENDING`** — the debit's outcome is unknown (Account timed out). A timeout cannot be told
  apart from a request that never arrived, so nothing is guessed: the caller gets `503` with
  `transferStatus: PENDING`, and a stale-`PENDING` sweep (after 120s) replays the debit key to
  find out. Keep the same `Idempotency-Key` and poll `GET /transfers/{id}`.

A cross-currency transfer locks the ECB rate and credited amount on the transfer before any
money moves; every replay reuses them. FX Service caches rates in Redis (fresh 10 min,
last-known 24 h) and needs internet access on a cold cache.

Full detail — every failure path and why: §4 of the design doc,
`docs/phase-3-resilience-compensation-idempotency.md`, `docs/phase-12-fx-rates-redis-cache.md`.

## Events and notifications

Every settled transfer writes an outbox row in the same transaction; a poller publishes it to
Kafka (`transfer.completed` / `transfer.failed`). Notification Service stores one row per
transfer and logs it — watch with `docker compose logs -f notification-service`. Delivery is
at-least-once and redeliveries are deduplicated by `transferId`; unreadable events go to
`<topic>-dlt`. See `docs/phase-11-notification-persistence-error-handling.md`.

## Observability

Grafana (http://localhost:3001) ships four dashboards: JVM, business metrics (transfer
outcomes, outbox backlog, circuit breaker), Service Versions, and FX Cache. Every service
exports traces to Tempo via an OTel Collector — one transfer yields a single trace from the
Gateway through Transfer, Account, Fraud and the Kafka hop to Notification. Logs are JSON on
stdout carrying `traceId`/`spanId`. See `docs/phase-8-observability.md`.

## Tests

    ./mvnw test                           # unit + Testcontainers integration tests (Docker needed); what CI runs
    ./mvnw -Pe2e -pl e2e-tests verify     # end-to-end suite, local only

The end-to-end suite builds the images, starts its own copy of the stack (random project name
and ports, so it doesn't clash with a dev stack), and drives the saga through the Gateway:
happy path and replay, EUR → PLN, blocklisted source and destination, Account outage and
circuit breaker, and a lost debit response settled by the stale-`PENDING` sweep. Faults come
from a WireMock proxy between Transfer and Account. Expect 5–15 min. Two stacks need ~10 GB
for Docker, so `docker compose stop` the dev stack first and `start` it after.

`-De2e.keepStack=true` keeps the stack for its logs. A killed run can leave one behind:
`docker compose ls`, then `docker compose -p <name> down -v --rmi local`. More in
`docs/phase-10-end-to-end-saga-tests.md`.
