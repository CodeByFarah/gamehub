package com.gamehub.api.controller;

import com.gamehub.api.dto.AchievementResponse;
import com.gamehub.api.dto.RecommendationResponse;
import com.gamehub.api.dto.UserProfileResponse;
import com.gamehub.api.security.CurrentUser;
import com.gamehub.application.service.PlayerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Player profile, achievements and recommendations.
 *
 * <h2>Why /me and /{userId} are separate</h2>
 * {@code /me} reads identity from the token and can never be pointed at
 * another player. {@code /{userId}} is a public profile view and returns only
 * what is safe for anyone to see, which is exactly what
 * {@link UserProfileResponse} holds: no email, no roles, no status.
 *
 * <p>Collapsing them into one endpoint with an optional id is where
 * authorisation bugs come from, because the safe and unsafe cases end up
 * sharing a code path and one conditional.
 */
@Tag(name = "Players")
@Validated
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final PlayerQueryService players;

    @Operation(summary = "The authenticated player own profile")
    @GetMapping("/me")
    public UserProfileResponse me(@CurrentUser UUID userId) {
        return players.profile(userId);
    }

    @Operation(summary = "Public profile of any player")
    @GetMapping("/{userId}")
    public UserProfileResponse profile(@PathVariable UUID userId) {
        return players.profile(userId);
    }

    @Operation(summary = "Achievements for the authenticated player, locked and unlocked")
    @GetMapping("/me/achievements")
    public List<AchievementResponse> achievements(
            @CurrentUser UUID userId,
            @RequestParam(required = false) UUID gameId) {

        // Without a game, only unlocked achievements are listed. Returning
        // every locked achievement across the whole catalogue would be a large
        // response that no screen renders.
        return gameId == null
                ? players.allUnlocked(userId)
                : players.achievementsFor(userId, gameId);
    }

    @Operation(summary = "Precomputed recommendations for the authenticated player")
    @GetMapping("/me/recommendations")
    public List<RecommendationResponse> recommendations(
            @CurrentUser UUID userId,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
        return players.recommendationsFor(userId, limit);
    }
}
