package com.gamehub.api.controller;

import com.gamehub.api.dto.MatchmakingJoinRequest;
import com.gamehub.api.dto.MatchmakingTicketResponse;
import com.gamehub.api.security.CurrentUser;
import com.gamehub.application.service.MatchmakingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Matchmaking queue entry, exit and polling.
 *
 * <p>Note what no endpoint here accepts: a user id. Identity comes from the
 * verified token through {@link CurrentUser}. An endpoint that took the player
 * id from the request body would let anyone queue, cancel or inspect on behalf
 * of anyone else, and that is one forgotten authorisation check away from
 * being exploitable.
 */
@Tag(name = "Matchmaking")
@RestController
@RequestMapping("/api/matchmaking")
@RequiredArgsConstructor
public class MatchmakingController {

    private final MatchmakingService matchmaking;

    @Operation(summary = "Join the matchmaking queue")
    @ApiResponses({
            @ApiResponse(responseCode = "202",
                    description = "Ticket accepted. Matching is asynchronous, so poll the "
                            + "ticket for its outcome."),
            @ApiResponse(responseCode = "409",
                    description = "Already queued. Enforced by a partial unique index, so two "
                            + "simultaneous joins cannot both succeed."),
            @ApiResponse(responseCode = "400", description = "Game is single player")
    })
    @PostMapping("/join")
    public ResponseEntity<MatchmakingTicketResponse> join(
            @CurrentUser UUID userId,
            @Valid @RequestBody MatchmakingJoinRequest request) {

        // 202, not 201. The ticket exists, but the match it is waiting for
        // does not yet, and may never. 201 would imply a finished resource.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(matchmaking.join(userId, request));
    }

    @Operation(summary = "Leave the queue. Idempotent.")
    @ApiResponse(responseCode = "204", description = "No longer queued")
    @DeleteMapping("/leave")
    public ResponseEntity<Void> leave(@CurrentUser UUID userId) {
        matchmaking.leave(userId);
        // 204 whether or not a ticket was found. A client cancelling something
        // already matched, or retrying a cancel, has reached the state it
        // wanted and should not be shown an error for it.
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Poll a ticket for its current state")
    @GetMapping("/tickets/{ticketId}")
    public MatchmakingTicketResponse status(@CurrentUser UUID userId,
                                            @PathVariable UUID ticketId) {
        return matchmaking.status(userId, ticketId);
    }
}
