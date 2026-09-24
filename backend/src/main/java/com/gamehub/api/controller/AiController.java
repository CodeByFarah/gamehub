package com.gamehub.api.controller;

import com.gamehub.api.dto.AiDtos.AiAssistantRequest;
import com.gamehub.api.dto.AiDtos.AiAssistantResponse;
import com.gamehub.api.dto.AiDtos.AiSearchRequest;
import com.gamehub.api.dto.AiDtos.AiSearchResponse;
import com.gamehub.api.security.CurrentUser;
import com.gamehub.application.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Natural-language discovery and the game assistant.
 *
 * <h2>Why these require authentication when browsing does not</h2>
 * Every call here can cost money at a third-party provider. Anonymous access
 * would make the endpoint a free proxy to a paid API, and the per-principal
 * rate limit would have nothing to key on.
 *
 * <h2>degraded is part of the contract</h2>
 * Both responses carry a {@code degraded} flag, true when the answer came from
 * the deterministic fallback rather than a model. Surfacing it means the UI
 * can be honest, and it makes silent degradation countable rather than
 * invisible.
 */
@Tag(name = "AI")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @Operation(summary = "Turn a natural-language request into catalogue results")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Results, with the extracted intent. degraded=true means the "
                            + "interpretation was made without a model."),
            @ApiResponse(responseCode = "400", description = "Prompt missing or over 500 characters"),
            @ApiResponse(responseCode = "429", description = "AI rate limit exceeded")
    })
    @PostMapping("/search")
    public AiSearchResponse search(@CurrentUser UUID userId,
                                   @Valid @RequestBody AiSearchRequest request) {
        return aiService.search(request.prompt());
    }

    @Operation(summary = "Ask a question about games, grounded in the catalogue")
    @PostMapping("/assistant")
    public AiAssistantResponse assist(@CurrentUser UUID userId,
                                      @Valid @RequestBody AiAssistantRequest request) {
        UUID gameId = parseGameId(request.gameId());
        return aiService.assist(request.question(), gameId);
    }

    /**
     * A malformed game id is treated as absent rather than as an error.
     *
     * <p>The parameter only narrows the grounding context; a bad value costs
     * the caller a broader answer, not a failed request.
     */
    private UUID parseGameId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
