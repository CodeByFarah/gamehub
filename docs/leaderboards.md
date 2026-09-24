# Leaderboards

## The problem

Answer two questions fast, at any scale:

1. Who are the top N players?
2. What rank am I?

The second is the hard one. In SQL, rank means counting every better row:
cheap for rank 5, ruinous for rank 400,000 — and slowest for exactly the
players who are most numerous.

```sql
SELECT COUNT(*) + 1 FROM leaderboard_entries
 WHERE leaderboard_id = ? AND score > ?;
```

O(number of better players). Window functions are no better: `RANK()` has to
materialise and number the whole leaderboard to answer a question about one
row.

## The approach

**Redis sorted sets as the serving layer, Postgres as the source of truth.**

A ZSET is a skip list, so `ZREVRANK` is O(log N) at every rank.

| Operation | Redis | Complexity |
|---|---|---|
| Submit a score | `ZADD GT CH` | O(log N) |
| My rank | `ZREVRANK` | O(log N) |
| A page | `ZREVRANGE` | O(log N + M) |
| Total entries | `ZCARD` | O(1) |
| Rebuild | pipelined `ZADD` | O(K log N) |

Offset paging is correct here even though it is usually a smell: `ZREVRANGE`
takes index bounds directly and does not scan from the start the way SQL
`OFFSET` does. Rank 10,000 costs what rank 1 costs.

## Ties, and why they need solving

Redis ranks by one number. Two players on 5000 points are separated by
lexicographic member order — which here is a UUID. That is not a tie-break, it
is a coin flip, and it is not even stable: a cache rebuild re-inserts the same
members and produces the same arbitrary order for a different reason.

The product rule we actually want is: **higher score wins; on equal scores,
whoever got there first wins.**

### Bit-packing both dimensions into one double

```
encoded = score · 2²⁹ + (TIME_MAX − secondsSinceEpoch)
          \__________/   \__________________________/
           high 20 bits          low 29 bits
```

Ordering by `encoded` descending gives score descending, then
`secondsSinceEpoch` **ascending** — exactly the rule above. Inverting the time
component is what turns "earlier is better" into "numerically larger", so a
single `ZREVRANGE` answers the whole query with no post-sort.

### Why this is exact, not approximate

An IEEE-754 double represents every integer below 2⁵³ exactly. The largest
value this codec can produce is:

```
(2²⁰ − 1) · 2²⁹ + (2²⁹ − 1) = 2⁴⁹ − 1
```

Comfortably under 2⁵³. The packing is lossless, `decodeScore` recovers the
original bit for bit, and ranks never shuffle when a leaderboard is rebuilt.

`LeaderboardScoreCodecTest` asserts this at the extremes and round-trips 1000
random values.

### The budget, and why it is a hard limit

The 2⁴⁹ ceiling is the whole reason for the range limits:

| Component | Bits | Range |
|---|---|---|
| Score | 20 | 0 to 1,048,575 |
| Inverted time | 29 | ~17 years from a fixed epoch (2024-01-01) |

Widening the score means narrowing the time resolution, and vice versa. The
two budgets cannot both grow. `encode()` therefore **rejects** out-of-range
input loudly rather than letting it wrap — a wrapped value would corrupt a
ranking silently, and a corrupted ranking is far more expensive to notice than
a rejected write.

The epoch is a pinned constant, never derived from "now". If it moved, every
previously encoded score would decode to a different instant.

### Residual ties

Two players with the same score in the same one-second bucket still collide,
and Redis falls back to member ordering. Deliberate: sub-second ordering
between two independent gameplay clients is not information we actually have,
so inventing a winner from it would be false precision. The outcome is at
least deterministic and survives a rebuild, because it depends only on the
member id.

## Monotonic writes

```lua
ZADD key GT CH <encoded> <userId>
```

`GT` means a lower score can never replace a higher one. This matters because
Kafka can redeliver an old score after a newer one has landed — without `GT`,
a redelivery would **demote** a player.

It also makes the write idempotent and order-independent, which is what allows
at-least-once delivery to touch a leaderboard safely. The Postgres side is
monotonic too:

```sql
INSERT INTO leaderboard_entries (...) VALUES (...)
ON CONFLICT (leaderboard_id, user_id) DO UPDATE
   SET score = EXCLUDED.score, achieved_at = EXCLUDED.achieved_at
 WHERE leaderboard_entries.score < EXCLUDED.score;
```

The `WHERE` on the `DO UPDATE` is the point. A plain upsert would let a
replayed old score overwrite a personal best.

Spring Data exposes no `GT` flag, so the Redis write is a Lua script — which
also folds the `ZADD` and the TTL refresh into one round trip.

## Write ordering: Postgres first, always

Postgres is written, then Redis. Never the reverse.

Writing Redis first means a crash between the two leaves a standing that
exists only in a cache which is *allowed to be evicted* — the score simply
vanishes. Postgres first makes the worst case a stale cache, which self-heals
on the next miss.

Redis is only touched when the Postgres upsert actually changed something. A
no-op `ZADD` still costs a round trip.

## Failure behaviour

Every method on `RedisLeaderboardStore` catches `DataAccessException` and
returns an empty result rather than throwing. The caller treats empty as a
miss and reads Postgres.

Letting the exception through would mean a Redis blip takes down the
leaderboard screen — for a system that still holds every byte of the data it
needs to answer.

| Redis state | Behaviour |
|---|---|
| Healthy | ZSET serves everything, `servedFrom: REDIS` |
| Cold key | Read Postgres, answer, warm the top 1000 entries, `servedFrom: POSTGRES` |
| Down | Read Postgres every time. Slower, correct, no errors |
| Evicted under `allkeys-lru` | Same as cold |

One distinction that is easy to get wrong: an empty rank result means *ask
Postgres*, never *this player is unranked*. Conflating them would show rank 1
to an unranked player during an outage.

The `servedFrom` field is surfaced in the response on purpose, so a silent
degradation becomes a countable one.

### Warming only the top slice

A cold rebuild loads the top 1000 entries, not the whole board. A leaderboard
can hold millions of rows and nobody pages past the first few hundred; loading
all of it would spend a great deal of memory and time serving requests that
never arrive.

Deep pages during a cold period fall through to Postgres, which is slower and
correct.

## Three boards per score

One completed game updates the game board, the player's regional board, and
the global board.

Three writes rather than one denormalised table with a scope column: each is
read independently, and a combined table would need a scope filter on every
query — exactly the filter that makes an index useless.

Boards are created lazily on first score. The full cross product of games and
regions would be mostly empty rows. Two instances creating the same board
concurrently is handled by the unique index on `redis_key`, with the loser
re-reading the winner.

## Why the Redis key is stored, not derived

`leaderboards.redis_key` is a column. Deriving the key at read time would mean
a change to the naming scheme silently splits a leaderboard in two — old
writes in one key, new reads against another — and **nothing would fail**.

Storing it makes such a change a migration. `LeaderboardKey.build` is the
single place that knows the format, so the writer and the rebuild cannot
drift.

## N+1 avoidance

A page of 20 standings needs 20 display names. Resolving them per row would be
a textbook N+1 on the most-read endpoint in the product, so the service loads
them with one `findByUserIdIn`.

A deleted player still holds a standing; the row renders as "Unknown player"
rather than being dropped, because dropping it would make every rank below
appear to shift.
