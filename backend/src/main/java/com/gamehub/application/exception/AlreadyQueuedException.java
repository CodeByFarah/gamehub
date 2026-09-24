package com.gamehub.application.exception;

import com.gamehub.api.error.ErrorCode;

import java.util.Map;
import java.util.UUID;

/**
 * Raised when a player who already holds a waiting ticket tries to queue again.
 *
 * <p>Almost always triggered by the partial unique index rather than by an
 * application check, which is the point: under concurrency the database is the
 * only component that can decide which of two simultaneous joins wins.
 */
public class AlreadyQueuedException extends GameHubException {

    public AlreadyQueuedException(UUID userId, UUID existingTicketId) {
        super(409, ErrorCode.ALREADY_QUEUED,
                "player " + userId + " already holds an active matchmaking ticket",
                Map.of("existingTicketId", String.valueOf(existingTicketId)));
    }
}
