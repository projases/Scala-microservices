# Scala Microservices

This is a functional approach to my Software Architecture course project at the Universitat Oberta de Catalunya (UOC) -> https://github.com/UOC-EPCSD-ISCSD-SA
Built with Scala 3, Cats Effect, http4s, tapir, and Doobie, following a functional architecture.

Key differences from the course project:
- No Spring Boot, no imperative style.
- RabbitMQ is used for async communication between services, instead of Kafka.
- One docker compose file to run all services, Postgres, and RabbitMQ.
- Product catalog was not implemented, but a stub service is provided to simulate product availability events.
- Used parallelism and concurrency to improve performance, instead of a single-threaded approach.


## Services

| Service          | Port  | Description                                                |
|------------------|-------|------------------------------------------------------------|
| `course`         | 18084 | Course lifecycle, enrollments, status                       |
| `user`           | 18082 | Users, alerts, product validation                           |
| `microcredential`| 18085 | Microcredential requests                                    |
| `productcatalog` | 18081 | Stub product catalog; publishes `product.unit_available`    |
| `notification`   | 18083 | RabbitMQ consumer turning domain events into (logged) emails |

## Requirements

- sbt (Scala 3.9.0 — LTS)
- Docker & docker compose
- Postgres, RabbitMQ (via `docker compose up`)

## Running

```bash
docker compose up --build -d
```

HTTP services expose their OpenAPI v3 spec at `GET /v3/api-docs` (JSON committed in `docs/`).
`notification` is a pure RabbitMQ consumer with no REST API of its own.

### Configuration overlay

Each service reads its config from `src/main/resources/application.conf` (defaults: localhost
ports, RabbitMQ on `localhost`). Inside Docker, the compose file mounts `docker/<service>.conf` on
top of that so the services reach each other and the broker at the compose hostnames (e.g.
`rabbitmq`). To run a single service natively against the Dockerised broker, override with
`-Dconfig.file` or set `rabbit.host=localhost`.

## Event flow (async, RabbitMQ)

- `microcredential` publishes `microcredential.pending` / `.approved` / `.rejected` on the
  `microcredential.events` exchange when a credential changes state.
- `productcatalog` publishes `product.unit_available` on the `product.events` exchange when units
  are added (`POST /products/{id}/units`).
- `notification` consumes both streams, looks up the relevant user/product details, and logs the
  "email" that would be sent.

## Architecture

A small set of HTTP services (Cats Effect + http4s + tapir) talk to each other **synchronously**
over REST when they need an immediate answer (e.g. `user` validating a product against
`productcatalog`), and **asynchronously** over RabbitMQ when an action can be deferred (domain
events consumed by `notification`).

```text
                 ┌───────────────┐   GET /products/{id}   ┌────────────────┐
   user ─────────▶ productcatalog ───────────────────────▶ (validates      )
   alerts        │               │                        │  product exists)
                 └───────┬───────┘                        └────────────────┘
                         │ POST /products/{id}/units → product.events
                         │                       product.unit_available
                         ▼
                 ┌───────────────┐   microcredential.events   ┌──────────────┐
   microcredential ─────────────▶ notification ─────────────▶   (logs the    )
   create/approve │               │   pending/approved/rejected│  "email")    │
                 └───────────────┘                            └──────────────┘
```

Every service is a self-contained module under `modules/`, packaged as a fat JAR
(`sbt-assembly`) run inside its own container. RabbitMQ holds the glue: exchange
`product.events` (`product.unit_available`) and `microcredential.events`
(`microcredential.pending|approved|rejected`).

### Verifying the async flow

`scripts/smoke-test.sh` drives all three event legs and asserts the expected "email" shows up in
the `notification` logs:

```bash
docker compose up --build -d
./scripts/smoke-test.sh
```

Exits non-zero on failure so it can gate CI. Run it by hand with

```bash
curl -X POST localhost:18081/products/1/units            # leg 1
curl -X POST localhost:18085/microcredentials/1/create  # leg 2
curl -s localhost:18085/microcredentials/pending        # pick an id
curl -X PATCH localhost:18085/microcredentials/<id>/approve   # leg 3
docker compose logs notification | grep "Sending an email"
```

### Regenerating the OpenAPI specs

For the tapir-based services the `docs/*-openapi.json` files are generated from the same
endpoints that serve requests, so the spec cannot drift from the code:

```bash
sbt "course/runMain edu.uoc.epcsd.course.http.ApiDocsGen"        > docs/course-openapi.json
sbt "user/runMain    edu.uoc.epcsd.user.http.ApiDocsGen"         > docs/user-openapi.json
sbt "microcredential/runMain edu.uoc.epcsd.microcredential.http.ApiDocsGen" > docs/microcredential-openapi.json
```

`productcatalog` is a minimal plain-http4s stub (no tapir); its spec is hand-authored in
`docs/productcatalog-openapi.json`, copied verbatim into its resources, and served at
`/v3/api-docs`.

## Testing

```bash
sbt test
```

runs the unit suites of every module (they pass warning-clean under `-Wunused:all`).

---
