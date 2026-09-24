package com.gamehub.application.service;

import com.gamehub.application.event.OutboxEventPublisher;
import com.gamehub.domain.common.Region;
import com.gamehub.domain.event.MatchCreatedEvent;
import com.gamehub.domain.matchmaking.MatchTicket;
import com.gamehub.domain.matchmaking.ProposedMatch;
import com.gamehub.infrastructure.persistence.entity.MatchEntity;
import com.gamehub.infrastructure.persistence.entity.MatchParticipantEntity;
import com.gamehub.infrastructure.persistence.repository.MatchParticipantRepository;
import com.gamehub.infrastructure.persistence.repository.MatchRepository;
import com.gamehub.infrastructure.persistence.repository.MatchmakingTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a {@link ProposedMatch} into a committed match, or rejects it.
 *
 * <h2>The race this closes</h2>
 * The matchmaking engine works from a snapshot. Between the snapshot and this
 * call, any of the following can have happened:
 *
 * <ul>
 *   <li>A player cancelled and is no longer WAITING.</li>
 *   <li>A different instance matched the same player into another game.</li>
 *   <li>The ticket expired.</li>
 * </ul>
 *
 * Writing the match without re-checking would put one player into two matches
 * at once, which is visible to players and awkward to unwind.
 *
 * <h2>How it is closed</h2>
 * The conditional UPDATE {@code claimTicketsForMatch} transitions only tickets
 * that are still WAITING, and reports how many it changed. If that count is
 * not exactly the number of participants, the proposal is stale and the whole
 * transaction rolls back, so no half-formed match is ever visible and the
 * surviving ticket is untouched and free for the next tick.
 *
 * <p>Two instances proposing the same pair is therefore safe: both run this,
 * one claims both tickets, the other claims zero and rolls back.
 *
 * <p>Separate bean from the tick scheduler so the call crosses the Spring
 * proxy and {@code @Transactional} actually applies. Called as a lambda from
 * inside the scheduler, it would not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchCommitService {

    private final MatchmakingTicketRepository tickets;
    private final MatchRepository matches;
    private final MatchParticipantRepository participants;
    private final OutboxEventPublisher events;

    /**
     * Commits a proposal.
     *
     * @return the new match id, or empty when the proposal was stale
     */
    @Transactional
    public Optional<UUID> commit(ProposedMatch proposal, Region region) {
        List<MatchTicket> proposedTickets = proposal.tickets();

        MatchEntity match = new MatchEntity();
        match.setGameId(proposal.gameId());
        match.setRegion(region);
        match.setStatus(MatchEntity.MatchStatus.CREATED);
        match.setMatchQuality(BigDecimal.valueOf(proposal.cost())
                .setScale(4, java.math.RoundingMode.HALF_UP));
        MatchEntity persisted = matches.save(match);

        // The decisive step. Claims every ticket or reports that it could not.
        int claimed = tickets.claimTicketsForMatch(proposal.ticketIds(), persisted.getId());

        if (claimed != proposedTickets.size()) {
            // Stale proposal. Rolling back discards the match row as well as
            // any partial claim, so nothing inconsistent is ever committed.
            log.debug("discarding stale proposal for game {}: claimed {} of {} tickets",
                    proposal.gameId(), claimed, proposedTickets.size());
            throw new StaleProposalException(claimed, proposedTickets.size());
        }

        for (MatchTicket ticket : proposedTickets) {
            MatchParticipantEntity participant = new MatchParticipantEntity();
            participant.setMatchId(persisted.getId());
            participant.setUserId(ticket.userId());
            participant.setTeam((short) proposedTickets.indexOf(ticket));
            // Snapshotted, so "was this match fair when it was made?" stays
            // answerable after both ratings have moved on.
            participant.setSkillRatingAtMatch(ticket.skillRating());
            participant.setLatencyMsAtMatch(ticket.latencyMs());
            participants.save(participant);
        }

        Instant now = Instant.now();
        long longestWait = proposedTickets.stream()
                .mapToLong(t -> t.waitSeconds(now))
                .max()
                .orElse(0L);

        // Same transaction as the claim, so the event cannot exist for a match
        // that rolled back.
        events.publish(new MatchCreatedEvent(
                        UUID.randomUUID(), now, persisted.getId(), proposal.gameId(),
                        region.name(), proposal.userIds(), proposal.cost(), longestWait),
                persisted.getId());

        log.info("created match {} for game {} quality {} after {}s",
                persisted.getId(), proposal.gameId(),
                String.format("%.4f", proposal.cost()), longestWait);

        return Optional.of(persisted.getId());
    }

    /**
     * Signals a stale proposal.
     *
     * <p>An exception rather than a return value, because it must roll the
     * transaction back. Returning empty would commit the match row and the
     * partial claim, which is exactly the inconsistency being prevented.
     */
    public static class StaleProposalException extends RuntimeException {

        private final transient int claimed;
        private final transient int expected;

        public StaleProposalException(int claimed, int expected) {
            super("claimed " + claimed + " of " + expected + " tickets");
            this.claimed = claimed;
            this.expected = expected;
        }

        public int getClaimed() {
            return claimed;
        }

        public int getExpected() {
            return expected;
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // Expected outcome under concurrency, not a defect. A stack trace
            // per occurrence would be pure overhead on a per-second job.
            return this;
        }
    }
}
