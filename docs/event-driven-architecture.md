# Event-driven architecture

## Why events at all

Completing a game triggers four things: statistics, achievements,
recommendations, and leaderboard writes.

Doing them inline would make ending a game as slow as the slowest of them and
as available as the least available. A statistics bug would stop people
playing. That coupling is the problem the event bus exists to remove.

```
                POST /api/sessions/{id}/complete
                              |
                              v
                  ┌───────────────────────┐
                  │  one transaction:     │
                  │  close session row    │
                  │  + insert outbox row  │
                  └───────────┬───────────┘
                              | commit
                              v
                      outbox relay
              (FOR UPDATE SKIP LOCKED, every instance)
                              |
                              v
                 gamehub.game.events.v1
                              |
        ┌─────────────────────┼─────────────────────┐
        v                     v                     v
   Statistics           Achievements         Recommendations
   consumer             consumer             consumer
                              |                     |
                              v                     v
                     AchievementUnlocked      leaderboard ZADD
```

## The dual-write problem

A request that both mutates state and emits an event cannot do the two
atomically. There is no transaction spanning Postgres and Kafka, and **both
orderings are wrong**:

| Order | Failure |
|---|---|
| Publish, then commit | The transaction rolls back. Consumers award achievements for a game that, as far as the database is concerned, never finished |
| Commit, then publish | The process dies in between. The session is recorded and the event is lost forever, silently |

No amount of careful coding fixes this. It is a property of writing to two
systems that cannot agree on a commit.

## The transactional outbox

Insert the event into `game_events` **in the same transaction** as the state
change. It commits or rolls back atomically with it, because it *is* the same
transaction. A separate relay then publishes it after commit.

`OutboxEventPublisher.publish` is annotated `@Transactional(propagation =
MANDATORY)`, not `REQUIRED`. With `REQUIRED`, a caller that forgot its own
transaction would silently get a separate one for the outbox row, and the
event would commit even when the business change rolled back. That failure
would be invisible in testing. `MANDATORY` makes the mistake impossible.

Serialisation failure aborts the transaction rather than being swallowed.
Swallowing it would commit the business change with no event, which is exactly
the divergence the outbox exists to prevent.

## The relay

```sql
SELECT * FROM game_events
 WHERE published_at IS NULL
 ORDER BY occurred_at
 LIMIT 200
 FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` is the load-bearing clause:

- **Without any locking**, three instances read the same rows and publish
  every event three times.
- **With plain `FOR UPDATE`**, the duplication stops but the instances
  serialise: two block waiting for the first to finish its batch, so adding
  instances adds no throughput.
- **With `SKIP LOCKED`**, each instance takes a disjoint batch and none of
  them waits.

That is what lets the relay run on every instance with no leader election.
Backed by `idx_game_events_unpublished`, a partial index over the unpublished
tail, so the scan costs the size of the backlog rather than of all history.

The transaction matters as much as the query. `OutboxBatchPublisher` is a
separate bean from `OutboxRelay` specifically because Spring transactions are
proxy-based: calling a `@Transactional` method as `this::drainBatch` never
crosses the proxy, so it would run with **no transaction**, the row locks
would release immediately, and every event would be duplicated — while the
code looked correct and single-instance tests passed.

Sends are awaited, not fired and forgotten. An async send would let the
transaction commit and stamp rows published before Kafka acknowledged them,
reintroducing the loss the outbox prevents.

## Delivery semantics

**At-least-once.** A row can be published and the process die before
`published_at` is stamped, so the next run republishes it.

Exactly-once would need Kafka transactions spanning the database write — a
large amount of machinery to remove a problem the consumers already solve.
This is the deliberate cheaper half of that trade.

## Consumer idempotency

```sql
INSERT INTO processed_events (event_id, consumer_group, processed_at)
VALUES (:eventId, :consumerGroup, now())
ON CONFLICT (event_id, consumer_group) DO NOTHING;
```

Returns 1 for a first sighting, 0 for a replay.

Three things about this are deliberate:

**It is an atomic upsert, not a check-then-act.** A `SELECT` followed by an
`INSERT` has a window in which two consumer threads both see no row and both
proceed. This has no such window.

**It shares a transaction with the work it guards** (`MANDATORY` again).
Separate transactions would let a crash between them mark an event processed
with none of its effects applied — the consumer-side version of the same
dual-write problem.

**It is keyed by consumer group, not globally.** One `GameCompleted` event is
legitimately processed three times, once by each consumer. A global key would
let whichever ran first suppress the other two.

Achievements add a **second, independent** layer: the composite primary key on
`user_achievements`, reached through an insert that ignores conflicts.
Awarding the same achievement twice is visible to the player and awkward to
undo, so it is guarded twice over.

## Ordering

Kafka guarantees ordering only within a partition. The partition key is the
**user id**, so all of one player's events land on one partition and stay
ordered — a player cannot complete a game before starting it.

That is the only ordering GameHub actually needs. A single global ordering
would mean one partition and no horizontal scaling at all.

`MatchCreated` is keyed by match id instead: it concerns two players and
neither has a stronger claim, and nothing downstream orders match creation
against a player's other events.

`GameStarted` and `GameCompleted` deliberately share one topic. They describe
the same aggregate and must stay ordered relative to one another; splitting
them across topics would throw that away for no benefit.

## Failure handling

A consumer failure is one of two kinds, and conflating them is how systems
lose data or stall:

| Kind | Example | Right response |
|---|---|---|
| **Transient** | Postgres briefly unreachable, lock timeout, deadlock | Retry. Dead-lettering these would discard good events over a two-second blip |
| **Poison** | A payload this consumer can never handle, a schema it does not understand | Dead-letter. Retrying never works, and retrying forever blocks the partition so every later event for every other player stops |

