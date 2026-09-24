# GameHub

A gaming platform backend built to demonstrate the engineering behind systems
like Google Play Games: matchmaking, leaderboards, cloud saves, an
event-driven pipeline, and an AI layer that degrades honestly when its
provider is unavailable.

This is a portfolio project. Nothing here is deployed, nothing here has users,
and every claim in this README is either backed by a command you can run or is
explicitly marked as not executed.

---

## Status

| Area | State |
|---|---|
| Backend | Builds and compiles clean under JDK 21 with `-Werror` |
| Unit and architecture tests | **64 tests, 0 failures** (`./gradlew test`) |
| Integration and concurrency tests | Written and compiling. **NOT EXECUTED** — require Docker |
| Load tests | Written and syntax-checked. **NOT MEASURED** — no run has happened |
| Terraform | `terraform validate` passes against Google provider 6.50.0 |
| Android app | **Not built** |
| Deployment | **Never executed** |

There are no performance figures anywhere in this repository, because none
have been measured. See [docs/performance.md](docs/performance.md) for what
would be measured and how.

---

## Why this exists

Most portfolio backends are CRUD with extra steps. The interesting parts of a
gaming platform are the ones CRUD does not exercise:

- **Matchmaking** is a real algorithm with a starvation problem, a cost
  function, and a race condition that only appears across multiple instances.
- **Leaderboards** are the one query Postgres does badly at read time, and
  fixing that properly means understanding what a sorted set actually buys.
- **Cloud saves** are a distributed-systems problem wearing a product
  feature as a disguise: two devices, one row, and a data-loss bug that is
  completely silent if you get it wrong.
- **The event pipeline** has a dual-write problem that no amount of careful
  ordering solves.
- **AI** is a dependency that is slow, expensive, occasionally wrong, and
  sometimes absent — which makes it a good test of how a system handles a
  dependency it cannot trust.

Each of those has a document explaining the problem, the failure mode, the
chosen solution, and what it costs.

---

## Architecture

```
                         Android app
                              │  HTTPS, JWT
                              ▼
                   ┌──────────────────────┐
                   │   Spring Boot API    │  stateless, horizontally scalable
                   │  controllers → svc   │
                   └──────────┬───────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
  ┌───────────┐        ┌───────────┐        ┌──────────────┐
  │ PostgreSQL│        │   Redis   │        │  Gemini API  │
  │           │        │           │        │              │
  │ source of │        │ ZSETs     │        │ via AiClient │
  │ truth     │        │ cache     │        │ port, with a │
  │ + outbox  │        │ leases    │        │ deterministic│
  └─────┬─────┘        └───────────┘        │ fallback     │
        │                                   └──────────────┘
        │ outbox relay
        │ (FOR UPDATE SKIP LOCKED)
        ▼
  ┌───────────┐
  │   Kafka   │
  └─────┬─────┘
        │
   ┌────┴────────────┬──────────────────┐
   ▼                 ▼                  ▼
Statistics     Achievements      Recommendations
consumer       consumer          + leaderboard writes

   every consumer is idempotent via processed_events
   every failure retries, then dead-letters
```

Full detail in [docs/architecture.md](docs/architecture.md).

---

## Technology, and why

Every dependency here earns its place. Nothing was added to lengthen the list.

| Technology | Why |
|---|---|
| **Java 21** | Virtual threads. Request threads here spend nearly all their time blocked on I/O, which is exactly the workload they exist for |
| **Spring Boot 3.4** | The ecosystem where the Kafka, JPA and Actuator integrations are mature |
| **PostgreSQL 16** | `SKIP LOCKED`, `ON CONFLICT`, partial unique indexes and full-text search are all load-bearing. Not interchangeable with another database |
| **Redis 7** | Sorted sets. Ranking is O(log N) there and O(better players) in SQL |
| **Kafka 3.9** | Fan-out to three independent consumers with per-player ordering and replay |
| **Flyway** | Schema is owned by migrations; Hibernate is set to `validate` and never touches DDL |
| **Testcontainers** | The behaviours above are Postgres-specific. H2 would test nothing that matters |
| **ArchUnit** | Enforces the layering as a failing test rather than as a convention |
| **Resilience4j** | Circuit breaker and retry around the one dependency that is not ours |
| **Micrometer + OTel** | Metrics and traces sharing one trace id |

---

## Four problems worth reading about

### 1. A leaderboard tie is decided by a UUID, unless you stop it

Redis sorted sets rank by one number. Two players on 5000 points are separated
by lexicographic member order — which here is a random primary key. That is
not a tie-break, it is a coin flip, and it changes on every cache rebuild.

The fix packs both dimensions into one score:

