-- ===========================================================================
-- V1: GameHub baseline schema.
--
-- Conventions used throughout:
--
--   * UUID primary keys (gen_random_uuid from pgcrypto). Chosen over bigserial
--     because IDs are minted by the client-facing API and by Kafka consumers
--     on several instances at once; a central sequence would be a needless
--     coordination point. The cost is index locality, which we accept because
--     no table here is range-scanned by primary key.
--
--   * TIMESTAMPTZ everywhere, never TIMESTAMP. Players are global and the
--     backend runs in UTC. Storing wall-clock without an offset is the single
--     most common source of leaderboard "ties" that are not really ties.
--
--   * Every index below carries a comment naming the query it serves. An index
--     with no named query is write amplification waiting to happen, so there
--     are no speculative ones here.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive username / email
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- trigram fuzzy search on titles


-- ---------------------------------------------------------------------------
-- users: authentication identity only.
--
-- Deliberately split from user_profiles. This table is read on token refresh
-- and written on password change; the profile is read on nearly every gameplay
-- request. Keeping the password hash out of the hot read path means a profile
-- query cannot accidentally serialise a credential into a DTO.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username      CITEXT      NOT NULL,
    email         CITEXT      NOT NULL,
    password_hash TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    roles         TEXT[]      NOT NULL DEFAULT ARRAY['ROLE_USER'],
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username        UNIQUE (username),
    CONSTRAINT uq_users_email           UNIQUE (email),
    CONSTRAINT ck_users_status          CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT ck_users_username_format CHECK (username ~ '^[A-Za-z0-9_.-]{3,32}$')
);

COMMENT ON COLUMN users.password_hash IS
    'BCrypt hash, cost factor 12. Never plaintext, never reversible.';

COMMENT ON CONSTRAINT uq_users_username ON users IS
    'CITEXT plus UNIQUE is what actually prevents Player1 and player1 both existing. An application-level lowercase check races under concurrent signup; this does not.';


-- ---------------------------------------------------------------------------
-- user_profiles: the public, gameplay-facing identity. 1:1 with users.
-- ---------------------------------------------------------------------------
CREATE TABLE user_profiles (
    user_id                UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    display_name           TEXT        NOT NULL,
    avatar_url             TEXT,
    region                 TEXT        NOT NULL,
    -- Elo-style rating, seeded at the conventional Elo starting point.
    skill_rating           INTEGER     NOT NULL DEFAULT 1200,
    level                  INTEGER     NOT NULL DEFAULT 1,
    xp                     INTEGER     NOT NULL DEFAULT 0,
    games_played           INTEGER     NOT NULL DEFAULT 0,
    games_won              INTEGER     NOT NULL DEFAULT 0,
    total_playtime_seconds BIGINT      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_profiles_region CHECK (region IN (
        'NA_EAST', 'NA_WEST', 'EU_WEST', 'EU_CENTRAL',
        'SA_EAST', 'AP_SOUTHEAST', 'AP_NORTHEAST', 'ME_CENTRAL')),
    CONSTRAINT ck_profiles_skill  CHECK (skill_rating BETWEEN 0 AND 5000),
    CONSTRAINT ck_profiles_level  CHECK (level >= 1),
    CONSTRAINT ck_profiles_counts CHECK (games_won <= games_played AND games_played >= 0)
);

-- Serves: the Postgres fallback path for matchmaking candidate lookup, which
-- filters by region and then scans a skill window. Composite (region,
-- skill_rating) lets Postgres range-scan the window instead of filtering after
-- a sort. The hot path is the Redis ZSET queue in docs/matchmaking.md.
CREATE INDEX idx_profiles_region_skill ON user_profiles (region, skill_rating);

COMMENT ON CONSTRAINT ck_profiles_counts ON user_profiles IS
    'Guards the statistics consumer. A duplicate GameCompleted event that incremented wins but not plays violates this and fails loudly, instead of silently corrupting win rate.';


