# ADR-003: Redis sorted sets for leaderboards, Postgres as source of truth

**Status:** Accepted · **Date:** 2026-09-21

## Context

Leaderboards need two queries: the top N, and "what rank am I?".

The second is the hard one. In SQL, rank means counting every better row:
O(number of better players). Cheap at rank 5, ruinous at rank 400,000, and
slowest for exactly the players who are most numerous. Window functions do not
help — `RANK()` materialises and numbers the whole leaderboard to answer a
question about one row.

## Decision

Redis sorted sets as the **serving layer**, PostgreSQL as the **source of
truth**. Writes go to Postgres first, then Redis. Every read falls back to
Postgres.

Score and achievement time are packed into one double so ties are resolved
deterministically:

```
encoded = score · 2²⁹ + (TIME_MAX − secondsSinceEpoch)
```

## Alternatives

**Postgres only.** Simplest, one fewer component. Rejected on the rank query
above. A materialised view refreshed periodically was considered and rejected
too: refresh is O(n) and the staleness window is visible to players who just
scored.

**Redis as source of truth.** Removes the dual write entirely. Rejected
because Redis persistence is weaker than Postgres, and a leaderboard is player
achievement data that must not be lost to an eviction or a failover.

**A dedicated ranking service.** Over-engineering for one query.

**Denormalised `rank` column, updated on write.** Rejected because one score
change shifts the rank of everyone below it, making a single write O(n).

## Consequences

**Good.** Rank is O(log N) at every depth, and paging by index does not scan.
Because Postgres holds the truth, Redis can run with `allkeys-lru` and losing
it costs latency rather than data — which is also what makes a plain Redis
lease acceptable elsewhere in the system. `ZADD GT` makes writes idempotent
and order-independent, so at-least-once delivery cannot demote a player.

**Bad.** A dual write, which is a real cost: the ordering (Postgres first) is
load-bearing and easy to get wrong. Leaderboards occupy memory. Redis is on
the read path, so every method needs a fallback and the fallbacks need
testing. The bit-packing imposes hard limits — score ≤ 1,048,575 and ~17 years
from a fixed epoch — and widening one narrows the other.

**Accepted.** The codec **rejects** out-of-range input rather than wrapping. A
wrapped value corrupts a ranking silently, and a corrupted ranking is far more
expensive to notice than a rejected write.

Sub-second ties still fall back to member ordering. Deliberate: ordering two
independent gameplay clients within one second is not information we have, so
inventing a winner would be false precision.
