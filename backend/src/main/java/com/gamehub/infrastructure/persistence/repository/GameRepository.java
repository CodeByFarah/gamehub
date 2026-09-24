package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.GameEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    Optional<GameEntity> findBySlug(String slug);

    Page<GameEntity> findByStatusOrderByPopularityScoreDesc(
            GameEntity.GameStatus status, Pageable pageable);

    /**
     * Full-text search over the generated tsvector.
     *
     * <h2>Why websearch_to_tsquery</h2>
     * plainto_tsquery ANDs every term, so a three-word query usually returns
     * nothing. to_tsquery requires the caller to build operator syntax and
     * throws on malformed input, which means user text has to be sanitised
     * first and a mistake there is a denial-of-service.
     * websearch_to_tsquery accepts free-form input, supports quoted phrases
     * and negation, and never throws on bad syntax. It is the only one of the
     * three safe to hand raw user input.
     *
     * <h2>Complexity</h2>
     * GIN index lookup on the posting list, then ts_rank_cd over only the
     * matched rows. Roughly O(log n + m) for m matches, against O(n) for a
     * sequential scan that would recompute to_tsvector per row.
     *
     * <p>ts_rank_cd rather than ts_rank because it accounts for term proximity:
     * a game whose title contains both query words adjacently outranks one
     * that mentions them in unrelated sentences. Ties broken by popularity so
     * results are stable and never arbitrary.
     */
    @Query(value = """
            SELECT * FROM games
             WHERE status = 'PUBLISHED'
               AND search_vector @@ websearch_to_tsquery('english', :query)
             ORDER BY ts_rank_cd(search_vector, websearch_to_tsquery('english', :query)) DESC,
                      popularity_score DESC
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<GameEntity> search(@Param("query") String query,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    /**
     * Fuzzy fallback for misspellings, used only when full-text returns too
     * few rows. Trigram similarity, because a tsvector has no notion of edit
     * distance and cannot match "stelar" to "Stellar".
     *
     * <p>The 0.3 floor is the pg_trgm default. Lower values start returning
     * unrelated titles, which is worse than returning nothing.
     */
    @Query(value = """
            SELECT * FROM games
             WHERE status = 'PUBLISHED'
               AND similarity(title, :query) > 0.3
             ORDER BY similarity(title, :query) DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<GameEntity> searchFuzzy(@Param("query") String query, @Param("limit") int limit);

    /**
     * Structured filtering, used by the Discover facets and by the AI search
     * endpoint once a natural-language prompt has been turned into parameters.
     *
     * <p>Every filter is null-tolerant, so one query serves all combinations
     * rather than needing a Criteria builder. The cast on the tags array is
     * required because a null parameter has no inferable type in Postgres.
     */
    @Query(value = """
            SELECT * FROM games
             WHERE status = 'PUBLISHED'
               AND (:genre IS NULL OR genre = :genre)
               AND (:multiplayer IS NULL OR supports_multiplayer = :multiplayer)
               AND (:maxSessionMinutes IS NULL OR avg_session_minutes <= :maxSessionMinutes)
               AND (:minRating IS NULL OR rating_avg >= :minRating)
               AND (CAST(:tags AS text[]) IS NULL OR tags && CAST(:tags AS text[]))
             ORDER BY popularity_score DESC
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<GameEntity> filter(@Param("genre") String genre,
                            @Param("multiplayer") Boolean multiplayer,
                            @Param("maxSessionMinutes") Integer maxSessionMinutes,
                            @Param("minRating") Double minRating,
                            @Param("tags") String[] tags,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    /**
     * Bumps denormalised popularity.
     *
     * <p>An atomic increment rather than read-modify-write, because many
     * sessions for one popular game are processed concurrently and a
     * load-then-save would lose increments under exactly the load that makes
     * the number interesting.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE GameEntity g
               SET g.popularityScore = g.popularityScore + :delta,
                   g.updatedAt = CURRENT_TIMESTAMP
             WHERE g.id = :gameId
            """)
    int incrementPopularity(@Param("gameId") UUID gameId, @Param("delta") int delta);

    List<GameEntity> findByIdInAndStatus(List<UUID> ids, GameEntity.GameStatus status);
}
