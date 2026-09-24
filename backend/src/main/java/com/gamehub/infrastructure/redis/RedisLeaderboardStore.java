package com.gamehub.infrastructure.redis;

import com.gamehub.config.properties.CacheProperties;
import com.gamehub.domain.leaderboard.LeaderboardScoreCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redis sorted set projection of a leaderboard.
 *
 * <h2>Why Redis and not Postgres for this</h2>
 * Ranking is the one query in GameHub that Postgres does badly at read time.
 * Answering "what rank am I?" from a table means counting every better row,
 * which is linear in the number of players above you: cheap for rank 5,
 * ruinous for rank 400,000, and slowest exactly for the players who are most
 * numerous. A ZSET is a skip list, so ZREVRANK is O(log N) at every rank.
 *
 * <p>Costs: leaderboards live in memory, and Redis is now on the read path.
 * Both are mitigated by the same decision, that Postgres stays the source of
 * truth. Every method here degrades to a documented fallback and never
 * propagates a Redis failure to the caller.
 *
 * <h2>Operations and complexity</h2>
 * <ul>
 *   <li>submit: ZADD, O(log N)</li>
 *   <li>rank: ZREVRANK, O(log N)</li>
 *   <li>page: ZREVRANGE, O(log N + M) for M entries returned</li>
 *   <li>size: ZCARD, O(1)</li>
 *   <li>rebuild: pipelined ZADD, O(K log N) for K entries</li>
 * </ul>
 *
 * <h2>Failure behaviour</h2>
 * Every call catches {@link DataAccessException} and returns an empty result
 * rather than throwing. The caller treats empty as a cache miss and falls back
 * to Postgres. The alternative, letting the exception through, would mean a
 * Redis blip takes down the leaderboard screen for a system that still holds
 * every byte of the data it needs to answer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLeaderboardStore {

    private final StringRedisTemplate redis;
    private final CacheProperties cacheProperties;
    private final RedisScript<Long> leaderboardSubmitScript;

    /**
     * Records a score.
     *
     * <p>ZADD GT, so a lower score can never replace a higher one. That makes
     * the write idempotent and order-independent, which matters because Kafka
     * can redeliver an old score after a newer one has already landed. Without
     * GT, a redelivery would demote a player.
     *
     * <p>Executed as a Lua script because Spring Data exposes no GT flag, and
     * because it folds the ZADD and the TTL refresh into a single round trip.
     */
    public boolean submit(String key, UUID userId, long score, Instant achievedAt) {
        try {
            double encoded = LeaderboardScoreCodec.encode(score, achievedAt);
            Long changed = redis.execute(
                    leaderboardSubmitScript,
                    List.of(key),
                    Double.toString(encoded),
                    userId.toString(),
                    Long.toString(cacheProperties.getLeaderboard().toMillis()));
            return changed != null && changed > 0;
        } catch (DataAccessException e) {
            // Not fatal. Postgres already holds the authoritative standing and
            // the ZSET will be rebuilt from it on the next miss.
            log.warn("leaderboard submit to {} failed, standing remains in Postgres: {}",
                    key, e.getMessage());
            return false;
        }
    }

    /**
     * One-based rank, or empty when Redis cannot answer.
     *
     * <p>Empty means "ask Postgres", never "this player is unranked". The two
     * are different and conflating them would show rank 1 to an unranked
     * player during an outage.
     */
    public Optional<Long> rank(String key, UUID userId) {
        try {
            Long zeroBased = redis.opsForZSet().reverseRank(key, userId.toString());
            return zeroBased == null ? Optional.empty() : Optional.of(zeroBased + 1);
        } catch (DataAccessException e) {
            log.warn("leaderboard rank lookup on {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A page of standings, best first.
     *
     * <p>Offset paging is correct here even though it is usually a smell,
     * because ZREVRANGE takes index bounds directly and does not scan from the
     * start the way an SQL OFFSET does. Cost is O(log N + M) whether the
     * caller asks for rank 1 or rank 10,000.
     */
    public List<LeaderboardRow> page(String key, int offset, int limit) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeWithScores(key, offset, (long) offset + limit - 1);

            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }

            List<LeaderboardRow> rows = new ArrayList<>(tuples.size());
            long rank = offset + 1L;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() == null || tuple.getScore() == null) {
                    continue;
                }
                rows.add(new LeaderboardRow(
                        rank++,
                        UUID.fromString(tuple.getValue()),
                        LeaderboardScoreCodec.decodeScore(tuple.getScore()),
                        LeaderboardScoreCodec.decodeAchievedAt(tuple.getScore())));
            }
            return rows;
        } catch (DataAccessException e) {
            log.warn("leaderboard page on {} failed, falling back to Postgres: {}",
                    key, e.getMessage());
            return List.of();
        }
    }

    public long size(String key) {
        try {
            Long count = redis.opsForZSet().zCard(key);
            return count == null ? 0L : count;
        } catch (DataAccessException e) {
            return 0L;
        }
    }

    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (DataAccessException e) {
            return false;
        }
    }

    /**
     * Repopulates a sorted set from the durable standings.
     *
     * <p>Sent as one pipelined batch. Issued as K individual round trips, a
     * 50,000-entry rebuild would spend almost all of its time waiting on the
     * network rather than in Redis.
     */
    public void rebuild(String key, List<LeaderboardRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = rows.stream()
                    .map(row -> ZSetOperations.TypedTuple.of(
                            row.userId().toString(),
                            LeaderboardScoreCodec.encode(row.score(), row.achievedAt())))
                    .collect(java.util.stream.Collectors.toSet());

            redis.opsForZSet().add(key, tuples);
            redis.expire(key, cacheProperties.getLeaderboard());
            log.info("rebuilt leaderboard {} with {} entries", key, rows.size());
        } catch (DataAccessException e) {
            log.warn("leaderboard rebuild of {} failed: {}", key, e.getMessage());
        }
    }

    /** One standing. Rank is supplied by the caller, since Redis returns position. */
    public record LeaderboardRow(long rank, UUID userId, long score, Instant achievedAt) {
    }
}
