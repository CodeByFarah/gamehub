package com.gamehub.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public profile.
 *
 * <p>Note what is absent: email, password hash, roles and status. This type
 * exists precisely so those can never be serialised by accident, which is the
 * failure mode of returning the entity directly.
 */
public record UserProfileResponse(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        String region,
        int skillRating,
        int level,
        int xp,
        int gamesPlayed,
        int gamesWon,
        double winRate,
        long totalPlaytimeSeconds,
        Instant createdAt) {
}
