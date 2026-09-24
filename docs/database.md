# Database

PostgreSQL 16. Not "a relational database" — `SKIP LOCKED`, `ON CONFLICT`,
partial unique indexes and `websearch_to_tsquery` are all load-bearing, and
swapping engines would mean redesigning four subsystems.

## Conventions

**UUID primary keys.** IDs are minted by the API and by Kafka consumers across
several instances at once; a central sequence would be a needless coordination
point. The cost is index locality, accepted because no table here is
range-scanned by primary key.

**`TIMESTAMPTZ` everywhere, never `TIMESTAMP`.** Players are global and the
backend runs in UTC. Storing wall-clock without an offset is the single most
common source of leaderboard "ties" that are not really ties.

**Every index carries a comment naming the query it serves.** An index with no
named query is write amplification waiting to happen.

## Schema

```
users ──1:1── user_profiles
  │                │
  │                └── matchmaking_queue ──> matches ──< match_participants
  │
  ├──< game_sessions >── games
  ├──< user_achievements >── achievements
  ├──< leaderboard_entries >── leaderboards
  ├──< cloud_saves ──< cloud_save_history
  └──< recommendations >── games

  game_events       (transactional outbox)
  processed_events  (consumer idempotency ledger)
```

### Why users and profiles are separate

`users` holds the password hash and is read on token refresh. `user_profiles`
is read on nearly every gameplay request. Keeping the hash out of the hot read
path means a profile query cannot accidentally serialise a credential into a
DTO.

They share a primary key rather than having an association, so there is no
lazy-loading hazard with `open-in-view` disabled.

## The indexes that matter

### `uq_mmq_one_active_ticket_per_user`

```sql
CREATE UNIQUE INDEX uq_mmq_one_active_ticket_per_user
    ON matchmaking_queue (user_id) WHERE status = 'WAITING';
```

The single most important line in the schema. It makes "a player cannot be
matched twice" a database invariant rather than a hopeful application check.

**Partial** is essential: a plain unique index on `user_id` would let a player
queue exactly once, ever.

### `idx_game_events_unpublished`

```sql
CREATE INDEX idx_game_events_unpublished ON game_events (occurred_at)
    WHERE published_at IS NULL;
```

Partial over the unpublished tail. Once a row is published it **drops out of
the index**, so the relay's scan cost stays proportional to the backlog rather
than to total event history — which grows forever.

### `idx_games_popularity`

```sql
CREATE INDEX idx_games_popularity ON games (popularity_score DESC, id)
    WHERE status = 'PUBLISHED';
```

Partial (live rows only) and DESC-ordered, so the default catalogue listing is
satisfied by an index scan with **no sort node** above it.

### `idx_leaderboard_entries_rank`

```sql
CREATE INDEX idx_leaderboard_entries_rank
    ON leaderboard_entries (leaderboard_id, score DESC, achieved_at ASC);
```

Matches the rebuild ordering exactly, turning a cold-cache rebuild into a
single index scan.

### The two partial unique indexes on achievements

```sql
CREATE UNIQUE INDEX uq_achievements_game_code ON achievements (game_id, code)
    WHERE game_id IS NOT NULL;
CREATE UNIQUE INDEX uq_achievements_global_code ON achievements (code)
    WHERE game_id IS NULL;
```

A plain `UNIQUE (game_id, code)` is **not enough**. Postgres treats NULLs as
distinct, so two platform-wide achievements could both claim `FIRST_GAME`.

### `idx_sessions_open`

```sql
CREATE INDEX idx_sessions_open ON game_sessions (user_id, game_id)
    WHERE ended_at IS NULL;
```

`game_sessions` is the highest-volume table. Partial over open sessions only,
typically a tiny fraction of rows, so this index stays small no matter how
large the table grows.

## Constraints that catch bugs

Constraints here are not decoration. Several exist specifically to make a
consumer bug fail loudly.

| Constraint | Catches |
|---|---|
| `ck_profiles_counts` (`games_won <= games_played`) | A duplicate `GameCompleted` that incremented wins but not plays |
| `ck_sessions_closed` | A half-written row from a crashed consumer: a session is either fully open or fully closed |
| `ck_cloud_saves_size_matches` | Stored size disagreeing with `octet_length(payload)` |
| `ck_leaderboards_shape` | A scope whose discriminators disagree, which would make the Redis key a lie |
| `ck_mmq_matched` | A MATCHED ticket with no match id |
| `uq_users_username` on `CITEXT` | "Player1" and "player1" both existing. An application-level lowercase check races; this does not |

## Generated columns

```sql
search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(genre, '')), 'B') || ...
) STORED
```

Generated, so the search index can never drift from the source text. Weighted,
so a title match outranks a description match.

Deliberately **not mapped** in JPA: Hibernate would try to write it and fail,
and reading it into Java serves no purpose because only the database evaluates
it.

## Migrations

Flyway owns the schema. Hibernate is `ddl-auto: validate` and never emits DDL,
so entity drift is a **startup failure** rather than a runtime error on a
query nobody exercised in testing.

`validate-on-migrate: true` fails on a checksum mismatch rather than
repairing. An edited migration that already ran is a code-review problem, not
something a booting instance should paper over.

| Migration | Contents |
|---|---|
| `V1__baseline_schema.sql` | 16 tables, constraints, indexes |
| `V2__seed_catalogue.sql` | 10 games, 10 achievements, the global leaderboard |

Seed data is a migration, not a script, so every environment starts from the
same catalogue and tests can assert against known ids. It includes one
`DELISTED` game specifically so tests can prove status filtering works — a
seed set of only published rows would let a missing filter pass every test.

## Concurrency patterns

| Pattern | Used by |
|---|---|
| `FOR UPDATE SKIP LOCKED` | Outbox relay: disjoint batches, no blocking, no leader |
| `ON CONFLICT DO NOTHING` | Idempotency ledger, achievement unlocks |
| `ON CONFLICT DO UPDATE ... WHERE` | Leaderboard upsert, monotonic so replays cannot demote |
| Conditional `UPDATE ... WHERE version = ?` | Cloud save compare-and-swap |
| Partial unique index | One active matchmaking ticket |
| Atomic `UPDATE x = x + 1` | Statistics and popularity, so concurrent consumers cannot lose increments |

Every one of these replaces a read-then-write that would race.

## Connection pooling

Hikari, 20 per instance, against Postgres `max_connections = 200`.

A pool larger than the database can serve does not make the system faster — it
moves the queue out of the application, where it is visible and measurable,
and into the database, where it is neither. This is also the real horizontal
scaling ceiling: roughly 5–8 instances.

`max-lifetime` is 10 minutes, below the typical cloud idle-connection reaper,
so the pool discards connections before the network silently drops them.

## Gaps

- **No partitioning.** `game_sessions` and `game_events` grow without bound
  and would need range partitioning by month at real volume.
- **No retention policy** on `cloud_save_history` or `game_events`.
- **No read replicas.** Every read hits the primary.
- **`processed_events` is swept but never partitioned.**
