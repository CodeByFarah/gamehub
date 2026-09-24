-- ===========================================================================
-- V2: catalogue and achievement seed data.
--
-- Committed as a migration rather than loaded by a script, so every
-- environment (a developer laptop, CI, a fresh cloud deployment) starts from
-- the same catalogue. Tests assert against these rows, so changing them is a
-- deliberate act with visible consequences rather than an accident.
--
-- All content is invented. No real game, studio or trademark appears here.
--
-- Deterministic UUIDs throughout. Random ids would mean a test could not
-- reference a specific game without querying for it first, and the
-- achievement rows below could not point at their games.
-- ===========================================================================

INSERT INTO games (
    id, slug, title, short_description, description, genre, tags,
    min_players, max_players, avg_session_minutes,
    supports_multiplayer, supports_cloud_save,
    rating_avg, rating_count, popularity_score, released_at, status
) VALUES

('a0000000-0000-4000-8000-000000000001', 'stellar-drift',
 'Stellar Drift',
 'Zero-gravity racing through collapsing asteroid fields.',
 'A momentum-based racer with no brakes. Every course is a single unbroken line through debris that shifts each run, so memorising a track buys you less than learning to read one.',
 'Racing', ARRAY['competitive','fast-paced','skill-based','multiplayer'],
 1, 8, 12, true, true, 4.60, 18420, 9800, DATE '2025-03-14', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000002', 'tide-and-tessera',
 'Tide and Tessera',
 'A tile-laying puzzle where the board drains and refills.',
 'Place tiles to channel water through a shifting grid. The tide rises on a fixed schedule regardless of how ready you are, which turns a calm puzzle into a planning problem.',
 'Puzzle', ARRAY['relaxing','casual','single-player','family-friendly'],
 1, 1, 18, false, true, 4.40, 9120, 6400, DATE '2024-11-02', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000003', 'ironroot-siege',
 'Ironroot Siege',
 'Asymmetric siege warfare for two to four commanders.',
 'One player defends a fortress, the others besiege it. The defender sees everything and has too few resources; the attackers see almost nothing and have too many options.',
 'Strategy', ARRAY['competitive','cooperative','tournament','multiplayer'],
 2, 4, 45, true, true, 4.70, 24310, 11200, DATE '2025-06-20', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000004', 'paper-lantern',
 'Paper Lantern',
 'A quiet narrative walk through a town that remembers you.',
 'No combat, no failure state. Townspeople recall what you told them hours earlier, and the ending is assembled from those small accumulated choices rather than a final decision.',
 'Adventure', ARRAY['story-rich','relaxing','single-player'],
 1, 1, 90, false, true, 4.80, 15670, 7300, DATE '2025-01-30', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000005', 'circuit-breakers',
 'Circuit Breakers',
 'Head-to-head reflex duels in sixty-second rounds.',
 'Two players, one board, one minute. Rounds are short enough that a loss costs nothing and long enough that luck does not decide them.',
 'Arcade', ARRAY['competitive','fast-paced','casual','multiplayer','skill-based'],
 2, 2, 5, true, false, 4.20, 31200, 12600, DATE '2024-08-11', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000006', 'harvest-protocol',
 'Harvest Protocol',
 'Build and automate a farm on a world with a nine-day year.',
 'Seasons turn over fast enough that a full crop cycle spans several of them. Automation is not an optimisation here, it is the only way to finish anything you start.',
 'Simulation', ARRAY['relaxing','sandbox','single-player','cooperative'],
 1, 4, 60, true, true, 4.50, 20140, 8900, DATE '2025-05-08', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000007', 'null-sector',
 'Null Sector',
 'A roguelike descent where the map edits itself behind you.',
 'Rooms you leave are rewritten. Backtracking is possible but never returns you to the place you left, so route planning is about what you are willing to lose.',
 'RPG', ARRAY['roguelike','skill-based','story-rich','single-player'],
 1, 1, 35, false, true, 4.30, 11890, 5700, DATE '2025-09-01', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000008', 'court-of-cards',
 'Court of Cards',
 'A bluffing card game for three to six players.',
 'Every card is public except the one that matters. Winning depends less on the hand you hold than on what the table believes you hold.',
 'Card', ARRAY['competitive','casual','multiplayer','family-friendly','tournament'],
 3, 6, 25, true, false, 4.10, 7450, 4200, DATE '2024-04-19', 'PUBLISHED'),

('a0000000-0000-4000-8000-000000000009', 'deadlift-league',
 'Deadlift League',
 'Rhythm-timed strength sports with online ladders.',
 'A sports game about timing rather than button mashing. The ladder resets monthly, so a good season is worth defending.',
 'Sports', ARRAY['competitive','skill-based','tournament','multiplayer'],
 1, 2, 15, true, false, 3.90, 5230, 3100, DATE '2025-07-25', 'PUBLISHED'),

