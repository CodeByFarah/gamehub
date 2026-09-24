package com.gamehub.application.service;

import com.gamehub.api.dto.LeaderboardEntryResponse;
import com.gamehub.api.dto.LeaderboardResponse;
import com.gamehub.domain.common.Region;
import com.gamehub.infrastructure.persistence.entity.LeaderboardEntity;
import com.gamehub.domain.leaderboard.LeaderboardKey;
import com.gamehub.domain.leaderboard.LeaderboardMetric;
import com.gamehub.domain.leaderboard.LeaderboardPeriod;
import com.gamehub.domain.leaderboard.LeaderboardScope;
import com.gamehub.infrastructure.persistence.entity.LeaderboardEntryEntity;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.LeaderboardEntryRepository;
import com.gamehub.infrastructure.persistence.repository.LeaderboardRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import com.gamehub.infrastructure.redis.RedisLeaderboardStore;
import com.gamehub.infrastructure.redis.RedisLeaderboardStore.LeaderboardRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Leaderboard reads and writes across the Redis serving layer and the Postgres
 * source of truth.
 *
 * <h2>Read path</h2>
 * Redis first. On a miss, read Postgres, answer from it, and repopulate Redis
 * so the next request is fast. On a Redis failure, go straight to Postgres.
 * The caller cannot tell the difference except through {@code servedFrom} in
 * the response, surfaced deliberately so a silent degradation becomes a
 * countable one.
 *
 * <h2>Write path</h2>
 * Postgres first, then Redis. Never the reverse. Writing Redis first would
 * mean a crash between the two leaves a standing that exists only in a cache
 * which is allowed to be evicted, so the score would simply vanish. Postgres
 * first makes the worst case a stale cache, and that self-heals on the next
 * miss.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    /** Guards against a client requesting a page large enough to be a DoS. */
    private static final int MAX_PAGE_SIZE = 100;

    /** How much of a cold leaderboard is loaded back into Redis on a miss. */
    private static final int WARM_SIZE = 1_000;

    private final LeaderboardRepository leaderboards;
    private final LeaderboardEntryRepository entries;
    private final UserProfileRepository profiles;
    private final RedisLeaderboardStore redisStore;

    /**
     * Records a score against every leaderboard it belongs to.
     *
     * <p>One completed game updates three standings: the game leaderboard, the
     * player region leaderboard and the global one. Three writes rather than
     * one denormalised table, because each is read independently and a
     * combined table would need a scope filter on every query, which is
     * exactly the filter that makes an index useless.
     */
    @Transactional
    public void submitScore(UUID userId, UUID gameId, Region region,
                            long score, Instant achievedAt) {
        for (LeaderboardEntity board : boardsFor(gameId, region)) {
            // Postgres first. It is the durable record.
            int changed = entries.upsertIfBetter(board.getId(), userId, score, achievedAt);

            if (changed > 0) {
                // Only touch Redis when the standing actually moved. A no-op
                // ZADD still costs a round trip.
                redisStore.submit(board.getRedisKey(), userId, score, achievedAt);
            }
        }
    }

    @Transactional(readOnly = true)
    public LeaderboardResponse page(LeaderboardScope scope, UUID gameId, Region region, LeaderboardPeriod period,
                                    int page, int size, UUID viewerId) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;

        LeaderboardEntity board = resolveBoard(scope, gameId, region, period);

        List<LeaderboardRow> rows = redisStore.page(board.getRedisKey(), offset, safeSize);
        String servedFrom = "REDIS";

        if (rows.isEmpty() && !redisStore.exists(board.getRedisKey())) {
            // Cold cache, or Redis is down. Both are handled the same way:
            // answer from the source of truth, then try to repopulate.
            servedFrom = "POSTGRES";
            rows = readFromPostgres(board, safePage, safeSize);
            warmCache(board);
        }

        Map<UUID, UserProfileEntity> profileById = loadProfiles(rows);
        List<LeaderboardEntryResponse> items = rows.stream()
                .map(row -> toEntry(row, profileById.get(row.userId())))
                .toList();

        return new LeaderboardResponse(
                scope.name(),
                period.name(),
                gameId == null ? null : gameId.toString(),
                region == null ? null : region.name(),
                items,
                viewerId == null ? null : viewerEntry(board, viewerId),
                entries.countByLeaderboardId(board.getId()),
                servedFrom);
    }

    /**
     * The caller own standing, resolved even when it falls outside the page.
     *
     * <p>Redis ZREVRANK first, Postgres COUNT as the fallback. The fallback is
     * O(better players) rather than O(log N), which is precisely why it is the
     * fallback: correct, noticeably slower, and far better than an error.
     */
    @Transactional(readOnly = true)
    public LeaderboardEntryResponse viewerEntry(LeaderboardEntity board, UUID viewerId) {
        LeaderboardEntryEntity entry = entries
                .findById(new LeaderboardEntryEntity.LeaderboardEntryId(board.getId(), viewerId))
                .orElse(null);

        if (entry == null) {
            // Genuinely unranked, which is a different thing from Redis being
            // unable to answer. Null lets the UI say "play a game to rank".
            return null;
        }

        long rank = redisStore.rank(board.getRedisKey(), viewerId)
                .orElseGet(() -> entries.computeRank(
                        board.getId(), entry.getScore(), entry.getAchievedAt()));

        UserProfileEntity profile = profiles.findById(viewerId).orElse(null);
        return toEntry(
                new LeaderboardRow(rank, viewerId, entry.getScore(), entry.getAchievedAt()),
                profile);
    }

    private List<LeaderboardRow> readFromPostgres(LeaderboardEntity board, int page, int size) {
        // The sort matches idx_leaderboard_entries_rank exactly, so Postgres
        // walks the index instead of sorting.
        List<LeaderboardEntryEntity> rows = entries
                .findByLeaderboardIdOrderByScoreDescAchievedAtAsc(
                        board.getId(), PageRequest.of(page, size));

        long rank = (long) page * size + 1L;
        List<LeaderboardRow> result = new ArrayList<>(rows.size());
        for (LeaderboardEntryEntity row : rows) {
            result.add(new LeaderboardRow(rank++, row.getUserId(),
                    row.getScore(), row.getAchievedAt()));
        }
        return result;
    }

    /**
     * Repopulates a cold sorted set from the top slice of the durable table.
     *
     * <p>Only the top slice. A leaderboard can hold millions of rows and
     * nobody pages past the first few hundred; loading all of it would spend
     * a great deal of memory and time serving requests that never arrive.
     */
    private void warmCache(LeaderboardEntity board) {
        try {
            redisStore.rebuild(board.getRedisKey(), readFromPostgres(board, 0, WARM_SIZE));
        } catch (Exception e) {
            // Best effort. Failing to warm must not fail a read that has
            // already been answered correctly from Postgres.
            log.warn("could not warm leaderboard {}: {}", board.getRedisKey(), e.getMessage());
        }
    }

    private Map<UUID, UserProfileEntity> loadProfiles(List<LeaderboardRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        // One query for the whole page. Resolving display names per row would
        // be a textbook N+1 on the most-read endpoint in the product.
        List<UUID> ids = rows.stream().map(LeaderboardRow::userId).toList();
        return profiles.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(UserProfileEntity::getUserId, Function.identity()));
    }

    private LeaderboardEntryResponse toEntry(LeaderboardRow row, UserProfileEntity profile) {
        return new LeaderboardEntryResponse(
                row.rank(),
                row.userId(),
                // A deleted player can still hold a standing. Rendering a
                // placeholder is better than dropping the row, which would
                // make every rank below it appear to shift.
                profile == null ? "Unknown player" : profile.getDisplayName(),
                profile == null ? null : profile.getAvatarUrl(),
                profile == null ? null : profile.getRegion().name(),
                row.score(),
                row.achievedAt());
    }

    /**
     * The three boards a single score belongs to, created on first use.
     *
     * <p>Created lazily rather than seeded for every game and region
     * combination, because the full cross product is large and most of it
     * would stay empty forever.
     */
    private List<LeaderboardEntity> boardsFor(UUID gameId, Region region) {
        return List.of(
                resolveBoard(LeaderboardScope.GAME, gameId, null, LeaderboardPeriod.ALL_TIME),
                resolveBoard(LeaderboardScope.REGIONAL, null, region, LeaderboardPeriod.ALL_TIME),
                resolveBoard(LeaderboardScope.GLOBAL, null, null, LeaderboardPeriod.ALL_TIME));
    }

    private LeaderboardEntity resolveBoard(LeaderboardScope scope, UUID gameId,
                                           Region region, LeaderboardPeriod period) {
        String key = LeaderboardKey.build(scope, gameId, region, period);

        return leaderboards.findByRedisKey(key).orElseGet(() -> {
            LeaderboardEntity board = new LeaderboardEntity();
            board.setScope(scope);
            board.setGameId(gameId);
            board.setRegion(region);
            board.setPeriod(period);
            board.setMetric(LeaderboardMetric.SCORE);
            board.setRedisKey(key);
            try {
                return leaderboards.save(board);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Two instances created the same board at once. The unique
                // index on redis_key rejected the loser, so re-read the
                // winner rather than failing a perfectly valid request.
                return leaderboards.findByRedisKey(key).orElseThrow(() -> e);
            }
        });
    }
}