```
encoded = score << 29 | (TIME_MAX - secondsSinceEpoch)
          \_________/   \__________________________/
           high 20 bits        low 29 bits
```

Inverting the timestamp turns "earlier is better" into "numerically larger",
so one `ZREVRANGE` gives *higher score first, earlier achiever wins*. The
maximum value is 2⁴⁹−1, safely inside the 2⁵³ exact-integer range of a double,
so the packing is lossless and ranks never shuffle.

→ [docs/leaderboards.md](docs/leaderboards.md)

### 2. "A player cannot be matched twice" is a database invariant

Double-tap Find Match. Two requests, two instances, one player. Both read "no
active ticket", both insert, and the matchmaker can now put that player in two
games at once.

No amount of Java fixes this. `synchronized` guards one JVM. At READ
COMMITTED, neither transaction can see the other's uncommitted insert, so both
reads are *correct*.

```sql
CREATE UNIQUE INDEX uq_mmq_one_active_ticket_per_user
    ON matchmaking_queue (user_id) WHERE status = 'WAITING';
```

The database serialises the inserts and rejects the loser. The service
translates that violation into the same 409 the fast path returns — that
translation is the mechanism, not defensive decoration.

→ [docs/matchmaking.md](docs/matchmaking.md)

### 3. Publishing an event and committing a transaction cannot both be atomic

Publish then commit: the transaction rolls back and consumers award
achievements for a game that never finished. Commit then publish: the process
dies in between and the event is lost forever, silently.

The event is inserted into `game_events` **in the same transaction** as the
state change, so it commits or rolls back with it. A relay then publishes it:

```sql
SELECT * FROM game_events WHERE published_at IS NULL
 ORDER BY occurred_at LIMIT 200
 FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` is what lets the relay run on every instance with no leader
election: each one takes a disjoint batch and none of them waits.

This gives at-least-once, not exactly-once. Duplicates are absorbed by an
idempotency ledger keyed by `(event_id, consumer_group)` — keyed by group
because one event is legitimately processed by three consumers.

→ [docs/event-driven-architecture.md](docs/event-driven-architecture.md)

### 4. Two devices, one save slot, one silent data-loss bug

Phone and tablet both hold version 17. Both upload. Without a version
predicate both `UPDATE`s succeed, the second destroys the first, and nothing
anywhere reports an error — the player just finds work missing.

```sql
UPDATE cloud_saves SET version = version + 1, ...
 WHERE id = :id AND version = :expectedVersion;
```

One row or zero. Zero means another device won, and the 409 carries the
server's current version and checksum so the client can merge rather than
guess.

→ [docs/cloud-saves.md](docs/cloud-saves.md)

---

## Running it locally

**Prerequisites:** JDK 21, Docker, and roughly 4GB of free memory.

```bash
git clone <repository-url> gamehub
cd gamehub

cp .env.example .env          # no real secrets; JWT_SECRET is required
docker compose up -d          # Postgres, Redis, Kafka, Prometheus, Grafana, OTel

cd backend
./gradlew bootRun
```

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| OpenAPI UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Grafana | http://localhost:3000 (anonymous viewer) |
| Prometheus | http://localhost:9090 |
| Kafka UI | http://localhost:8081 |

The catalogue is seeded by migration `V2`, so there are ten games to browse
immediately.

**No Gemini key is needed.** With `GEMINI_API_KEY` empty the app starts
normally and the AI endpoints use deterministic keyword extraction, returning
results flagged `degraded: true`. That is a supported mode, not a broken one.

### Running the tests

```bash
cd backend

./gradlew test              # unit + ArchUnit. No Docker. ~15s
./gradlew integrationTest   # Testcontainers. Requires Docker
./gradlew check             # both
```

`test` and `integrationTest` are split by JUnit tag, not filename, so a
misnamed class cannot quietly skip CI.

### Running the load tests

```bash
docker compose up -d
cd backend && ./gradlew bootRun     # separate shell

k6 run tests/load/seed-users.js
./tests/load/run-all.sh
```

Each scenario declares thresholds and exits non-zero when one is missed, so
these work as a gate rather than a report.

---

## What was verified, and what was not

Reproducing the verified results needs only JDK 21:

```
$ ./gradlew compileJava
BUILD SUCCESSFUL          # -Xlint:all -Werror, zero warnings

$ ./gradlew test
BUILD SUCCESSFUL
64 tests, 0 failures      # unit + ArchUnit

$ terraform validate
Success! The configuration is valid.
```

**Not executed, and not claimed:**

- Integration and concurrency tests. Written and compiling; they need a Docker
  daemon, which was unavailable on the machine this was built on.
