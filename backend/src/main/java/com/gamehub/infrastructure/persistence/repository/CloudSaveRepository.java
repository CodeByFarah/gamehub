package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.CloudSaveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CloudSaveRepository extends JpaRepository<CloudSaveEntity, UUID> {

    Optional<CloudSaveEntity> findByUserIdAndGameIdAndSlot(UUID userId, UUID gameId, short slot);

    List<CloudSaveEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    /**
     * The compare-and-swap at the heart of cloud saves.
     *
     * <h2>What it prevents</h2>
     * Two devices both hold version 17. Both send an update. Without the
     * version predicate, both UPDATEs succeed and the second silently destroys
     * the first write: the player loses progress and nothing anywhere reports
     * an error.
     *
     * <p>With the predicate, the first UPDATE matches and bumps the row to 18.
     * The second no longer matches any row and affects zero. The service reads
     * that zero and returns 409 with the server current version and payload,
     * so the client can merge instead of guessing.
     *
     * <h2>Why this and not SELECT FOR UPDATE</h2>
     * Pessimistic locking would also be correct, but it holds a row lock for
     * the whole request including the network round trip to the client.
     * Conflicts here are rare, which is exactly the condition under which
     * optimistic control wins: the common path takes no lock at all, and the
     * rare loser pays with a retry rather than everyone paying with a wait.
     *
     * <p>The statement is atomic in Postgres regardless of isolation level,
     * because an UPDATE re-evaluates its predicate against the latest
     * committed row version.
     *
     * @return 1 if this writer held the current version, 0 if it lost the race
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE cloud_saves
               SET version = version + 1,
                   payload = :payload,
                   payload_size_bytes = :payloadSize,
                   checksum = :checksum,
                   device_id = :deviceId,
                   updated_at = now()
             WHERE id = :id
               AND version = :expectedVersion
            """, nativeQuery = true)
    int updateIfVersionMatches(@Param("id") UUID id,
                               @Param("expectedVersion") long expectedVersion,
                               @Param("payload") byte[] payload,
                               @Param("payloadSize") int payloadSize,
                               @Param("checksum") String checksum,
                               @Param("deviceId") String deviceId);
}
