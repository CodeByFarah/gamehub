package com.gamehub.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class SessionDtos {

    private SessionDtos() {
    }

    public record StartSessionRequest(
            @NotNull(message = "gameId is required") UUID gameId,
            UUID matchId,
            @Size(max = 32) String clientVersion) {
    }

    /**
     * @param score upper bound matches the leaderboard codec range. Rejecting
     *              here rather than at encode time means the client gets a 400
     *              naming the field instead of a 500 from deep inside the
     *              leaderboard write path.
     * @param perfect whether this met the perfect-match criteria. Client
     *                asserted, and treated as a hint: the achievement consumer
     *                re-derives it from score and outcome, so a lying client
     *                cannot award itself an achievement.
     */
    public record CompleteSessionRequest(
            @Min(value = 0, message = "score must not be negative")
            @Max(value = 1_048_575, message = "score exceeds the leaderboard representable range")
            int score,
            @NotNull(message = "outcome is required") String outcome,
            boolean perfect) {
    }

    public record SessionResponse(
            UUID id,
            UUID gameId,
            UUID matchId,
            Instant startedAt,
            Instant endedAt,
            Integer durationSeconds,
            int score,
            String outcome) {
    }
}
