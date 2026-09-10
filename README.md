# Banking Microservices Showcase

[![CI](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml/badge.svg)](https://github.com/walekman/microservices-showcase/actions/workflows/ci.yml)

Design: [docs/microservices-showcase-design.md](docs/microservices-showcase-design.md)

## Running locally

First time only:

    cp .env.example .env

Then:

    docker compose up --build

This starts Postgres and Account Service. More services land in later plans.

## Try it (Swagger UI)

Account Service's API is browsable and callable straight from a browser at
http://localhost:8081/swagger-ui.html — no separate client needed.

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
