package com.gamehub.api.controller;

import com.gamehub.api.dto.SessionDtos.CompleteSessionRequest;
import com.gamehub.api.dto.SessionDtos.SessionResponse;
import com.gamehub.api.dto.SessionDtos.StartSessionRequest;
import com.gamehub.api.security.CurrentUser;
import com.gamehub.application.service.GameSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Gameplay session lifecycle.
 *
 * <p>Completing a session is the highest-fan-out write in the product: it
 * emits the event that statistics, achievements, leaderboards and
 * recommendations all react to. The endpoint itself stays fast because that
 * fan-out happens asynchronously through the outbox, not inline.
 */
@Tag(name = "Sessions")
@Validated
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final GameSessionService sessions;

    @Operation(summary = "Start a session, or resume an open one for the same game")
    @ApiResponse(responseCode = "201", description = "Session open")
    @PostMapping
    public ResponseEntity<SessionResponse> start(@CurrentUser UUID userId,
                                                 @Valid @RequestBody StartSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessions.start(userId, request));
    }

    @Operation(summary = "Complete a session and emit the downstream event")
    @ApiResponse(responseCode = "200",
            description = "Session closed. Statistics, achievements and leaderboards update "
                    + "asynchronously, so they may lag this response by a moment.")
    @PostMapping("/{sessionId}/complete")
    public SessionResponse complete(@CurrentUser UUID userId,
                                    @PathVariable UUID sessionId,
                                    @Valid @RequestBody CompleteSessionRequest request) {
        return sessions.complete(userId, sessionId, request);
    }

    @Operation(summary = "Recent sessions for the authenticated player")
    @GetMapping
    public List<SessionResponse> recent(
            @CurrentUser UUID userId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return sessions.recentFor(userId, limit);
    }
}
