package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.GameSessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GameSessionRepository extends JpaRepository<GameSessionEntity, UUID> {

    Page<GameSessionEntity> findByUserIdOrderByStartedAtDesc(UUID userId, Pageable pageable);

    /** Backed by idx_sessions_open, a partial index over unfinished sessions. */
    Optional<GameSessionEntity> findByUserIdAndGameIdAndEndedAtIsNull(UUID userId, UUID gameId);

    long countByGameId(UUID gameId);
}
