package com.gamehub.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request to enter the matchmaking queue.
 *
 * <p>Skill rating is NOT accepted from the client. It is read server-side from
 * the profile. A client-supplied rating would let anyone queue as a beginner
 * and farm real beginners, which is the single most obvious way to abuse a
 * matchmaking API.
 *
 * @param latencyMs client-measured RTT. This one IS client-supplied because
 *                  the server cannot measure it, so it is clamped to a sane
 *                  range. A dishonest value only degrades the match quality of
 *                  the player who sent it.
 */
public record MatchmakingJoinRequest(
        @NotNull(message = "gameId is required")
        UUID gameId,

        @Min(value = 0, message = "latencyMs must not be negative")
        @Max(value = 5000, message = "latencyMs above 5000 is not a playable connection")
        int latencyMs) {
}
