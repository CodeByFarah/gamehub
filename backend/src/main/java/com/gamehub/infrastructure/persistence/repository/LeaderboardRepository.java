package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.LeaderboardEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeaderboardRepository extends JpaRepository<LeaderboardEntity, UUID> {

    /**
     * Lookup by Redis key.
     *
     * <p>The only finder the service needs. Since the key is derived from
     * (scope, game, region, period) by one function and is unique, it is a
     * complete natural identifier, and looking a board up by its parts as
     * well would be a second way to spell the same query.
     */
    Optional<LeaderboardEntity> findByRedisKey(String redisKey);
}
