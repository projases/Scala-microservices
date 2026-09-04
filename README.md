# Scala Microservices

Microservices project for the UOC Software Architecture course. Built with Scala 3,
Cats Effect, http4s, tapir, and Doobie, following a tagless-final / functional architecture.

## Services

| Service          | Port  | Description                              |
|------------------|-------|------------------------------------------|
| `course`         | 18084 | Course lifecycle, enrollments, status     |
| `user`           | 18082 | Users, alerts, product validation         |
| `microcredential`| 18085 | Microcredential requests                  |
| `productcatalog` | 18081 | Stub product catalog                      |

## Requirements

- sbt (Scala 3.3.8)
- Docker & docker compose
- Postgres, RabbitMQ (via `docker compose up`)

## Running

```bash
docker compose up --build -d
```

Each service exposes its OpenAPI v3 spec at `GET /v3/api-docs` (JSON committed in `docs/`).

## Testing

```bash
sbt course/test
```

---

> Full architecture, diagrams, and setup guide coming soon.
