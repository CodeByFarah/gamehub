package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    /**
     * Lookup for login. The username column is CITEXT, so this is already
     * case-insensitive at the database level and needs no LOWER() wrapper,
     * which would also have made the unique index unusable.
     */
    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
