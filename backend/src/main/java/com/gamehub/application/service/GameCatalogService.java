package com.gamehub.application.service;

import com.gamehub.api.dto.GameDetailResponse;
import com.gamehub.api.dto.GameSummaryResponse;
import com.gamehub.api.dto.PageResponse;
import com.gamehub.application.exception.ResourceNotFoundException;
import com.gamehub.config.properties.CacheProperties;
import com.gamehub.infrastructure.persistence.entity.GameEntity;
import com.gamehub.infrastructure.persistence.entity.GameEntity.GameStatus;
import com.gamehub.infrastructure.persistence.repository.GameRepository;
import com.gamehub.infrastructure.redis.CacheStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Catalogue browse, search and filter.
 *
 * <h2>Search strategy</h2>
 * Two tiers. Postgres full-text first: it handles stemming, stop words and
 * phrase queries, and is served by a GIN index. If that returns too few rows,
 * top up from trigram similarity, which catches misspellings full-text cannot,
 * because a tsvector has no notion of edit distance.
 *
 * <p>Trigram is the fallback rather than the primary because it has no concept
 * of relevance beyond string similarity: it would rank a game whose title
 * merely looks like the query above one that is genuinely about it.
 *
 * <p>Complexity: full-text is a GIN posting-list lookup plus ranking over the
 * matches, roughly O(log n + m). Trigram is index-backed too but with a much
 * larger constant, a second reason to run it only when needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameCatalogService {

    private static final int MAX_PAGE_SIZE = 50;

    /** Below this many full-text hits, the fuzzy tier is also consulted. */
    private static final int FUZZY_THRESHOLD = 3;

    private static final String CACHE_PREFIX_CATALOGUE = "cache:games:page:";
    private static final String CACHE_PREFIX_DETAIL = "cache:games:detail:";

    private final GameRepository games;
    private final CacheStore cache;
    private final CacheProperties cacheProperties;

    /**
     * Default listing, ordered by popularity.
     *
     * <p>Cached, because the Discover screen is the busiest read in the
     * product while the catalogue changes only when an admin publishes
     * something. The TTL is short and writes evict eagerly, so the TTL is only
     * a backstop for a write that happened on another instance.
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public PageResponse<GameSummaryResponse> browse(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        String key = CACHE_PREFIX_CATALOGUE + safePage + ":" + safeSize;

        // Raw type plus cast: the cache round-trips through JSON and cannot
        // preserve the element type, so the cast is unavoidable rather than
        // careless. It is safe because this key family is written only here.
        return (PageResponse<GameSummaryResponse>) cache.getOrLoad(
                key, cacheProperties.getGameCatalogue(), PageResponse.class,
                () -> loadPage(safePage, safeSize));
    }

    private PageResponse<GameSummaryResponse> loadPage(int page, int size) {
        Page<GameEntity> results = games.findByStatusOrderByPopularityScoreDesc(
                GameStatus.PUBLISHED, PageRequest.of(page, size));

        return PageResponse.of(
                results.getContent().stream().map(GameCatalogService::toSummary).toList(),
                page, size, results.getTotalElements());
    }

    @Transactional(readOnly = true)
    public GameDetailResponse detail(UUID gameId) {
        return cache.getOrLoad(
                CACHE_PREFIX_DETAIL + gameId,
                cacheProperties.getGameDetail(),
                GameDetailResponse.class,
                () -> games.findById(gameId)
                        .filter(game -> game.getStatus() == GameStatus.PUBLISHED)
                        .map(GameCatalogService::toDetail)
                        .orElseThrow(() -> new ResourceNotFoundException("game", gameId)));
    }

    @Transactional(readOnly = true)
    public GameDetailResponse detailBySlug(String slug) {
        return games.findBySlug(slug)
                .filter(game -> game.getStatus() == GameStatus.PUBLISHED)
                .map(GameCatalogService::toDetail)
                .orElseThrow(() -> new ResourceNotFoundException("game", slug));
    }

    /**
     * Free-text search.
     *
     * <p>Not cached. Search terms have a long tail, so a cache would mostly
     * store entries never read again while evicting the catalogue pages that
     * genuinely benefit. This is the read that Postgres full-text indexing
     * exists to make fast on its own.
     */
    @Transactional(readOnly = true)
    public List<GameSummaryResponse> search(String query, int limit, int offset) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);

        List<GameEntity> results = new ArrayList<>(
                games.search(query.trim(), safeLimit, Math.max(offset, 0)));

        if (results.size() < FUZZY_THRESHOLD) {
            // Likely a misspelling. Top up from the trigram index, skipping
            // anything full-text has already returned.
            List<UUID> seen = results.stream().map(GameEntity::getId).toList();
            games.searchFuzzy(query.trim(), safeLimit).stream()
                    .filter(game -> !seen.contains(game.getId()))
                    .limit(Math.max(0, safeLimit - results.size()))
                    .forEach(results::add);
        }

        return results.stream().map(GameCatalogService::toSummary).toList();
    }

    /**
     * Structured filtering. Also the execution half of AI search, once a
     * natural-language prompt has been turned into parameters.
     */
    @Transactional(readOnly = true)
    public List<GameSummaryResponse> filter(String genre, Boolean multiplayer,
                                            Integer maxSessionMinutes, Double minRating,
                                            List<String> tags, int limit, int offset) {
        String[] tagArray = (tags == null || tags.isEmpty()) ? null : tags.toArray(new String[0]);

        return games.filter(genre, multiplayer, maxSessionMinutes, minRating, tagArray,
                        Math.min(Math.max(limit, 1), MAX_PAGE_SIZE), Math.max(offset, 0))
                .stream()
                .map(GameCatalogService::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GameSummaryResponse> byIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return games.findByIdInAndStatus(ids, GameStatus.PUBLISHED).stream()
                .map(GameCatalogService::toSummary)
                .toList();
    }

    /**
     * Eager invalidation on write: the detail key, and every catalogue page,
     * because a popularity or status change reorders pages the changed game
     * does not even appear on.
     */
    public void invalidate(UUID gameId) {
        cache.evict(CACHE_PREFIX_DETAIL + gameId);
        cache.evictByPrefix(CACHE_PREFIX_CATALOGUE);
    }

    static GameSummaryResponse toSummary(GameEntity game) {
        return new GameSummaryResponse(
                game.getId(), game.getSlug(), game.getTitle(), game.getShortDescription(),
                game.getGenre(), game.getTags(), game.getIconUrl(), game.getRatingAvg(),
                game.getRatingCount(), game.getPopularityScore(), game.isSupportsMultiplayer(),
                game.isSupportsCloudSave(), game.getAvgSessionMinutes());
    }

    static GameDetailResponse toDetail(GameEntity game) {
        return new GameDetailResponse(
                game.getId(), game.getSlug(), game.getTitle(), game.getShortDescription(),
                game.getDescription(), game.getGenre(), game.getTags(), game.getMinPlayers(),
                game.getMaxPlayers(), game.getAvgSessionMinutes(), game.isSupportsMultiplayer(),
                game.isSupportsCloudSave(), game.getIconUrl(), game.getBannerUrl(),
                game.getRatingAvg(), game.getRatingCount(), game.getPopularityScore(),
                game.getReleasedAt());
    }
}
