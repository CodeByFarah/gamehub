# Redis strategy

Redis is used for four distinct jobs, and it is worth separating them because
they have different failure semantics.

| Use | What it is | If Redis is lost |
|---|---|---|
| Leaderboard ZSETs | A **projection**, rebuilt from Postgres | Rebuilt on next miss |
| Catalogue cache | A cache | Recomputed |
| Distributed leases | Mutual-exclusion hint | Jobs skip a cycle |
| Rate limiting | Counter | Limiting stops; requests still served |

**Nothing in Redis is a source of truth.** That single property is what makes
`maxmemory-policy allkeys-lru` safe: every key is evictable, and eviction
costs latency rather than data.

## Every key family

| Key pattern | TTL | Written by | Invalidated by |
|---|---|---|---|
| `cache:games:page:{page}:{size}` | 1m | Catalogue browse | Prefix evict on any game write |
| `cache:games:detail:{id}` | 15m | Game detail | Key evict on that game write |
| `lb:global:{period}` | 2d | Leaderboard submit | Never; updated in place by ZADD |
| `lb:region:{region}:{period}` | 2d | Leaderboard submit | Never |
| `lb:game:{gameId}:{period}` | 2d | Leaderboard submit | Never |
| `lease:outbox-relay` | 10s | Outbox relay | Self-releasing Lua script |
| `lease:matchmaker:{gameId}:{region}` | 10s | Matchmaker tick | Self-releasing Lua script |
| `lease:matchmaker-expiry` | 10s | Expiry sweep | Self-releasing |
| `ratelimit:{principal}` | 1m | Rate limiter | Fixed window expiry |

All TTLs live in one place, `CacheProperties`, because TTLs scattered across
the services that write each key are impossible to reason about as a whole,
and "what is cached, and for how long?" is the first question asked when a
stale read is reported.

## Why each TTL is what it is

**Catalogue pages, 1 minute.** The catalogue is written by admins at
unpredictable times. A newly published game appearing up to a minute late is
an acceptable trade for absorbing the read volume of the Discover screen.
Writes evict eagerly as well, so the TTL is only the backstop for a write that
happened on another instance.

**Game detail, 15 minutes.** Read far more often than it changes, and
invalidated by key on every write.

**Leaderboards, 2 days.** Not really a cache: a projection updated
incrementally by `ZADD` as scores arrive. The TTL exists so an abandoned
leaderboard cannot occupy memory forever, not to force a refresh.

**Leases, 10 seconds.** Must exceed the worst-case job duration or a second
instance starts while the first is still running. This is the one duration
here that is correctness-adjacent rather than a performance knob.

## Invalidation

Two mechanisms, used together:

- **Eager eviction on write.** A game write evicts its detail key and every
  catalogue page, because a popularity or status change reorders pages the
  changed game does not even appear on.
- **TTL as backstop.** Covers writes that happened on another instance.

Prefix eviction uses `SCAN`, never `KEYS`. `KEYS` blocks the single Redis
thread for a full keyspace walk, stalling every other client.

## Why a wrapper instead of @Cacheable

`CacheStore` exists rather than Spring annotations because `@Cacheable` hides
two things this system needs to see:

1. **No hit-rate signal** without extra plumbing. A cache whose hit rate
   nobody measures is a cache nobody can justify keeping.
2. **It propagates backend failures by default**, turning a Redis blip into a
   user-visible outage for data Postgres could have served perfectly well.

Every call here records hit, miss or error, and every failure path falls
through to the loader. The caller cannot tell the difference except through
the metrics, which is exactly the intent.

## Cache stampede is not guarded

On a miss, concurrent callers all run the loader.

A conscious trade: the cached items are single-row lookups and short catalogue
pages, so a handful of duplicate queries costs little. The usual fix, a
per-key distributed lock, adds a lock acquisition to the read path of every
cached endpoint — a much larger cost than the problem.

Recorded here rather than left implicit, so it is revisitable if a genuinely
expensive item is ever cached.

## Serialisation

Keys are plain strings, not JDK-serialised. Binary keys are unreadable in
`redis-cli`, which turns every cache investigation into a guessing game, and
they make the key format an accident of Java rather than something designed.

Values are JSON. JDK serialisation couples the cache to exact class shapes, so
a field added to a DTO makes every cached entry undeserialisable, and it is a
well-known remote code execution vector if anything untrusted can ever write
to Redis.

## Client

Lettuce, the Spring Boot default, rather than Jedis. It is netty-based and its
connections are thread-safe, so one shared connection serves every request
thread instead of a pool per instance.

Timeouts are 500ms for both connect and read. Deliberately short: every Redis
read has a Postgres fallback, so waiting on a slow Redis is strictly worse
than giving up and reading the source of truth.
