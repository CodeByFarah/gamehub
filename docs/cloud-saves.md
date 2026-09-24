# Cloud saves

## The problem

A player has GameHub open on a phone and a tablet. Both hold version 17. Both
finish a session. Both upload.

This is a distributed-systems problem wearing a product feature as a disguise,
and the naive implementation has a data-loss bug that is completely silent.

## The race

```
  Phone                        Server                        Tablet
    |                            |                             |
    |  GET save  --------------> |                             |
    |  <---------- version 17    |                             |
    |                            | <-------------- GET save    |
    |                            |  version 17 --------------> |
    |                            |                             |
    |  PUT (v17, progress A) --> |                             |
    |                            | -- writes A, version 18     |
    |                            | <---- PUT (v17, progress B) |
    |                            | -- writes B, version 19     |
    |                            |                             |
                      progress A is gone
```

Without a version predicate both UPDATE statements succeed. The second
overwrites the first. Nothing errors, nothing logs, and the player simply
finds work missing. It is the worst class of bug: silent, data-destroying, and
impossible to reproduce on one device.

## Strategies considered

### Last write wins

What you get by default. Simple, fast, and loses data. Rejected because the
failure is invisible: the player does not learn their progress was discarded
until much later, and there is no record that it happened.

### Pessimistic locking

`SELECT ... FOR UPDATE`, holding the row lock across the client round trip.

Correct, but it makes **every** writer pay for a conflict that almost never
happens. Two devices writing the same slot within milliseconds is unusual, so
the common path would acquire and hold a lock for nothing. It also turns a
client that crashes mid-request into a held lock until the transaction times
out.

### CRDTs

Conflict-free replicated data types merge automatically with no coordination,
and are genuinely the right answer for collaborative editing.

Rejected because a save blob is an opaque array of bytes produced by a game we
do not control. There is no merge function for a player inventory, and
inventing one would produce a merged state neither device intended. CRDTs
require knowing the structure of the data, and here we deliberately do not.

### Optimistic concurrency control, chosen

Every write presents the version it believes is current. Exactly one wins; the
loser is told what it lost.

Optimistic control wins precisely when conflicts are rare, which is the case
here. The common path takes no lock at all.

## The mechanism

```sql
UPDATE cloud_saves
   SET version = version + 1,
       payload = :payload,
       checksum = :checksum,
       updated_at = now()
 WHERE id = :id
   AND version = :expectedVersion;
```

One row or zero. Zero means another device got there first.

### Why this is atomic

The check and the write are a **single statement**. Postgres re-evaluates the
predicate against the latest committed row version, so there is no window
between them for another transaction to slip through. A SELECT followed by an
UPDATE has exactly that window, and it is what makes read-then-write unsafe at
any isolation level below serializable.

This holds at READ COMMITTED, which is the default and what the application
runs at. No isolation level change, no advisory lock, no retry loop.

### Why there is no JPA @Version

JPA optimistic locking would work. It is not used because it would hide the
mechanism.

The version here is not an implementation detail of the ORM: it is part of the
**public API contract**, returned to the client and presented back on the next
write. Modelling it as a plain column updated by an explicit conditional
UPDATE keeps that visible, and lets the service distinguish a genuine version
conflict from any other persistence failure.

## The conflict response

```json
{
  "timestamp": "2026-09-22T11:04:19.482Z",
  "status": 409,
  "error": "VERSION_CONFLICT",
  "message": "cloud save has been updated by another client: expected version 17 but the server holds 18",
  "path": "/api/cloud-saves",
  "traceId": "3f2b1c9d8e7a6b5c",
  "meta": {
    "expectedVersion": 17,
    "serverVersion": 18,
    "serverChecksum": "9f86d081884c7d65"
  }
}
```

The `meta` block is the important part. A 409 that only says "conflict" forces
the client into a second round trip to find out what it lost, during which the
version can change again. Returning the current state *with* the rejection
makes the conflict recoverable rather than merely reported.

## Client resolution strategy

The server does not merge. It cannot: the payload is opaque bytes. What it
guarantees is that the client always has enough information to decide.

1. Receive 409 with `serverVersion` and `serverChecksum`.
2. GET the server payload.
3. Apply a game-specific merge, or present a choice to the player.
4. PUT again with `expectedVersion = serverVersion`.

Step 4 can conflict again if a third write landed. That is correct, and the
loop terminates because each round advances the version.

Every accepted write is snapshotted into `cloud_save_history` before being
replaced, so a bad merge is recoverable and the question "how often do
conflicts actually happen?" is a query rather than a guess.

## Two paths, failing differently

| Situation | Path | Conflict surfaces as |
|---|---|---|
| No row yet, expectedVersion 0 | INSERT | Unique index on (user_id, game_id, slot); a concurrent insert loses |
| Row exists | Conditional UPDATE | Zero rows affected |
| No row, expectedVersion not 0 | Rejected | 409, because the client believes it is updating something that does not exist |

That last case matters. Silently creating a save at a version the client
invented would mask a deleted save, or a client pointed at the wrong
environment.

## Integrity checks

The client sends a SHA-256 of the decoded payload, and the server **verifies
it** rather than trusting it. A checksum the server never checks detects
nothing at all: a client that corrupted the bytes would usually compute the
digest over the corrupted copy anyway.

It catches truncated or corrupted uploads, which would otherwise be stored as
a valid-looking save and only noticed by the player.

Size is bounded at 1 MiB in three places: the DTO, the service, and a CHECK
constraint in the schema. The database constraint is the one that matters, as
the last line of defence against any path that bypasses validation.
`ck_cloud_saves_size_matches` additionally asserts that the stored size equals
`octet_length(payload)`, so the two can never disagree.

## Verification

`CloudSaveConcurrencyTest` launches 16 threads through a `CyclicBarrier`. The
barrier matters: without it threads start staggered, the first finishes before
the last begins, the race never happens, and the test passes vacuously.

Asserted:

- Exactly **one** write is accepted. Not "at least one" and not "no
  exceptions": every other outcome is a lost write.
- The other 15 receive 409 carrying `serverVersion` and `serverChecksum`.
- The version advances by exactly one. A larger jump means a second write
  slipped through unnoticed.
- A stale writer arriving later is rejected, not silently applied.

**Status: NOT EXECUTED.** It compiles, and requires a Docker daemon which was
unavailable on the machine this was built on.

## Observability

`gamehub_cloudsave_write_total{result="accepted"|"conflict"}`

The conflict rate is the evidence for whether optimistic control is still the
right choice. A rate climbing into double digits would be the signal to
revisit, perhaps with per-field merging, or by reducing how often clients hold
a stale version.

## Limitations

- **No delta sync.** Every write ships the whole blob. Fine at 1 MiB, wrong at
  100 MiB.
- **No server-side merge**, by design.
- **History grows without bound.** A retention policy is needed before this is
  production-ready.
- **Slot count is fixed at 10** by a CHECK constraint.
