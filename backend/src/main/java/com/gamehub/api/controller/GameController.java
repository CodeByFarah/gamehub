package com.gamehub.api.controller;

import com.gamehub.api.dto.GameDetailResponse;
import com.gamehub.api.dto.GameSummaryResponse;
import com.gamehub.api.dto.PageResponse;
import com.gamehub.application.service.GameCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * Catalogue browse, search and filter. Public: browsing needs no account.
 */
@Tag(name = "Games")
@Validated
@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameController {

    private final GameCatalogService catalogue;

    /**
     * Listing, search and filtering on one endpoint.
     *
     * <p>One endpoint rather than three, because the Discover screen moves
     * between these modes as the user types and toggles facets. Splitting them
     * would make the client juggle three URLs for what is, to the user, a
     * single evolving query.
     */
    @Operation(summary = "Browse, search or filter the catalogue")
    @GetMapping
    public PageResponse<GameSummaryResponse> list(
            @Parameter(description = "Free-text query. When present, search takes precedence.")
            @RequestParam(required = false) String q,

            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Boolean multiplayer,
            @RequestParam(required = false) Integer maxSessionMinutes,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) List<String> tags,

            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

        if (q != null && !q.isBlank()) {
            List<GameSummaryResponse> results = catalogue.search(q, size, page * size);
            // Total is unknown without a second COUNT over the full-text
            // match, which would double the cost of every search to populate
            // a number the UI only uses for an infinite scroll.
            return PageResponse.unbounded(results, page, size);
        }

        boolean filtering = genre != null || multiplayer != null
                || maxSessionMinutes != null || minRating != null
                || (tags != null && !tags.isEmpty());

        if (filtering) {
            List<GameSummaryResponse> results = catalogue.filter(
                    genre, multiplayer, maxSessionMinutes, minRating, tags, size, page * size);
            return PageResponse.unbounded(results, page, size);
        }

        return catalogue.browse(page, size);
    }

    @Operation(summary = "Game detail by id")
    @GetMapping("/{gameId}")
    public GameDetailResponse detail(@PathVariable UUID gameId) {
        return catalogue.detail(gameId);
    }

    @Operation(summary = "Game detail by slug, for shareable links")
    @GetMapping("/slug/{slug}")
    public GameDetailResponse detailBySlug(@PathVariable String slug) {
        return catalogue.detailBySlug(slug);
    }
}
