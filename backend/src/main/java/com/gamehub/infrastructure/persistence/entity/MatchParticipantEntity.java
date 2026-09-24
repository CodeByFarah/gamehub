package com.gamehub.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * A player in a match.
 *
 * <p>Rating and latency are snapshotted rather than joined. Both change after
 * the match, and answering "was this match fair when it was made?" requires
 * the values as they were, not as they are now.
 */
@Entity
@Table(name = "match_participants")
@IdClass(MatchParticipantEntity.MatchParticipantId.class)
@Getter
@Setter
@NoArgsConstructor
public class MatchParticipantEntity {

    @Id
    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private short team;

    @Column(name = "skill_rating_at_match", nullable = false)
    private int skillRatingAtMatch;

    @Column(name = "latency_ms_at_match")
    private Integer latencyMsAtMatch;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class MatchParticipantId implements Serializable {
        private UUID matchId;
        private UUID userId;
    }
}