-- ---------------------------------------------------------------------------
-- games: the catalogue. Read-heavy, written rarely and only by admins.
-- ---------------------------------------------------------------------------
CREATE TABLE games (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                 TEXT         NOT NULL,
    title                TEXT         NOT NULL,
    short_description    TEXT         NOT NULL,
    description          TEXT,
    genre                TEXT         NOT NULL,
    tags                 TEXT[]       NOT NULL DEFAULT '{}',
    min_players          SMALLINT     NOT NULL DEFAULT 1,
    max_players          SMALLINT     NOT NULL DEFAULT 1,
    avg_session_minutes  SMALLINT     NOT NULL DEFAULT 15,
    supports_multiplayer BOOLEAN      NOT NULL DEFAULT false,
    supports_cloud_save  BOOLEAN      NOT NULL DEFAULT false,
    icon_url             TEXT,
    banner_url           TEXT,
    rating_avg           NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    rating_count         INTEGER      NOT NULL DEFAULT 0,
    -- Denormalised, recomputed by the statistics consumer. Kept on the row
    -- rather than computed at read time, because discovery sorts by it on
    -- every catalogue request and a COUNT over game_sessions would not hold up.
    popularity_score     INTEGER      NOT NULL DEFAULT 0,
    released_at          DATE,
    status               TEXT         NOT NULL DEFAULT 'PUBLISHED',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Generated column, so the search index can never drift from the source
    -- text. Weighted: a title match must outrank a description match.
    search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(genre, '')), 'B') ||
        setweight(to_tsvector('english', array_to_string(tags, ' ')), 'B') ||
        setweight(to_tsvector('english', coalesce(short_description, '')), 'C') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'D')
    ) STORED,

    CONSTRAINT uq_games_slug     UNIQUE (slug),
    CONSTRAINT ck_games_slug_fmt CHECK (slug ~ '^[a-z0-9-]{3,64}$'),
    CONSTRAINT ck_games_status   CHECK (status IN ('DRAFT', 'PUBLISHED', 'DELISTED')),
    CONSTRAINT ck_games_players  CHECK (min_players >= 1 AND max_players >= min_players),
    CONSTRAINT ck_games_rating   CHECK (rating_avg BETWEEN 0 AND 5 AND rating_count >= 0),
    CONSTRAINT ck_games_session  CHECK (avg_session_minutes > 0)
);

-- Serves: GET /api/games?q=... and POST /api/ai/search full-text ranking.
-- GIN over the generated tsvector gives a posting-list lookup instead of a
-- sequential scan that evaluates to_tsvector() per row.
CREATE INDEX idx_games_search_vector ON games USING GIN (search_vector);

-- Serves: fuzzy title match for typos, used only when the full-text query
-- returns too few rows. Trigram rather than full-text, because tsvector has no
-- notion of edit distance.
CREATE INDEX idx_games_title_trgm ON games USING GIN (title gin_trgm_ops);

-- Serves: the default GET /api/games listing,
--   WHERE status = 'PUBLISHED' ORDER BY popularity_score DESC
-- Partial, so the index holds only live rows, and DESC-ordered so the sort is
-- satisfied by the index scan with no sort node above it.
CREATE INDEX idx_games_popularity ON games (popularity_score DESC, id)
    WHERE status = 'PUBLISHED';

-- Serves: the genre facet on the Discover screen.
CREATE INDEX idx_games_genre ON games (genre) WHERE status = 'PUBLISHED';

-- Serves: tag filtering, tags @> ARRAY['competitive']. GIN is the only index
-- type that can answer array containment.
CREATE INDEX idx_games_tags ON games USING GIN (tags);


