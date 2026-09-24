package com.gamehub.application.service;

import com.gamehub.api.dto.SessionDtos.CompleteSessionRequest;
import com.gamehub.api.dto.SessionDtos.SessionResponse;
import com.gamehub.api.dto.SessionDtos.StartSessionRequest;
import com.gamehub.application.event.OutboxEventPublisher;
import com.gamehub.application.exception.ResourceNotFoundException;
import com.gamehub.application.exception.ValidationException;
import com.gamehub.domain.event.GameCompletedEvent;
import com.gamehub.domain.event.GameStartedEvent;
import com.gamehub.domain.leaderboard.LeaderboardScoreCodec;
import com.gamehub.infrastructure.persistence.entity.GameSessionEntity;
import com.gamehub.infrastructure.persistence.entity.GameSessionEntity.Outcome;
import com.gamehub.infrastructure.persistence.repository.GameRepository;
import com.gamehub.infrastructure.persistence.repository.GameSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Session lifecycle, and the origin of the events the rest of the system
 * reacts to.
 *
 * <p>Completing a session writes the row and the outbox event in one
 * transaction, and does nothing else. Statistics, achievements, leaderboards
 * and recommendations all happen asynchronously in consumers. That is the
 * point of the event bus: ending a game must not wait on four subsystems, and
 * an outage in any of them must not stop players finishing games.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameSessionService {

    private final GameSessionRepository sessions;
    private final GameRepository games;
    private final OutboxEventPublisher events;

    @Transactional
    public SessionResponse start(UUID userId, StartSessionRequest request) {
        if (!games.existsById(request.gameId())) {
            throw new ResourceNotFoundException("game", request.gameId());
        }

        // Resuming an open session rather than opening a second one. A client
        // that crashed and restarted would otherwise accumulate orphan
        // sessions, each inflating playtime statistics.
        return sessions.findByUserIdAndGameIdAndEndedAtIsNull(userId, request.gameId())
                .map(GameSessionService::toResponse)
                .orElseGet(() -> openNew(userId, request));
    }

    private SessionResponse openNew(UUID userId, StartSessionRequest request) {
        GameSessionEntity session = new GameSessionEntity();
        session.setUserId(userId);
        session.setGameId(request.gameId());
        session.setMatchId(request.matchId());
        session.setStartedAt(Instant.now());
        session.setClientVersion(request.clientVersion());

        GameSessionEntity saved = sessions.save(session);

        events.publish(new GameStartedEvent(
                        UUID.randomUUID(), saved.getStartedAt(), saved.getId(),
                        userId, request.gameId(), request.matchId(), request.clientVersion()),
                saved.getId(), userId, request.gameId());

        return toResponse(saved);
    }

    /**
     * Closes a session and emits the fan-out event.
     *
     * <p>The perfect flag from the client is not trusted. It is re-derived
     * from the outcome and score, so a modified client cannot award itself the
     * PERFECT_MATCH achievement by setting a boolean.
     */
    @Transactional
    public SessionResponse complete(UUID userId, UUID sessionId, CompleteSessionRequest request) {
        GameSessionEntity session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("session", sessionId));

        if (!session.getUserId().equals(userId)) {
            // 404, not 403. A 403 would confirm that this session id exists.
            throw new ResourceNotFoundException("session", sessionId);
        }
        if (!session.isOpen()) {
            throw new ValidationException("session " + sessionId + " has already been completed");
        }
        if (request.score() > LeaderboardScoreCodec.MAX_SCORE) {
            throw new ValidationException(
                    "score exceeds the maximum representable leaderboard score of "
                            + LeaderboardScoreCodec.MAX_SCORE);
        }

        Outcome outcome = parseOutcome(request.outcome());
        Instant endedAt = Instant.now();
        session.close(endedAt, request.score(), outcome);

        boolean perfect = derivePerfect(outcome, request.score(), session);

        events.publish(new GameCompletedEvent(
                        UUID.randomUUID(), endedAt, session.getId(), userId, session.getGameId(),
                        session.getMatchId(), request.score(), session.getDurationSeconds(),
                        outcome.name(), perfect),
                session.getId(), userId, session.getGameId());

        log.debug("session {} completed with {} and score {}",
                sessionId, outcome, request.score());
        return toResponse(session);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> recentFor(UUID userId, int limit) {
        return sessions.findByUserIdOrderByStartedAtDesc(
                        userId, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                .map(GameSessionService::toResponse)
                .getContent();
    }

    /**
     * Server-side definition of a perfect match.
     *
     * <p>Deliberately derived rather than accepted. Every rule that grants a
     * reward has to live on the server, because the client is under the
     * control of the person being rewarded.
     */
    private boolean derivePerfect(Outcome outcome, int score, GameSessionEntity session) {
        return outcome == Outcome.WIN
                && score >= 1_000
                && session.getDurationSeconds() != null
                && session.getDurationSeconds() >= 60;
    }

    private Outcome parseOutcome(String raw) {
        try {
            return Outcome.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "outcome must be one of WIN, LOSS, DRAW or ABANDONED");
        }
    }

    private static SessionResponse toResponse(GameSessionEntity session) {
        return new SessionResponse(
                session.getId(), session.getGameId(), session.getMatchId(),
                session.getStartedAt(), session.getEndedAt(), session.getDurationSeconds(),
                session.getScore(),
                session.getOutcome() == null ? null : session.getOutcome().name());
    }
}
