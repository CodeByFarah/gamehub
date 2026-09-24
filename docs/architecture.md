# Architecture

## Shape

A **modular monolith**: one deployable, strict internal boundaries enforced by
tests rather than by convention.

```
com.gamehub
├── domain/           pure logic. No Spring, no JPA, no network.
│   ├── matchmaking/  engine, cost function, policy
│   ├── leaderboard/  score codec, key builder
│   ├── achievement/  rule evaluator
│   ├── recommendation/ content-based scorer
│   └── event/        sealed event hierarchy
├── application/      use cases, ports, outbox relay
├── infrastructure/   adapters: JPA, Redis, Kafka, Gemini
├── api/              controllers, DTOs, errors, security
└── config/           Spring wiring, typed properties
```

Dependencies point inward. `domain` depends on nothing but the JDK, which is
what makes the matchmaking engine, the leaderboard codec and the
recommendation scorer testable with no container, no mocks and no database.

`ArchitectureTest` fails the build if any of that changes. It caught a real
violation during construction: `LeaderboardController` referenced enums nested
inside a JPA entity, leaking persistence into the public API contract. The fix
was to move them into the domain, not to relax the rule.

## Why a modular monolith and not microservices

Three consumers fan out from one event, which is the shape people reach for
microservices to express. It would be the wrong call here:

- **One team, one release cadence.** The coordination cost microservices buy
  down does not exist yet.
- **Shared schema.** Statistics, achievements and recommendations all read the
  same tables. Splitting them means either a shared database, which is the
  worst of both worlds, or a data-duplication project.
- **The outbox already provides the seam.** Every consumer communicates only
  through events and its own idempotency ledger, so extracting one is a
  deployment change rather than a rewrite.

Recorded in [ADR-006](adr/ADR-006-modular-monolith.md).

## Request path

```
  Android client
       |  HTTPS, Bearer JWT
       v
  JwtAuthenticationFilter    verifies signature, populates SecurityContext
       |                     never rejects; authorisation rules decide
       v
  Controller                 validates, delegates. No logic.
       |                     identity from @CurrentUser, never from the body
       v
  Service                    transaction boundary, orchestration
       |
       +--> Repository       Postgres, source of truth
       +--> CacheStore       Redis, always with a fallback
       +--> OutboxPublisher  same transaction as the state change
       +--> AiClient         port, never the adapter
```

Errors leave through one `@RestControllerAdvice` that shapes every exception
into a single documented envelope. A raw Spring error page leaks the exception
class and sometimes a stack trace; a driver exception can carry a connection
string or user data.

## Data layer roles

| Store | Role | If it disappears |
|---|---|---|
| **PostgreSQL** | Source of truth for everything | The service is down. This is the only hard dependency |
| **Redis** | Serving layer, cache, leases | Slower, still correct. Every read falls back |
| **Kafka** | Event transport | Derived state stops updating. Nothing is lost: events are durable in the outbox |

That table is the most important thing on this page. Redis and Kafka are both
*recoverable* losses by design, and that is what makes a Redis lease
acceptable for matchmaking and `allkeys-lru` acceptable for leaderboards.

## Horizontal scaling

The API is stateless: no sessions, no server-side login state, no in-memory
data that matters. Any instance can serve any request.

Three background jobs run on **every** instance, and each is safe under
concurrency by a mechanism that does not depend on coordination:

| Job | Safety mechanism | If the lease fails open |
|---|---|---|
| Outbox relay | `FOR UPDATE SKIP LOCKED` | Disjoint batches anyway; no duplication |
| Matchmaker tick | Conditional ticket claim | Duplicate proposals, one wins, rest roll back |
| Ticket expiry | Idempotent `UPDATE` | Harmless |

The Redis lease on top of each is a **work-deduplication optimisation**, not a
correctness mechanism. That distinction is deliberate: Redis locks are not
safe under arbitrary failure, and a design that depended on one for
correctness would need fencing tokens or a consensus service.

### The real scaling ceiling

Not CPU. **Database connections.**

Each instance opens a Hikari pool of 20. Postgres is configured for 200. That
caps instances at roughly 5–8 with headroom for migrations and psql, which is
why Cloud Run `max_instances` is 5 and not unbounded.

A pool larger than the database can serve does not make the system faster — it
moves the queue out of the application, where it is visible and measurable,
and into the database, where it is neither.

Beyond that ceiling the next step is PgBouncer in transaction mode, not more
instances.

## Consistency model

**Strongly consistent** on the write path: a session completion and its event
commit atomically, and a cloud-save conflict is detected synchronously.

**Eventually consistent** for derived state: statistics, achievements,
leaderboards and recommendations. The window is the relay poll (500ms) plus
consumer lag.

Where that would be jarring the API does not rely on it — session completion
returns the session directly rather than re-reading a derived counter.

## Failure behaviour

| Failure | Behaviour |
|---|---|
| Redis down | Reads fall back to Postgres. Leases fail closed, so background jobs skip a cycle |
| Kafka down | Requests still succeed. Outbox backlog grows, drains on recovery |
| Postgres down | Readiness fails, instance leaves the load balancer. Liveness deliberately does **not** fail |
| Gemini down | Circuit opens, AI degrades to deterministic fallback, responses flagged |
| Consumer poison record | Retried with backoff, then dead-lettered so the partition keeps moving |

Liveness excluding dependencies is deliberate. Restarting a process because
Redis is down fixes nothing and turns a degraded service into an unavailable
one.

## Technology choices, briefly

Each is expanded in an ADR.

- **Java 21** for virtual threads: this workload is almost entirely blocked on
  I/O.
- **PostgreSQL** and not "a relational database": `SKIP LOCKED`,
  `ON CONFLICT`, partial unique indexes and `websearch_to_tsquery` are all
  load-bearing. Swapping engines would mean redesigning four subsystems.
- **Redis** specifically for sorted sets. Ranking is the one query SQL does
  badly at read time.
- **Kafka** for fan-out with replay and per-key ordering.
- **Flyway** owns the schema; Hibernate is set to `validate` and never emits
  DDL, so entity drift is a startup failure rather than a runtime surprise.

## What is deliberately absent

- **No API gateway.** One service; Cloud Run handles TLS and routing.
- **No service mesh.** Nothing to mesh.
- **No CQRS or event sourcing.** Events drive side effects; they are not the
  source of truth. `game_events` is an outbox and an audit log, not a ledger
  to rebuild state from.
- **No distributed transactions.** The outbox exists precisely so none are
  needed.
- **No Kafka in the cloud configuration.** Cost, not principle. See
  [deployment.md](deployment.md).
