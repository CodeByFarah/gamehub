package com.gamehub.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An achievement and whether this player has it.
 *
 * <p>Locked achievements are returned too, so the UI can show what is still
 * available. That is the whole motivational point of an achievement list.
 */
public record AchievementResponse(
        UUID id,
        String code,
        String name,
        String description,
        String iconUrl,
        int points,
        String rarity,
        UUID gameId,
        boolean unlocked,
        Instant unlockedAt) {
}
