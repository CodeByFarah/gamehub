package com.gamehub.api.dto;

import java.time.Instant;
import java.util.UUID;

public record LeaderboardEntryResponse(
        long rank,
        UUID userId,
        String displayName,
        String avatarUrl,
        String region,
        long score,
        Instant achievedAt) {
}
