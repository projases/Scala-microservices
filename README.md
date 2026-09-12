# Scala Microservices

This is a functional approach to my Software Architecture course project at the Universitat Oberta de Catalunya (UOC) -> https://github.com/UOC-EPCSD-ISCSD-SA

Built with Scala 3, Cats Effect, http4s, tapir, and Doobie, following a functional architecture.

Key differences from the course project:
- No Spring Boot, no imperative style.
- RabbitMQ is used for async communication between services, instead of Kafka.
- One docker compose file to run all services, Postgres, and RabbitMQ.
- Product catalog was not implemented, but a stub service is provided to simulate product availability events.

## Architectural Decisions 

### Domain Modeling:
- In the Java version entities were modeled as "fat aggregate" containing a nested List of enrollments. This created issues with immutability, as one would have to copy every enrollment on every state change. Decoupled Course and Enrollment into "skinny classes" referencing only by courseId. 

- Using id: [Option] or sentinel values broke the DDD rule of not allowing illegal states representable. The solution was to create separate persisted aggregates (Course) from draft entities (NewCourse). Since NewCourse lacks id field, it would be impossible to pass an unsaved entity to an update query for example. 

- Domain invariants are validated on creation

- Tagless-Final algebra decoupled domain behaviors from concrete classes. CourseError ADT allowed to declare failure modes (F[Either[CourseError, A]]), thus eliminating nulls, runtime exceptions -- while allowing composition. 

- Using Cats Effects fibers allowed to move from *O(N)* sequential network delays to *O(1)* parallel operation


## Development status

This is a *working* system — every service runs, the tests are green, and the async
flow is smoke-tested — that is also being actively iterated on as a
functional-programming study. If you are skimming: the codebase is healthy and running;
what follows says what is being changed next and why.

### Stable today

- 5 services under Docker Compose; RabbitMQ-backed async flow gated by `scripts/smoke-test.sh`.
- Tagless-final service layers, Doobie repositories, parallel per-enrollment fibers.
- Unit suites green and warning-clean under `-Wunused:all` (`sbt test`).

### Actively being developed

- **Domain modeling — "draft vs persisted" split.** `Course`, `Enrollment` and
  `Microcredential` still carry `id: Option[Long]`, forcing every update / event publish
  to unwrap the id at runtime (`getOrElse(throw ...)` guards). The decided fix is to
  separate `NewCourse` / `NewEnrollment` / `NewMicrocredential` (no `id` field; `create`
  issues it at the repository boundary) from the persisted types (`id: Long`), making
  "updating an unsaved entity" a compile error instead of a runtime exception.

- **Effects-as-Data exploration.** A side-by-side, teaching re-implementation of the
  course lifecycle as pure reducers returning effect lists, interpreted by an
  imperative shell. Kept as a comparison artifact to
  deepen the functional-core / imperative-shell story — not a replacement for the
  current tagless-final service.

## Services

| Service           | Port  | Description                                                  |
|-------------------|-------|--------------------------------------------------------------|
| `course`          | 18084 | Course lifecycle, enrollments, status                        |
| `user`            | 18082 | Users, alerts, product validation                            |
| `microcredential` | 18085 | Microcredential requests                                     |
| `productcatalog`  | 18081 | Stub product catalog; publishes `product.unit_available`     |
| `notification`    | 18083 | RabbitMQ consumer turning domain events into (logged) emails |

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

## Event flow (async, RabbitMQ)

- `microcredential` publishes `microcredential.pending` / `.approved` / `.rejected` on the
  `microcredential.events` exchange when a credential changes state.
- `productcatalog` publishes `product.unit_available` on the `product.events` exchange when units
  are added (`POST /products/{id}/units`).
- `notification` consumes both streams, looks up the relevant user/product details, and logs the
  "email" that would be sent.

## Architecture

A small set of HTTP services (Cats Effect + http4s + tapir) talk to each other **synchronously**
over REST when they need an immediate answer, and **asynchronously** over RabbitMQ -- domain events consumed by `notification`.

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
