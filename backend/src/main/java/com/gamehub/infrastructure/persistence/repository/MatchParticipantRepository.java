package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.MatchParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MatchParticipantRepository
        extends JpaRepository<MatchParticipantEntity, MatchParticipantEntity.MatchParticipantId> {

    List<MatchParticipantEntity> findByMatchId(UUID matchId);
}
