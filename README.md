# Banking Microservices Showcase

[![CI](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml/badge.svg)](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml)

Design: [docs/microservices-showcase-design.md](docs/microservices-showcase-design.md)

## Running locally

First time only:

    cp .env.example .env

Then:

    docker compose up --build

This starts Postgres, Account Service (8081) and Transfer Service (8082).
More services land in later plans.

## Try it (Swagger UI)

Both APIs are browsable and callable straight from a browser:

- Account Service — http://localhost:8081/swagger-ui.html
- Transfer Service — http://localhost:8082/swagger-ui.html

## Try it (curl)

    # Create an account
    curl -X POST http://localhost:8081/accounts \
      -H "Content-Type: application/json" \
      -d '{"ownerName": "Ada Lovelace", "initialBalance": 100.00}'

    # Fetch it (replace <id> with the id from the response above)
    curl http://localhost:8081/accounts/<id>

    # List all accounts
    curl http://localhost:8081/accounts

    # Debit it
    curl -X POST http://localhost:8081/accounts/<id>/debit \
      -H "Content-Type: application/json" \
      -d '{"amount": 40.00}'

    # Credit it
    curl -X POST http://localhost:8081/accounts/<id>/credit \
      -H "Content-Type: application/json" \
      -d '{"amount": 15.00}'

## Transfers

A transfer is a saga: Transfer Service checks both accounts, debits the source, then
credits the destination, recording the outcome in its own database at each step. There
is no distributed transaction — each step commits independently, which is why the
failure states below exist.

    # Transfer money (replace the ids with two accounts you created)
    curl -X POST http://localhost:8082/transfers \
      -H "Content-Type: application/json" \
      -d '{"fromAccountId": "<from>", "toAccountId": "<to>", "amount": 40.00}'

    # Fetch one transfer
    curl http://localhost:8082/transfers/<id>

    # List transfers, optionally by status
    curl http://localhost:8082/transfers
    curl "http://localhost:8082/transfers?status=COMPENSATION_REQUIRED"

Errors are RFC 7807 problem documents with a stable `code`:

    # Insufficient funds -> 422 INSUFFICIENT_FUNDS, no money moves
    # Unknown account    -> 422 ACCOUNT_NOT_FOUND, no money moves
    # Account down       -> 503 ACCOUNT_SERVICE_UNAVAILABLE, no money moves

### Known gap: COMPENSATION_REQUIRED

If the debit succeeds and the credit then fails, the money is stranded at the source.
This release records that as `COMPENSATION_REQUIRED`, logs it at ERROR, and makes it
listable — but does not fix it. Compensation (crediting the source back) is Plan 3.
The gap is deliberate: it makes visible exactly why saga compensation exists.
