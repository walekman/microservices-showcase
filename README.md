# Banking Microservices Showcase

[![CI](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml/badge.svg)](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml)

Design: [docs/microservices-showcase-design.md](docs/microservices-showcase-design.md)

## Running locally

First time only:

    cp .env.example .env

If you ran an earlier version of this stack, destroy the Postgres volume first — the
second database is created by an init script that only runs on an empty data directory
(this discards any locally created accounts). This also matters if you last ran the stack
before Phase 7b (`docs/phase-7b-account-ownership-authorization.md`): Hibernate can't add
the new `ownerId`/`initiatorId` columns as `NOT NULL` over existing rows, so accounts/transfers
created before that phase are left with a null value there and simply become permanently
inaccessible (a clean 404, not an error, but confusing if you don't know why):

    docker compose down -v

Then:

    docker compose up --build

This starts Postgres, Account Service (8081), Transfer Service (8082), Fraud Service (8084), Kafka, Notification Service (8083), the API Gateway (8080), Keycloak (8180), and the observability stack: OTel Collector, Grafana Tempo (3200), Prometheus (9090), and Grafana (3001).

## Authentication (Keycloak)

Every endpoint except `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, and Swagger's own pages now needs a bearer JWT
(see `docs/phase-7-auth-keycloak-jwt.md`). Keycloak comes up pre-configured with a `showcase`
realm — two demo users, `ada` and `bob` (password `password` for both), each with the
`customer` role, and a third, `admin` (password `password`), holding `account-admin` and
`transfer-admin` instead — the two roles that gate the list-all endpoints (`GET /accounts`,
`GET /transfers`; see `docs/phase-7b-account-ownership-authorization.md`). Swap `username=ada`
for `username=admin` in the token request below to exercise those.

Get a token (password grant — fine for this demo, since there's no login UI yet):

    curl -X POST http://localhost:8180/realms/showcase/protocol/openid-connect/token \
      -H "Content-Type: application/x-www-form-urlencoded" \
      -d "grant_type=password&client_id=showcase-ui&username=ada&password=password" \
      | jq -r .access_token

Export it once and append `-H "Authorization: Bearer $TOKEN"` to every request below:

    export TOKEN=$(curl -s -X POST http://localhost:8180/realms/showcase/protocol/openid-connect/token \
      -H "Content-Type: application/x-www-form-urlencoded" \
      -d "grant_type=password&client_id=showcase-ui&username=ada&password=password" \
      | jq -r .access_token)

The two admin-only examples further down (`GET /accounts`, `GET /transfers`) need a separate
token for the `admin` user instead:

    export ADMIN_TOKEN=$(curl -s -X POST http://localhost:8180/realms/showcase/protocol/openid-connect/token \
      -H "Content-Type: application/x-www-form-urlencoded" \
      -d "grant_type=password&client_id=showcase-ui&username=admin&password=password" \
      | jq -r .access_token)

## Try it (Swagger UI)

All three APIs are browsable and callable straight from a browser. Each has an **Authorize**
button (top right) — paste in a token obtained as above to make "Try it out" calls succeed:

- Account Service — http://localhost:8081/swagger-ui.html
- Transfer Service — http://localhost:8082/swagger-ui.html
- Fraud Service — http://localhost:8084/swagger-ui.html

## API Gateway

A single entry point at `http://localhost:8080` routes to the two client-facing services:

- `/transfers/**` → Transfer Service (full API)
- `POST /accounts`, `GET /accounts`, `GET /accounts/{id}` → Account Service

Account's `/accounts/{id}/debit` and `/accounts/{id}/credit` are intentionally **not** routed
— they're internal saga calls Transfer Service makes directly on the Docker network. That keeps
them unreachable *through the Gateway*, but **not unreachable outright**: Account's own port
(8081) is published for local dev, and any `customer` token can still call `credit` directly,
since a real transfer's credit call is made with the *source* customer's own token, never the
destination owner's — there is no ownership check that could distinguish the two (see
`docs/phase-7b-account-ownership-authorization.md`'s Design Decisions). `debit` no longer has
this gap: as of Phase 7b it's owner-gated, so a `customer` token can only debit an account it
created — calling it directly against someone else's account now 404s, the same answer a
genuinely missing account would give. Every other example in this README still targets each
service's own port directly (8081/8082/8083/8084); the Gateway doesn't replace those, it adds a
second, narrower way in. Every routed path requires a bearer JWT with the matching permission,
same as calling each service directly (see "Authentication" above) — the Gateway and the service
behind it each independently check the token.

## Observability

Grafana at http://localhost:3001 (no login needed — anonymous viewer access) has three dashboards
provisioned on startup: a JVM/Micrometer dashboard, a business-metrics dashboard (transfer
completed/failed/fraud-rejected counters, outbox backlog, circuit-breaker state), and a Service Versions dashboard. The Service Versions dashboard shows which version and commit each service is running, from the `application_info` metric every service publishes; the same facts are at `/actuator/info` on each service's port, no token needed. Pass the commit when building — `GIT_SHA=$(git rev-parse --short HEAD) docker compose up -d --build` — or it shows as `unknown`. Prometheus
(http://localhost:9090) scrapes `/actuator/prometheus` on all five services every 10s — check
its Targets page if a Grafana panel shows "No data." Every service also exports traces via
OTLP through an OTel Collector to Grafana Tempo; trigger any transfer below, then open Grafana
Explore against the Tempo data source to see a single trace spanning Gateway → Transfer →
Account → Fraud → the Kafka hop → Notification — the concrete proof this system's sync and
async communication share one trace. Logs are structured JSON on every service's stdout
(`docker compose logs <service>`), each line carrying the `traceId`/`spanId` of the request
that produced it, so a trace in Tempo and its log lines can be cross-referenced directly.

## Try it (curl)

Every command below needs `-H "Authorization: Bearer $TOKEN"` added (see "Authentication" above) —
omitted here to keep the examples focused on each endpoint's own request shape.

    # Create an account
    curl -X POST http://localhost:8081/accounts \
      -H "Content-Type: application/json" \
      -d '{"ownerName": "Ada Lovelace", "initialBalance": 100.00}'

    # Fetch it (replace <id> with the id from the response above) -- only the account's owner
    # can do this; using a token other than the one that created it 404s, same as a genuinely
    # missing id (see docs/phase-7b-account-ownership-authorization.md)
    curl http://localhost:8081/accounts/<id>

    # List all accounts -- needs the admin token (username=admin), not ada's/bob's
    curl http://localhost:8081/accounts -H "Authorization: Bearer $ADMIN_TOKEN"

    # Debit it (Idempotency-Key is required -- a retry with the same key is a no-op, not a
    # second debit. The key must be unique per operation, not just per account: it is the
    # sole primary key in Account's idempotency ledger, so copy-pasting a literal example
    # key like "demo-debit-1" against a second account returns 409
    # IDEMPOTENCY_KEY_CONFLICT instead of debiting it -- give each account its own key, e.g.
    # by working the account id into it as below)
    curl -X POST http://localhost:8081/accounts/<id>/debit \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: demo-debit-<id>" \
      -d '{"amount": 40.00}'

    # Credit it
    curl -X POST http://localhost:8081/accounts/<id>/credit \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: demo-credit-<id>" \
      -d '{"amount": 15.00}'

## Transfers

A transfer is a saga: Transfer Service checks both accounts, debits the source, then
credits the destination, recording the outcome in its own database at each step. There
is no distributed transaction — each step commits independently, which is why the
failure states below exist.

### All saga outcomes

Live request (`TransferService.execute()`):

| # | Path | Outcome |
|---|---|---|
| 1 | source exists → destination exists → source not blocked → debit succeeds → destination not blocked → credit succeeds | `COMPLETED` |
| 2 | source or destination doesn't exist | `FAILED` (`ACCOUNT_NOT_FOUND`) |
| 3 | Account Service unreachable during existence pre-check | `FAILED` (`ACCOUNT_SERVICE_UNAVAILABLE`) |
| 4 | source account blocklisted | `FAILED` (`SOURCE_ACCOUNT_BLOCKED`) |
| 5 | Fraud Service unreachable checking the source | `FAILED` (`SOURCE_FRAUD_SERVICE_UNAVAILABLE`) |
| 6 | debit rejected (e.g. `INSUFFICIENT_FUNDS`) | `FAILED` |
| 7 | debit call unreachable, retries/circuit breaker exhausted | `FAILED` (`ACCOUNT_SERVICE_UNAVAILABLE`) — terminal, not reconciled |
| 8 | destination account blocklisted | `COMPENSATION_REQUIRED` (`DESTINATION_ACCOUNT_BLOCKED`) → scheduler compensates |
| 9 | Fraud Service unreachable checking the destination | `COMPENSATION_REQUIRED` (`DESTINATION_FRAUD_SERVICE_UNAVAILABLE`) → scheduler retries |
| 10 | credit rejected | `COMPENSATION_REQUIRED` → scheduler compensates |
| 11 | credit call unreachable | `COMPENSATION_REQUIRED` → scheduler resolves |
| 12 | unexpected exception before debit | `FAILED` (`UNEXPECTED_ERROR`) |
| 13 | unexpected exception after debit | `COMPENSATION_REQUIRED` (`UNEXPECTED_ERROR`) |
| 14 | process crashes mid-request | stays `PENDING` → scheduler resolves |

Background scheduler resolution:

- **stale `PENDING`** → re-check source fraud: blocked → `FAILED`; unreachable → stays `PENDING`, retried next sweep; clear → (idempotent) debit attempt exactly as today (lands → promoted to `COMPENSATION_REQUIRED`; rejected → `FAILED`; unreachable → stays `PENDING`)
- **`COMPENSATION_REQUIRED`** → re-check destination fraud, unconditionally: blocked → compensate the source (as today's rejected-credit path); unreachable → stays `COMPENSATION_REQUIRED`, retried next sweep; clear → (idempotent) credit attempt exactly as today (lands → `COMPLETED`; rejected → compensate; unreachable → stays `COMPENSATION_REQUIRED`)

    # Transfer money (replace the ids with two accounts you created)
    curl -X POST http://localhost:8082/transfers \
      -H "Content-Type: application/json" \
      -d '{"fromAccountId": "<from>", "toAccountId": "<to>", "amount": 40.00}'

    # Fetch one transfer -- the initiator or the destination account's owner (see
    # docs/phase-7b-account-ownership-authorization.md)
    curl http://localhost:8082/transfers/<id>

    # List transfers, optionally by status -- needs the admin token (username=admin), not
    # ada's/bob's
    curl http://localhost:8082/transfers -H "Authorization: Bearer $ADMIN_TOKEN"
    curl "http://localhost:8082/transfers?status=COMPENSATION_REQUIRED" -H "Authorization: Bearer $ADMIN_TOKEN"

    # Trigger a blocked-source rejection (clean failure, no money moves): set
    # FRAUD_BLOCKLIST_ACCOUNT_IDS in .env to include an account id, restart fraud-service,
    # then transfer FROM that account.
    #
    # Trigger a blocked-destination compensation (money moves, then reverses automatically):
    # transfer TO a blocklisted account instead.
    curl "http://localhost:8082/transfers?status=FAILED" -H "Authorization: Bearer $ADMIN_TOKEN"

Errors are RFC 7807 problem documents with a stable `code`:

    # Insufficient funds                  -> 422 INSUFFICIENT_FUNDS, no money moves
    # Unknown account                     -> 422 ACCOUNT_NOT_FOUND, no money moves
    # Source account blocklisted          -> 422 SOURCE_ACCOUNT_BLOCKED, no money moves
    # Destination account blocklisted     -> transfer recorded COMPENSATION_REQUIRED, source
    #                                         is automatically credited back within
    #                                         transfer.compensation.sweep-interval
    # Missing, blank, or overlong          -> 400 VALIDATION_FAILED (debit/credit only)
    #   Idempotency-Key header
    # Idempotency key reused with         -> 409 IDEMPOTENCY_KEY_CONFLICT (should never
    #   different parameters                 happen in normal operation)
    # Account down during pre-validation  -> after Retry/CircuitBreaker exhaust their
    #                                         attempts, 503 ACCOUNT_SERVICE_UNAVAILABLE,
    #                                         no money moves
    # Account down during the debit       -> after Retry/CircuitBreaker exhaust their
    #                                         attempts, 503 ACCOUNT_SERVICE_UNAVAILABLE,
    #                                         outcome UNKNOWN: recorded FAILED, but the
    #                                         debit may have committed -- this is a terminal
    #                                         state, not PENDING, so it is NOT reconciled by
    #                                         the stale-PENDING sweep below (see the note
    #                                         at the end of this section)

### Automatic compensation and idempotency

Transfer Service wraps every call into Account Service in a CircuitBreaker + Retry
(Resilience4j), and every debit/credit carries a deterministic `Idempotency-Key`
(`"{transferId}:debit"`, `"{transferId}:credit"`) so a retry can never double-move money.

If the debit succeeds and the credit then fails, the money is momentarily stranded at the
source and recorded `COMPENSATION_REQUIRED`. A background sweep (`transfer.compensation.sweep-interval`,
default 15s) resolves it automatically, by replaying the ambiguous call rather than guessing:

- If the destination credit had actually already landed (a lost response, not a lost
  request) — the transfer is marked `COMPLETED`. Nothing to reverse.
- If the destination definitively rejects it, the source is credited back and the transfer
  is marked `COMPENSATED`.
- If crediting the source back also definitively fails, the transfer is marked
  `COMPENSATION_FAILED` — a manual-review terminal state, logged at ERROR.

A separate sweep (`transfer.compensation.pending-stale-after`, default 120s) recovers
transfers stuck at `PENDING` — e.g. the process crashed mid-saga — the same way, starting
from the debit leg.

    curl "http://localhost:8082/transfers?status=COMPENSATION_REQUIRED" -H "Authorization: Bearer $ADMIN_TOKEN"
    curl "http://localhost:8082/transfers?status=COMPENSATED" -H "Authorization: Bearer $ADMIN_TOKEN"
    curl "http://localhost:8082/transfers?status=COMPENSATION_FAILED" -H "Authorization: Bearer $ADMIN_TOKEN"

Known gap, narrower than Phase 2's: a transfer recorded `FAILED` with
`ACCOUNT_SERVICE_UNAVAILABLE` on the debit leg still needs reconciliation — the debit may
have committed despite the 503. Retry now resolves most of these on its own (a retry
replays the same idempotency key, so a merely-lost response gets confirmed within the live
saga itself); this only remains open for the rarer case where every retry attempt, not just
the first, fails to get back a definitive answer. Either way, this state is terminal
(`FAILED`, not `PENDING`), so it is not touched by either sweep above. The same gap exists
for a stale-`PENDING` row recovered as `FAILED` because the source account came back
blocklisted: that recovery never establishes whether the debit itself had already landed
before the crash either, for the same underlying reason — no operation-lookup endpoint
exists on Account Service to check.

## Kafka and Notification Service

Transfer outcomes (completed or failed) are published to Kafka topics (`transfer.completed` and
`transfer.failed`) via an outbox table and polling publisher in Transfer Service.

The Notification Service (port 8083) is a stateless consumer that listens to both topics and
logs transfer notifications. To watch notifications as they arrive:

    docker compose logs -f notification-service

Look for lines like:
- `Notification sent: transfer completed {...}` for successful transfers
- `Notification sent: transfer failed {...}` for failed transfers

Delivery is at-least-once: if the publisher crashes after Kafka acknowledges a message but
before the outbox row is marked published, the same event is republished on the next poll. The
Notification Service just logs, so a duplicate log line is the entire blast radius — this is an
accepted characteristic of the outbox pattern, not a bug.

The Notification Service health endpoint is available at:

    curl http://localhost:8083/actuator/health
