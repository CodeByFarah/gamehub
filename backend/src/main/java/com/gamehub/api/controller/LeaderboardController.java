package com.gamehub.api.controller;

import com.gamehub.api.dto.LeaderboardResponse;
import com.gamehub.api.security.CurrentUser;
import com.gamehub.api.security.JwtService;
import com.gamehub.application.exception.ValidationException;
import com.gamehub.application.service.LeaderboardService;
import com.gamehub.domain.common.Region;
import com.gamehub.domain.leaderboard.LeaderboardPeriod;
import com.gamehub.domain.leaderboard.LeaderboardScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Leaderboard reads.
 *
 * <p>Public, so a leaderboard is shareable without an account. The viewer own
 * standing is resolved only when a token happens to be present, which is why
 * the principal is read from {@link Authentication} rather than injected with
 * {@code @CurrentUser}: the latter throws on an anonymous request, and here
 * anonymous is legitimate.
 */
@Tag(name = "Leaderboards")
@Validated
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboards;

    @Operation(summary = "Leaderboard for one game")
    @GetMapping("/games/{gameId}/leaderboard")
    public LeaderboardResponse forGame(
            @PathVariable UUID gameId,
            @RequestParam(defaultValue = "ALL_TIME") String period,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {

        return leaderboards.page(LeaderboardScope.GAME, gameId, null, parsePeriod(period),
                page, size, viewerId(authentication));
    }

    @Operation(summary = "Global leaderboard across all games")
    @GetMapping("/leaderboards/global")
    public LeaderboardResponse global(
            @RequestParam(defaultValue = "ALL_TIME") String period,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {

        return leaderboards.page(LeaderboardScope.GLOBAL, null, null, parsePeriod(period),
                page, size, viewerId(authentication));
    }

    @Operation(summary = "Leaderboard for one region")
    @GetMapping("/leaderboards/regional/{region}")
    public LeaderboardResponse regional(
            @Parameter(description = "One of the deployment regions, for example EU_WEST")
            @PathVariable String region,
            @RequestParam(defaultValue = "ALL_TIME") String period,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {

        return leaderboards.page(LeaderboardScope.REGIONAL, null, parseRegion(region), parsePeriod(period),
                page, size, viewerId(authentication));
    }

    /** Null for an anonymous caller, which the service treats as no viewer. */
    private UUID viewerId(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof JwtService.AuthenticatedUser user)) {
            return null;
        }
        return user.userId();
    }

    private LeaderboardPeriod parsePeriod(String raw) {
        try {
            return LeaderboardPeriod.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "period must be one of ALL_TIME, MONTHLY, WEEKLY or DAILY");
        }
    }

    private Region parseRegion(String raw) {
        try {
            return Region.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "region must be one of " + java.util.Arrays.toString(Region.values()));
        }
    }
}