Configured as: retry with exponential backoff from 500ms, capped at 10s, with
a **total elapsed ceiling of 60s**.

That ceiling is not arbitrary. `max.poll.interval.ms` is five minutes, and a
retry sequence that outlasts it gets the whole consumer evicted from the
group, triggering a rebalance that replays the entire in-flight batch. The
retry budget is deliberately well inside the poll interval.

Deserialisation and validation failures skip retries entirely and go straight
to the dead-letter topic. They can never succeed on a retry, so retrying them
would burn the whole backoff budget to reach the same conclusion while holding
up the partition.

### Dead-letter topics

Failed records go to `<topic>.DLT` on the **same partition number**, so a
human reading the DLT can still reason about ordering and about which key was
affected. DLT retention is 30 days against 7 for the main topics: a poison
record needs a person to look at it, and that person may not arrive the same
week.

**Nothing consumes the DLT automatically.** That is intentional. A process
that silently reprocessed or deleted poison records would hide the bug that
produced them.

## Event catalogue

| Event | Topic | Key | Emitted by |
|---|---|---|---|
| `GameStarted` | `gamehub.game.events.v1` | userId | Session start |
| `GameCompleted` | `gamehub.game.events.v1` | userId | Session complete |
| `AchievementUnlocked` | `gamehub.achievement.events.v1` | userId | Achievement **consumer** |
| `MatchCreated` | `gamehub.match.events.v1` | matchId | Matchmaker commit |
| `CloudSaveUpdated` | `gamehub.cloudsave.events.v1` | userId | Cloud save write |

`AchievementUnlocked` is the only event produced by a consumer. Achievements
are derived state, and deriving them in the consumer keeps the rule in one
place instead of duplicating it in every endpoint that could trigger an
unlock. It carries `causedByEventId`, so a replayed source event is traceable
rather than mysterious.

`CloudSaveUpdated` carries the version but **not** the payload. Save blobs run
to 1 MiB, and a Kafka topic is the wrong place to keep them: broker storage
would be multiplied by the retention window for data that already lives in
Postgres.

## Event versioning

The root is a **sealed interface**. A consumer switch over these is checked by
the compiler, so adding an event type breaks the build at every place that has
to decide what to do about it. An open hierarchy would let a new event be
added and silently ignored by three consumers.

Jackson typing is by a **logical name** (`GAME_COMPLETED`), not by Java class
name. Class names leak package structure into the wire format and make a
package rename a breaking change for every consumer and every message already
sitting in a topic.

Every event carries `schemaVersion` as a Kafka header, so a consumer can
reject or upcast a shape it does not understand rather than deserialising it
into a half-populated object and acting on the result.

Topic names carry an explicit `.v1` suffix. When an incompatible change is
unavoidable, the new shape goes to `.v2` and both run side by side until every
consumer has moved. Mutating a live topic's schema in place is the change that
cannot be rolled back, because the messages already written cannot be
un-written.

## Eventual consistency, and where it shows

A player who finishes a game and immediately opens their profile may see the
previous count. The window is the relay poll (500ms) plus consumer lag,
typically well under a second.

Where that would be jarring, the API does not rely on it: the session
completion response returns the completed session directly rather than
re-reading a derived counter.

One ordering subtlety is accepted rather than coordinated. The achievement
consumer reads the profile to evaluate cumulative rules like `TEN_WINS`, and
the statistics consumer is what updates that profile. Both consume
independently, so the achievement consumer may run first and see a count one
behind — the achievement then unlocks on the next qualifying game, at most one
game late.

Chaining them would couple two subsystems and make each an availability
dependency of the other, which is the thing the event bus exists to avoid.

## Producer configuration

```yaml
acks: all
enable.idempotence: true
max.in.flight.requests.per.connection: 5
```

`acks=all` with idempotence gives effectively-once produce semantics per
partition: the relay must never lose an event it has already marked published,
and must never create a duplicate through an internal producer retry.

Consumer offsets are committed by the container after the listener returns,
never by a background timer. A crash mid-handler therefore **replays** the
record rather than skipping it — which is exactly what the idempotency ledger
is built to absorb.

## Observability

| Metric | Question |
|---|---|
| `gamehub_outbox_backlog` | Is the relay keeping up? |
| `gamehub_outbox_published_total` | Is it publishing at all? |
| `gamehub_outbox_failed_total` | Are publishes failing? |
| `gamehub_consumer_events_total{result}` | How many deliveries are duplicates? |
| `kafka_consumer_fetch_manager_records_lag_max` | Are consumers behind? |

Two alerts distinguish the cases that look alike:

- **Backlog growing** — the relay is losing to the write rate.
- **Backlog non-zero AND publish rate zero** — the relay is *stuck*. A
  different problem needing a different response.

A separate job logs at ERROR when rows have been unpublished for over two
minutes, which no throughput number explains.

## Retention

| Store | Retention | Why |
|---|---|---|
| Kafka topics | 7 days | Enough to replay a weekend outage |
| Kafka DLT | 30 days | A person has to look at these |
| `processed_events` | Swept past topic retention | Once a record can no longer be redelivered, remembering it costs storage for nothing |
| `game_events` | Kept | Doubles as an audit log and a per-player replay source |

## Limitations

- **No schema registry.** Contracts are enforced by a shared sealed interface,
  which works because producer and consumer are the same deployable. A genuine
  microservice split would need Avro or Protobuf with a registry.
- **No consumer-side ordering across partitions.** By design.
- **The DLT has no tooling.** Reprocessing is manual.
- **No end-to-end tracing through Kafka.** `observation-enabled: true` is set,
  but propagation across the outbox boundary is not implemented, so a trace
  ends at the commit and a new one starts in the consumer.