- Load tests. Written and syntax-checked with `node --check`; never run, so
  there are no latency or throughput numbers anywhere.
- Cloud deployment. The Terraform validates but has never been applied.
- The Android app, which is not built.

One test failure worth mentioning, because it is the sort of thing that
usually gets quietly deleted: `ArchitectureTest.controllersDoNotTouchPersistence`
failed on its first run. `LeaderboardController` referenced `Scope` and
`Period`, which were nested inside the JPA entity — a genuine leak of the
persistence layer into the public API contract. The fix was to move those
enums into the domain, not to relax the rule.

---

## Repository layout

```
gamehub/
├── backend/                  Spring Boot service
│   └── src/main/java/com/gamehub/
│       ├── domain/           pure logic: matchmaking, leaderboards,
│       │                     achievements, recommendations. No framework.
│       ├── application/      services, ports, outbox relay
│       ├── infrastructure/   JPA, Redis, Kafka, Gemini adapters
│       ├── api/              controllers, DTOs, errors, security
│       └── config/           Spring wiring and typed properties
├── android/                  Kotlin + Compose client (not built)
├── infra/
│   ├── docker/               Prometheus, Grafana, OTel, Kafka topics
│   └── terraform/            Cloud Run, Cloud SQL, Memorystore, secrets
├── tests/load/               k6 scenarios
├── docs/                     architecture and decision records
└── .github/workflows/        PR and main pipelines
```

The `domain` package depends on nothing but the JDK, and
[ArchitectureTest](backend/src/test/java/com/gamehub/architecture/ArchitectureTest.java)
fails the build if that ever changes.

---

## Documentation

| Document | Contents |
|---|---|
| [architecture.md](docs/architecture.md) | System design, boundaries, scaling model |
| [database.md](docs/database.md) | Schema, every index and why it exists |
| [api.md](docs/api.md) | Endpoints, error envelope, status codes |
| [matchmaking.md](docs/matchmaking.md) | Cost function, complexity, the race |
| [leaderboards.md](docs/leaderboards.md) | Score packing, ranking, fallback |
| [cloud-saves.md](docs/cloud-saves.md) | Optimistic concurrency, conflict resolution |
| [event-driven-architecture.md](docs/event-driven-architecture.md) | Outbox, idempotency, DLT |
| [ai-architecture.md](docs/ai-architecture.md) | Port, validation, degradation |
| [redis-strategy.md](docs/redis-strategy.md) | Every key, TTL and invalidation rule |
| [testing.md](docs/testing.md) | Strategy and what is deliberately not tested |
| [performance.md](docs/performance.md) | What would be measured, and how |
| [observability.md](docs/observability.md) | Metrics, traces, alerts |
| [deployment.md](docs/deployment.md) | Local vs cloud, including the Kafka gap |
| [security.md](docs/security.md) | Auth, secrets, threat model |

### Decision records

| ADR | Decision |
|---|---|
| [001](docs/adr/ADR-001-java-spring-boot.md) | Java 21 and Spring Boot |
| [002](docs/adr/ADR-002-transactional-outbox.md) | Transactional outbox over direct publishing |
| [003](docs/adr/ADR-003-redis-leaderboards.md) | Redis sorted sets with Postgres as source of truth |
| [004](docs/adr/ADR-004-cloud-save-concurrency.md) | Optimistic concurrency over locking or CRDTs |
| [005](docs/adr/ADR-005-ai-provider-abstraction.md) | A port with a deterministic fallback |
| [006](docs/adr/ADR-006-modular-monolith.md) | Modular monolith over microservices |

---

## Known limitations

Stated plainly, because a portfolio project that claims to be production-ready
is making the one claim nobody believes.

- **No Kafka in the cloud configuration.** Managed Kafka on GCP has no
  scale-to-zero tier and would cost more than everything else combined. The
  outbox makes the transport swappable; `deployment.md` covers the three
  options.
- **Single-region.** No multi-region replication, no geo-routing.
- **Matchmaking is 1v1.** The engine produces pairs. Team matchmaking is a
  genuinely different algorithm, not a parameter change.
- **Recommendations are content-based only.** Collaborative filtering needs an
  interaction matrix that does not exist yet.
- **No refresh-token revocation list.** Refresh tokens are revocable in the
  sense that the user record is re-read on use, but a stolen token stays valid
  until the account is suspended.
- **No rate limiting on unauthenticated endpoints.** The limiter keys on the
  authenticated principal, so catalogue browsing is unprotected.
- **Cache stampede is not guarded.** A deliberate trade: the cached items are
  cheap single-row lookups, and a per-key distributed lock on every cached
  read costs more than the problem.

---

## Licence

MIT. See [LICENSE](LICENSE).