-- ---------------------------------------------------------------------------
-- matches / match_participants: the output of matchmaking.
-- Declared before game_sessions because a session may reference a match.
-- ---------------------------------------------------------------------------
CREATE TABLE matches (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id       UUID         NOT NULL REFERENCES games(id) ON DELETE RESTRICT,
    region        TEXT         NOT NULL,
    status        TEXT         NOT NULL DEFAULT 'CREATED',
    -- Lower is better. The raw cost the matchmaker accepted, kept so that
    -- "is match quality degrading?" is answerable from data, not anecdote.
    match_quality NUMERIC(8,4) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at    TIMESTAMPTZ,
    ended_at      TIMESTAMPTZ,

    CONSTRAINT ck_matches_status CHECK (status IN ('CREATED', 'IN_PROGRESS', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_matches_times  CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
);

-- Serves: the match-quality dashboard and the p95 match-quality metric, both
-- of which scan recent matches for one game.
CREATE INDEX idx_matches_game_created ON matches (game_id, created_at DESC);

CREATE TABLE match_participants (
    match_id              UUID     NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    user_id               UUID     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    team                  SMALLINT NOT NULL DEFAULT 0,
    -- Snapshotted, not joined. The rating changes after the match; auditing
    -- "was this match fair?" needs the rating as it was at match time.
    skill_rating_at_match INTEGER  NOT NULL,
    latency_ms_at_match   INTEGER,

    PRIMARY KEY (match_id, user_id)
);

-- Serves: recent matches on the profile screen.
CREATE INDEX idx_match_participants_user ON match_participants (user_id);


-- ---------------------------------------------------------------------------
-- game_sessions: one row per play session. Highest-volume table here.
-- ---------------------------------------------------------------------------
CREATE TABLE game_sessions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id          UUID        NOT NULL REFERENCES games(id) ON DELETE RESTRICT,
    match_id         UUID        REFERENCES matches(id) ON DELETE SET NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at         TIMESTAMPTZ,
    duration_seconds INTEGER,
    score            INTEGER     NOT NULL DEFAULT 0,
    outcome          TEXT,
    client_version   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_sessions_outcome CHECK (outcome IS NULL OR outcome IN ('WIN', 'LOSS', 'DRAW', 'ABANDONED')),
    CONSTRAINT ck_sessions_score   CHECK (score >= 0),
    CONSTRAINT ck_sessions_closed  CHECK (
        (ended_at IS NULL     AND duration_seconds IS NULL     AND outcome IS NULL) OR
        (ended_at IS NOT NULL AND duration_seconds IS NOT NULL AND outcome IS NOT NULL)
    )
);

-- Serves: GET /api/users/{id}/sessions, the profile activity feed.
-- (user_id, started_at DESC) satisfies both the filter and the ordering.
CREATE INDEX idx_sessions_user_started ON game_sessions (user_id, started_at DESC);

-- Serves: per-game session statistics and the popularity recompute job.
CREATE INDEX idx_sessions_game_started ON game_sessions (game_id, started_at DESC);

-- Serves: the resume-session lookup and the abandoned-session sweeper.
-- Partial over open sessions only, typically a tiny fraction of rows, so this
-- index stays small no matter how large the table grows.
CREATE INDEX idx_sessions_open ON game_sessions (user_id, game_id)
    WHERE ended_at IS NULL;

COMMENT ON CONSTRAINT ck_sessions_closed ON game_sessions IS
    'A session is either fully open or fully closed. Prevents the half-written row a crashed consumer would otherwise leave behind.';


-- ---------------------------------------------------------------------------
-- achievements / user_achievements
-- ---------------------------------------------------------------------------
CREATE TABLE achievements (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL game_id means a platform-wide achievement, such as FIRST_GAME.
    game_id           UUID        REFERENCES games(id) ON DELETE CASCADE,
    code              TEXT        NOT NULL,
    name              TEXT        NOT NULL,
    description       TEXT        NOT NULL,
    icon_url          TEXT,
    points            SMALLINT    NOT NULL DEFAULT 10,
    rarity            TEXT        NOT NULL DEFAULT 'COMMON',
    trigger_type      TEXT        NOT NULL,
    trigger_threshold INTEGER     NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_achievements_code    CHECK (code ~ '^[A-Z0-9_]{3,48}$'),
    CONSTRAINT ck_achievements_rarity  CHECK (rarity IN ('COMMON', 'RARE', 'EPIC', 'LEGENDARY')),
    CONSTRAINT ck_achievements_points  CHECK (points > 0),
    CONSTRAINT ck_achievements_thresh  CHECK (trigger_threshold > 0),
    CONSTRAINT ck_achievements_trigger CHECK (trigger_type IN (
        'GAMES_PLAYED', 'GAMES_WON', 'LEVEL_REACHED',
        'SCORE_REACHED', 'PERFECT_MATCH', 'SESSION_DURATION'))
);

-- A plain UNIQUE (game_id, code) is not enough: Postgres treats NULLs as
-- distinct, so two platform-wide achievements could both claim FIRST_GAME.
-- Two partial unique indexes cover both cases correctly.
CREATE UNIQUE INDEX uq_achievements_game_code ON achievements (game_id, code)
    WHERE game_id IS NOT NULL;
CREATE UNIQUE INDEX uq_achievements_global_code ON achievements (code)
    WHERE game_id IS NULL;

CREATE TABLE user_achievements (
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    achievement_id  UUID        NOT NULL REFERENCES achievements(id) ON DELETE CASCADE,
    unlocked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The Kafka event that caused the unlock. Makes the achievement pipeline
    -- auditable and lets us prove an unlock was not duplicated.
    source_event_id UUID,

    PRIMARY KEY (user_id, achievement_id)
);

COMMENT ON TABLE user_achievements IS
    'The composite primary key is the idempotency mechanism for the achievement consumer: a replayed event hits a unique violation, which the consumer swallows as a no-op. See docs/event-driven-architecture.md.';

-- Serves: the Achievements screen, most recently unlocked first.
CREATE INDEX idx_user_achievements_recent ON user_achievements (user_id, unlocked_at DESC);


-- ---------------------------------------------------------------------------
-- leaderboards / leaderboard_entries
--
-- Postgres is the durable source of truth. Redis sorted sets are the serving
-- layer and can be rebuilt from these tables at any time, which is precisely
-- why every Redis key in GameHub is safe to evict. See docs/leaderboards.md.
-- ---------------------------------------------------------------------------
CREATE TABLE leaderboards (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id    UUID        REFERENCES games(id) ON DELETE CASCADE,
    scope      TEXT        NOT NULL,
    region     TEXT,
    period     TEXT        NOT NULL DEFAULT 'ALL_TIME',
    metric     TEXT        NOT NULL DEFAULT 'SCORE',
    -- The Redis key this leaderboard projects into. Stored rather than derived,
    -- so a key-naming change is a migration instead of a silent cache split.
    redis_key  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_leaderboards_redis_key UNIQUE (redis_key),
    CONSTRAINT ck_leaderboards_scope     CHECK (scope IN ('GLOBAL', 'REGIONAL', 'GAME')),
    CONSTRAINT ck_leaderboards_period    CHECK (period IN ('ALL_TIME', 'MONTHLY', 'WEEKLY', 'DAILY')),
    CONSTRAINT ck_leaderboards_metric    CHECK (metric IN ('SCORE', 'WINS', 'PLAYTIME')),
    -- Scope and its discriminators must agree, or the Redis key is a lie.
    CONSTRAINT ck_leaderboards_shape CHECK (
        (scope = 'GLOBAL'   AND game_id IS NULL     AND region IS NULL)     OR
        (scope = 'REGIONAL' AND game_id IS NULL     AND region IS NOT NULL) OR
        (scope = 'GAME'     AND game_id IS NOT NULL)
    )
);

CREATE TABLE leaderboard_entries (
    leaderboard_id UUID        NOT NULL REFERENCES leaderboards(id) ON DELETE CASCADE,
    user_id        UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    score          BIGINT      NOT NULL,
    -- Tie-break dimension: equal score, earlier achievement wins. This column
    -- is also packed into the Redis ZSET score. docs/leaderboards.md has the
    -- bit-packing scheme that makes ties deterministic.
    achieved_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (leaderboard_id, user_id),
    CONSTRAINT ck_leaderboard_entries_score CHECK (score >= 0)
);

-- Serves: the full rebuild of a Redis ZSET after cache loss, which reads one
-- leaderboard ordered by (score DESC, achieved_at ASC) and pipelines it into
-- Redis. Matching the index to that exact ordering makes the rebuild a single
-- index scan.
CREATE INDEX idx_leaderboard_entries_rank
    ON leaderboard_entries (leaderboard_id, score DESC, achieved_at ASC);


-- ---------------------------------------------------------------------------
-- matchmaking_queue: durable mirror of the Redis matchmaking queue.
--
-- Redis holds the hot queue. This table exists so that a Redis flush does not
-- strand players, and so queue behaviour is auditable after the fact.
-- ---------------------------------------------------------------------------
CREATE TABLE matchmaking_queue (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id      UUID        NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    region       TEXT        NOT NULL,
    skill_rating INTEGER     NOT NULL,
    latency_ms   INTEGER     NOT NULL DEFAULT 0,
    status       TEXT        NOT NULL DEFAULT 'WAITING',
    enqueued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    matched_at   TIMESTAMPTZ,
    match_id     UUID        REFERENCES matches(id) ON DELETE SET NULL,

    CONSTRAINT ck_mmq_status  CHECK (status IN ('WAITING', 'MATCHED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_mmq_latency CHECK (latency_ms >= 0),
    CONSTRAINT ck_mmq_matched CHECK (
        (status =  'MATCHED' AND match_id IS NOT NULL AND matched_at IS NOT NULL) OR
        (status <> 'MATCHED' AND match_id IS NULL)
    )
);

-- The critical constraint of the matchmaking subsystem. A player may hold at
-- most one WAITING ticket across all games. This makes "the same player cannot
-- be matched twice" a database invariant rather than a hopeful application
-- check: two concurrent POST /api/matchmaking/join requests cannot both
-- succeed, regardless of how many backend instances are running. Partial, so
-- historical MATCHED and CANCELLED tickets are unconstrained.
CREATE UNIQUE INDEX uq_mmq_one_active_ticket_per_user
    ON matchmaking_queue (user_id) WHERE status = 'WAITING';

-- Serves: the matchmaker tick, which pulls the waiting pool for one
-- (game, region) bucket ordered by how long each player has waited.
CREATE INDEX idx_mmq_bucket ON matchmaking_queue (game_id, region, enqueued_at)
    WHERE status = 'WAITING';

-- Serves: the ticket-expiry sweeper.
CREATE INDEX idx_mmq_expiry ON matchmaking_queue (expires_at) WHERE status = 'WAITING';


-- ---------------------------------------------------------------------------
-- cloud_saves: optimistic concurrency control. See docs/cloud-saves.md.
-- ---------------------------------------------------------------------------
CREATE TABLE cloud_saves (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id            UUID        NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    slot               SMALLINT    NOT NULL DEFAULT 0,
    -- Monotonic per (user, game, slot). A write must present the version it
    -- believes is current. UPDATE ... WHERE version = :expected matches either
    -- one row or zero rows, and zero means 409 CONFLICT.
    version            BIGINT      NOT NULL DEFAULT 1,
    payload            BYTEA       NOT NULL,
    payload_size_bytes INTEGER     NOT NULL,
    checksum           TEXT        NOT NULL,
    device_id          TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_cloud_saves_slot         UNIQUE (user_id, game_id, slot),
    CONSTRAINT ck_cloud_saves_version      CHECK (version >= 1),
    CONSTRAINT ck_cloud_saves_slot_range   CHECK (slot BETWEEN 0 AND 9),
    -- 1 MiB ceiling, enforced here as well as in the API, because the database
    -- is the last line of defence against a client that skips validation.
    CONSTRAINT ck_cloud_saves_size         CHECK (payload_size_bytes BETWEEN 1 AND 1048576),
    CONSTRAINT ck_cloud_saves_size_matches CHECK (payload_size_bytes = octet_length(payload))
);

-- Serves: GET /api/cloud-saves for one user, the Cloud Saves screen.
CREATE INDEX idx_cloud_saves_user ON cloud_saves (user_id, updated_at DESC);

-- Append-only audit of every accepted write. Lets a player recover from a bad
-- overwrite, and lets us answer "how often do real conflicts happen?" with a
-- number rather than a guess.
CREATE TABLE cloud_save_history (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cloud_save_id UUID        NOT NULL REFERENCES cloud_saves(id) ON DELETE CASCADE,
    version       BIGINT      NOT NULL,
    payload       BYTEA       NOT NULL,
    checksum      TEXT        NOT NULL,
    device_id     TEXT,
    written_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_cloud_save_history_version UNIQUE (cloud_save_id, version)
);

-- Serves: version-history listing and the restore-version-N path.
CREATE INDEX idx_cloud_save_history_lookup
    ON cloud_save_history (cloud_save_id, version DESC);


-- ---------------------------------------------------------------------------
-- game_events: the transactional outbox.
--
-- Solves the dual-write problem. A request that both mutates state and emits a
-- Kafka event cannot do the two atomically. Publish-then-commit can publish an
-- event for a transaction that later rolls back; commit-then-publish loses the
-- event if the process dies in between. Instead the event is INSERTed in the
-- same transaction as the state change and a relay publishes it after commit.
-- See docs/event-driven-architecture.md.
-- ---------------------------------------------------------------------------
CREATE TABLE game_events (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type       TEXT        NOT NULL,
    -- Event schema version, carried as a Kafka header, so a consumer can
    -- reject or upcast a payload shape it does not understand.
    schema_version   SMALLINT    NOT NULL DEFAULT 1,
    aggregate_id     UUID        NOT NULL,
    -- Kafka partition key. Same key means same partition means ordering is
    -- preserved per user, which is all the ordering GameHub actually needs.
    partition_key    TEXT        NOT NULL,
    user_id          UUID        REFERENCES users(id) ON DELETE SET NULL,
    game_id          UUID        REFERENCES games(id) ON DELETE SET NULL,
    payload          JSONB       NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ,
    publish_attempts SMALLINT    NOT NULL DEFAULT 0,
    last_error       TEXT,

    CONSTRAINT ck_game_events_type CHECK (event_type IN (
        'GAME_STARTED', 'GAME_COMPLETED', 'ACHIEVEMENT_UNLOCKED',
        'MATCH_CREATED', 'CLOUD_SAVE_UPDATED'))
);

-- Serves: the outbox relay, which polls for unpublished rows in causal order.
-- Partial over the unpublished tail only: once a row is published it drops out
-- of the index, so the relay scan cost stays proportional to the backlog
-- rather than to total event history.
CREATE INDEX idx_game_events_unpublished ON game_events (occurred_at)
    WHERE published_at IS NULL;

-- Serves: per-user event replay and debugging.
CREATE INDEX idx_game_events_user ON game_events (user_id, occurred_at DESC);


-- ---------------------------------------------------------------------------
-- processed_events: consumer-side idempotency ledger.
--
-- Kafka gives at-least-once delivery. Every consumer records the event ids it
-- has handled, keyed by consumer group, and skips replays. Keyed by group
-- rather than globally, because the same event legitimately reaches three
-- different consumers.
-- ---------------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id       UUID        NOT NULL,
    consumer_group TEXT        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, consumer_group)
);

-- Serves: the retention sweeper that drops ledger rows older than the Kafka
-- topic retention. Without it the ledger grows without bound.
CREATE INDEX idx_processed_events_age ON processed_events (processed_at);


-- ---------------------------------------------------------------------------
-- recommendations: materialised output of the recommendation consumer.
--
-- Precomputed rather than scored at request time, because the Home screen must
-- render from one indexed lookup, not an on-the-fly scan of the catalogue.
-- ---------------------------------------------------------------------------
CREATE TABLE recommendations (
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id      UUID         NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    score        NUMERIC(8,6) NOT NULL,
    -- Human-readable justification surfaced in the UI.
    reason       TEXT         NOT NULL,
    algorithm    TEXT         NOT NULL DEFAULT 'CONTENT_BASED_V1',
    generated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (user_id, game_id),
    CONSTRAINT ck_recommendations_score CHECK (score >= 0 AND score <= 1)
);

-- Serves: GET /api/users/{id}/recommendations, top-N by score.
CREATE INDEX idx_recommendations_user_score ON recommendations (user_id, score DESC);
