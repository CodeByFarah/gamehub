package com.gamehub.application.service;

import com.gamehub.api.dto.MatchmakingJoinRequest;
import com.gamehub.api.dto.MatchmakingTicketResponse;
import com.gamehub.application.exception.AlreadyQueuedException;
import com.gamehub.application.exception.ResourceNotFoundException;
import com.gamehub.application.exception.ValidationException;
import com.gamehub.config.properties.MatchmakingProperties;
import com.gamehub.infrastructure.persistence.entity.GameEntity;
import com.gamehub.infrastructure.persistence.entity.MatchmakingTicketEntity;
import com.gamehub.infrastructure.persistence.entity.MatchmakingTicketEntity.TicketStatus;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.GameRepository;
import com.gamehub.infrastructure.persistence.repository.MatchParticipantRepository;
import com.gamehub.infrastructure.persistence.repository.MatchmakingTicketRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Queue entry and exit. The pairing itself happens in
 * {@link MatchmakerTickService}.
 *
 * <h2>The concurrency story</h2>
 * A player double-tapping Find Match, or a client retrying a request that
 * actually succeeded, sends two joins at once. On a single instance a
 * check-then-insert would usually work by luck. On three instances behind a
 * load balancer it fails routinely: both requests read "no active ticket",
 * both insert, and the player is now in the queue twice and can be matched
 * into two games.
 *
 * <p>The defence is the partial unique index
 * {@code uq_mmq_one_active_ticket_per_user}. The pre-check below exists only
 * so the common case returns a clean 409 instead of an integrity violation;
 * the constraint is what makes it correct. Catching the violation and
 * translating it is not belt-and-braces, it is the actual mechanism.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingService {

    private final MatchmakingTicketRepository tickets;
    private final MatchParticipantRepository participants;
    private final UserProfileRepository profiles;
    private final GameRepository games;
    private final MatchmakingProperties properties;

    /**
     * Enqueues a player.
     *
     * <p>Skill rating is read from the profile, never taken from the request.
     * A client-supplied rating would let anyone queue as a beginner and farm
     * real beginners.
     */
    @Transactional
    public MatchmakingTicketResponse join(UUID userId, MatchmakingJoinRequest request) {
        GameEntity game = games.findById(request.gameId())
                .orElseThrow(() -> new ResourceNotFoundException("game", request.gameId()));

        if (!game.isSupportsMultiplayer()) {
            throw new ValidationException(
                    "game " + game.getSlug() + " is single player and has no matchmaking queue");
        }

        UserProfileEntity profile = profiles.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("profile", userId));

        // Fast path for the ordinary duplicate. Not the safety mechanism.
        tickets.findByUserIdAndStatus(userId, TicketStatus.WAITING)
                .ifPresent(existing -> {
                    throw new AlreadyQueuedException(userId, existing.getId());
                });

        MatchmakingTicketEntity ticket = new MatchmakingTicketEntity();
        ticket.setUserId(userId);
        ticket.setGameId(request.gameId());
        ticket.setRegion(profile.getRegion());
        ticket.setSkillRating(profile.getSkillRating());
        ticket.setLatencyMs(request.latencyMs());
        ticket.setStatus(TicketStatus.WAITING);
        ticket.setEnqueuedAt(Instant.now());
        ticket.setExpiresAt(Instant.now().plus(properties.getTicketTtl()));

        try {
            MatchmakingTicketEntity saved = tickets.saveAndFlush(ticket);
            log.debug("queued player {} for game {} at rating {}",
                    userId, request.gameId(), profile.getSkillRating());
            return toResponse(saved, null);
        } catch (DataIntegrityViolationException e) {
            // Lost the race with a concurrent join. The database rejected the
            // second ticket, which is exactly what it is there for. Translate
            // it into the same 409 the fast path would have produced, so the
            // client sees one consistent behaviour either way.
            log.info("concurrent join rejected for player {} by the unique index", userId);
            throw new AlreadyQueuedException(userId, null);
        }
    }

    /**
     * Removes a player from the queue.
     *
     * <p>Idempotent. Leaving when not queued is a no-op rather than a 404,
     * because a client that has already been matched, or that is retrying a
     * cancel, should not be shown an error for reaching the state it wanted.
     */
    @Transactional
    public void leave(UUID userId) {
        int cancelled = tickets.cancelWaitingForUser(userId);
        if (cancelled > 0) {
            log.debug("player {} left the queue", userId);
        }
    }

    /**
     * Current ticket state, including the match if one has been made.
     *
     * <p>This is the endpoint the client polls while queueing, so it stays a
     * single indexed lookup.
     */
    @Transactional(readOnly = true)
    public MatchmakingTicketResponse status(UUID userId, UUID ticketId) {
        MatchmakingTicketEntity ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("ticket", ticketId));

        if (!ticket.getUserId().equals(userId)) {
            // 404 rather than 403. Telling a caller that a ticket exists but
            // is not theirs leaks the existence of other tickets.
            throw new ResourceNotFoundException("ticket", ticketId);
        }

        List<UUID> opponents = ticket.getMatchId() == null ? List.of()
                : participants.findByMatchId(ticket.getMatchId()).stream()
                        .map(p -> p.getUserId())
                        .filter(id -> !id.equals(userId))
                        .toList();

        return toResponse(ticket, opponents);
    }

    public long waitingCount() {
        return tickets.countByStatus(TicketStatus.WAITING);
    }

    private MatchmakingTicketResponse toResponse(MatchmakingTicketEntity ticket,
                                                 List<UUID> opponents) {
        long waited = Math.max(0,
                Instant.now().getEpochSecond() - ticket.getEnqueuedAt().getEpochSecond());

        return new MatchmakingTicketResponse(
                ticket.getId(),
                ticket.getStatus().name(),
                ticket.getGameId(),
                ticket.getRegion().name(),
                ticket.getSkillRating(),
                ticket.getEnqueuedAt(),
                ticket.getExpiresAt(),
                waited,
                // A hint, not a promise. Derived from the point at which the
                // acceptance threshold has relaxed enough that a match is
                // likely, which is the only honest estimate available without
                // modelling the arrival rate of the bucket.
                (long) properties.getPatienceSeconds(),
                ticket.getMatchId(),
                opponents == null ? List.of() : opponents);
    }
}
