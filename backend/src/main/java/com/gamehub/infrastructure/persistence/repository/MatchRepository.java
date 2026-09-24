package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.MatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MatchRepository extends JpaRepository<MatchEntity, UUID> {
}
