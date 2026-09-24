package com.gamehub.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiDtos {

    private AiDtos() {
    }

    /**
     * Natural-language discovery.
     *
     * <p>Length-capped at the DTO rather than only at the provider. A long
     * prompt costs money per token and widens the prompt-injection surface, so
     * it is rejected at the edge before it reaches either.
     */
    public record AiSearchRequest(
            @NotBlank(message = "prompt is required")
            @Size(max = 500, message = "prompt must be 500 characters or fewer")
            String prompt) {
    }

    /**
     * Structured intent extracted from a prompt.
     *
     * <p>This is the contract the model is asked to fill, and every field is
     * validated before use. Nothing here is passed to the database as text:
     * genre and tags are checked against the known catalogue vocabulary, and
     * the numbers are range-checked. A model that hallucinates a genre
     * produces an empty filter, not a malformed query.
     *
     * @param confidence model self-reported, treated only as a hint for
     *                   whether to show the interpretation back to the user
     * @param degraded   true when this came from the deterministic fallback
     *                   rather than the model, so the UI can be honest about it
     */
    public record AiSearchIntent(
            String genre,
            List<String> tags,
            Boolean multiplayer,
            Integer maxSessionMinutes,
            Double minRating,
            String interpretation,
            double confidence,
            boolean degraded) {
    }

    public record AiSearchResponse(
            AiSearchIntent intent,
            List<GameSummaryResponse> results,
            boolean degraded) {
    }

    public record AiAssistantRequest(
            @NotBlank(message = "question is required")
            @Size(max = 500, message = "question must be 500 characters or fewer")
            String question,
            String gameId) {
    }

    /**
     * @param grounded whether the answer was produced from catalogue data
     *                 supplied to the model. An ungrounded answer is shown
     *                 with a caveat, because a model asked about a game it has
     *                 never seen will confidently invent one.
     */
    public record AiAssistantResponse(
            String answer,
            List<GameSummaryResponse> referencedGames,
            boolean grounded,
            boolean degraded) {
    }
}
