# ADR-002: Transactional outbox instead of direct publishing

**Status:** Accepted · **Date:** 2026-09-21

## Context

Completing a game must both update the database and publish an event that
three consumers react to. There is no transaction spanning Postgres and Kafka.

Both naive orderings are wrong:

- **Publish then commit** — the transaction rolls back and consumers award
  achievements for a game that never finished.
- **Commit then publish** — the process dies in between and the event is lost
  forever, silently.

This is not a coding-care problem. It is a property of writing to two systems
that cannot agree on a commit.

## Decision

Insert the event into a `game_events` table **in the same transaction** as the
state change. A relay polls unpublished rows with
`FOR UPDATE SKIP LOCKED` and publishes them after commit.

Delivery is **at-least-once**. Consumers deduplicate through a
`processed_events` ledger keyed by `(event_id, consumer_group)`.

## Alternatives

**Direct publishing.** Rejected as above.

**Kafka transactions spanning the database write.** Genuinely gives
exactly-once. Rejected because it requires a transaction coordinator, careful
producer/consumer configuration, and a much larger blast radius when it
misbehaves — to remove a problem the consumers already solve with one atomic
insert.

**Debezium / CDC.** Reads the write-ahead log and publishes changes. Powerful,
and it removes the polling relay entirely. Rejected because it publishes *row
changes*, not *domain events*: consumers would receive "a row in game_sessions
changed" and have to reconstruct intent. It also adds Kafka Connect as a
component to operate.

**Two-phase commit.** Kafka does not support XA. Not an option.

## Consequences

**Good.** No event can exist for a transaction that rolled back, and none can
be lost after one committed. `SKIP LOCKED` lets the relay run on every
instance with no leader election: each takes a disjoint batch and none of them
waits. `game_events` doubles as an audit log and a per-player replay source.
Because the transport is behind the relay, swapping Kafka for Pub/Sub changes
the relay and nothing else.

**Bad.** Events are delayed by up to one poll interval (500ms), which is the
floor on how stale derived state can be. The relay is extra machinery to
operate and monitor. `game_events` grows without bound and has no retention
policy yet. Every consumer must be idempotent, which is a real constraint on
how they are written.

**Traps found.** `OutboxBatchPublisher` had to be a separate bean from
`OutboxRelay`: Spring transactions are proxy-based, so calling a
`@Transactional` method as `this::drainBatch` never crosses the proxy and
would have run with no transaction — releasing the `SKIP LOCKED` row locks
immediately and duplicating every event, while single-instance tests passed.
