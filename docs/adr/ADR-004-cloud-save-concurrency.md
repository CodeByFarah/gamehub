# ADR-004: Optimistic concurrency for cloud saves

**Status:** Accepted · **Date:** 2026-09-21

## Context

A player may have GameHub open on several devices. Two devices holding the
same save version can both upload, and the naive implementation loses one
write **silently** — no error, no log, and the player simply finds progress
missing.

## Decision

Optimistic concurrency control. Every write presents the version it believes
is current:

```sql
UPDATE cloud_saves SET version = version + 1, ...
 WHERE id = :id AND version = :expectedVersion;
```

One row or zero. Zero returns **409 carrying the server version and
checksum**, so the client can merge rather than guess.

## Alternatives

**Last write wins.** The default, and it loses data invisibly. Rejected
because the player does not learn their progress was discarded until much
later, and there is no record that it happened.

**Pessimistic locking (`SELECT ... FOR UPDATE`).** Correct, but it makes every
writer pay for a conflict that almost never happens — two devices writing the
same slot within milliseconds is unusual. It also holds a row lock across the
client round trip, so a client that crashes mid-request holds it until the
transaction times out.

**CRDTs.** The right answer for collaborative editing. Rejected because a save
blob is an opaque byte array produced by a game we do not control. There is no
merge function for a player inventory, and inventing one produces a state
neither device intended. CRDTs require knowing the structure of the data, and
here we deliberately do not.

**Vector clocks.** Detects concurrency more precisely than a counter but still
cannot merge opaque bytes, so it adds complexity for the same outcome.

## Consequences

**Good.** No silent data loss. The common path takes no lock at all, which is
the right trade when conflicts are rare. The conflict is atomic at READ
COMMITTED — no isolation change, no advisory lock, no retry loop — because the
check and the write are one statement with no window between them.

`cloud_save_history` makes a bad merge recoverable and turns "how often do
conflicts happen?" into a query rather than a guess.

**Bad.** Clients must handle 409 as a **normal outcome**. A client that treats
it as a generic failure will lose progress, so this pushes real complexity
onto the client. The server cannot merge, so per-game merge logic is the
client's problem. A pathological retry loop is possible if a device is
persistently behind.

**Deliberately not using JPA `@Version`.** It would work, but it would hide
the mechanism. The version here is part of the **public API contract**,
returned to the client and presented back on the next write — not an ORM
implementation detail. Modelling it as a plain column keeps that visible and
lets the service distinguish a genuine conflict from any other persistence
failure.

**Measured by.** `gamehub_cloudsave_write_total{result}`. A conflict rate
climbing into double digits would be the signal to revisit this decision.
