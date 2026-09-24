package com.gamehub.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An achievement definition.
 *
 * <p>Achievements are data, not code. The unlock rule is expressed as a
 * triggerType plus a threshold, so adding "win 50 games" is an INSERT rather
 * than a deployment. The alternative, a class per achievement, means every new
 * achievement is a release.
 *
 * <p>A null gameId means platform-wide. The schema enforces uniqueness for
 * both cases with two partial indexes, because a plain UNIQUE (game_id, code)
 * would let two platform-wide achievements share a code: Postgres treats NULLs
 * as distinct.
 */
@Entity
@Table(name = "achievements")
@Getter
@Setter
@NoArgsConstructor
public class AchievementEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "game_id")
    private UUID gameId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "icon_url")
    private String iconUrl;

    @Column(nullable = false)
    private short points = 10;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rarity rarity = Rarity.COMMON;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private TriggerType triggerType;

    @Column(name = "trigger_threshold", nullable = false)
    private int triggerThreshold = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isPlatformWide() {
        return gameId == null;
    }

    public enum Rarity {
        COMMON, RARE, EPIC, LEGENDARY
    }

    /** Must stay in step with ck_achievements_trigger in the schema. */
    public enum TriggerType {
        GAMES_PLAYED, GAMES_WON, LEVEL_REACHED, SCORE_REACHED, PERFECT_MATCH, SESSION_DURATION
    }
}