('a0000000-0000-4000-8000-00000000000a', 'glass-cathedral',
 'Glass Cathedral',
 'First-person traversal through impossible architecture.',
 'A momentum platformer built from non-Euclidean rooms. Falling is rarely fatal, but it costs you the height you spent minutes earning.',
 'Action', ARRAY['fast-paced','skill-based','single-player','retro'],
 1, 1, 22, false, true, 4.45, 13780, 6800, DATE '2025-02-12', 'PUBLISHED'),

-- A delisted row, so tests can prove status filtering actually works. Every
-- read path filters on status = 'PUBLISHED', and a seed set containing only
-- published rows would let a missing filter pass every test.
('a0000000-0000-4000-8000-0000000000ff', 'retired-arena',
 'Retired Arena',
 'Withdrawn from the catalogue.',
 'Present only so that tests can assert delisted games are excluded.',
 'Action', ARRAY['retro'],
 1, 2, 10, true, false, 3.00, 120, 0, DATE '2023-01-01', 'DELISTED');


-- ---------------------------------------------------------------------------
-- achievements
--
-- Platform-wide rows have a NULL game_id and are covered by the partial
-- unique index on code alone. Game-specific rows are covered by the partial
-- unique index on (game_id, code), which deliberately allows the same code to
-- exist under two different games.
-- ---------------------------------------------------------------------------
INSERT INTO achievements (
    id, game_id, code, name, description, points, rarity,
    trigger_type, trigger_threshold
) VALUES

('b0000000-0000-4000-8000-000000000001', NULL, 'FIRST_GAME',
 'First Steps', 'Finish your first game on GameHub.',
 10, 'COMMON', 'GAMES_PLAYED', 1),

('b0000000-0000-4000-8000-000000000002', NULL, 'FIRST_WIN',
 'Winner', 'Win a game for the first time.',
 15, 'COMMON', 'GAMES_WON', 1),

('b0000000-0000-4000-8000-000000000003', NULL, 'TEN_WINS',
 'Contender', 'Win ten games.',
 40, 'RARE', 'GAMES_WON', 10),

('b0000000-0000-4000-8000-000000000004', NULL, 'HUNDRED_GAMES',
 'Regular', 'Play one hundred games.',
 60, 'RARE', 'GAMES_PLAYED', 100),

('b0000000-0000-4000-8000-000000000005', NULL, 'LEVEL_10',
 'Seasoned', 'Reach level ten.',
 50, 'RARE', 'LEVEL_REACHED', 10),

('b0000000-0000-4000-8000-000000000006', NULL, 'PERFECT_MATCH',
 'Flawless', 'Finish a game meeting every perfect-match condition.',
 100, 'EPIC', 'PERFECT_MATCH', 1),

('b0000000-0000-4000-8000-000000000007', NULL, 'MARATHON',
 'Marathon', 'Play a single session lasting over two hours.',
 75, 'EPIC', 'SESSION_DURATION', 7200),

('b0000000-0000-4000-8000-000000000010',
 'a0000000-0000-4000-8000-000000000001', 'DRIFT_KING',
 'Drift King', 'Score 5000 or more in a single Stellar Drift run.',
 80, 'EPIC', 'SCORE_REACHED', 5000),

('b0000000-0000-4000-8000-000000000011',
 'a0000000-0000-4000-8000-000000000003', 'SIEGE_MASTER',
 'Siege Master', 'Win twenty five games of Ironroot Siege.',
 120, 'LEGENDARY', 'GAMES_WON', 25),

('b0000000-0000-4000-8000-000000000012',
 'a0000000-0000-4000-8000-000000000005', 'SIXTY_SECONDS',
 'Sixty Seconds Flat', 'Score 1000 in one round of Circuit Breakers.',
 45, 'RARE', 'SCORE_REACHED', 1000);


-- ---------------------------------------------------------------------------
-- leaderboards
--
-- Only the global board is seeded, because it is the one that always exists.
-- Per-game and per-region boards are created lazily on first score: the full
-- cross product of games and regions would be mostly empty rows.
--
-- The redis_key value must match LeaderboardEntity.buildRedisKey exactly.
-- Drift between the two would silently split a leaderboard between the key
-- being written and the key being read, and nothing would fail.
-- ---------------------------------------------------------------------------
INSERT INTO leaderboards (id, game_id, scope, region, period, metric, redis_key) VALUES
('c0000000-0000-4000-8000-000000000001', NULL, 'GLOBAL', NULL,
 'ALL_TIME', 'SCORE', 'lb:global:all_time');
