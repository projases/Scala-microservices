# Scala Microservices

Microservices project for the UOC Software Architecture course. Built with Scala 3,
Cats Effect, http4s, tapir, and Doobie, following a tagless-final / functional architecture.

## Services

| Service          | Port  | Description                                                |
|------------------|-------|------------------------------------------------------------|
| `course`         | 18084 | Course lifecycle, enrollments, status                       |
| `user`           | 18082 | Users, alerts, product validation                           |
| `microcredential`| 18085 | Microcredential requests                                    |
| `productcatalog` | 18081 | Stub product catalog; publishes `product.unit_available`    |
| `notification`   | 18083 | RabbitMQ consumer turning domain events into (logged) emails |

## Requirements

- sbt (Scala 3.3.8)
- Docker & docker compose
- Postgres, RabbitMQ (via `docker compose up`)

## Running

```bash
docker compose up --build -d
```

HTTP services expose their OpenAPI v3 spec at `GET /v3/api-docs` (JSON committed in `docs/`).
`notification` is a pure RabbitMQ consumer with no REST API of its own.

## Event flow (async, RabbitMQ)

- `microcredential` publishes `microcredential.pending` / `.approved` / `.rejected` on the
  `microcredential.events` exchange when a credential changes state.
- `productcatalog` publishes `product.unit_available` on the `product.events` exchange when units
  are added (`POST /products/{id}/units`).
- `notification` consumes both streams, looks up the relevant user/product details, and logs the
  "email" that would be sent.

## Testing

```bash
sbt course/test
```

---

> Full architecture, diagrams, and setup guide coming soon.
