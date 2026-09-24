package com.gamehub.integration;

import com.gamehub.api.dto.MatchmakingJoinRequest;
import com.gamehub.application.exception.AlreadyQueuedException;
import com.gamehub.application.service.MatchmakingService;
import com.gamehub.domain.common.Region;
import com.gamehub.infrastructure.persistence.entity.MatchmakingTicketEntity.TicketStatus;
import com.gamehub.infrastructure.persistence.entity.UserEntity;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.MatchmakingTicketRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import com.gamehub.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One player, many simultaneous join requests. Exactly one ticket may exist.
 *
 * <h2>Problem</h2>
 * A player double-taps Find Match, or a flaky connection makes the client
 * retry a request that actually succeeded. Two or more joins arrive at once,
 * and behind a load balancer they land on different instances.
 *
 * <h2>Race condition</h2>
 * The obvious implementation reads "does this player already have a waiting
 * ticket?" and inserts if not. Between the read and the insert, the other
 * request does the same. Both see no ticket, both insert, and the player is
 * now in the queue twice.
 *
 * <p>The consequence is not cosmetic: the matchmaker can pair each ticket
 * separately, putting one player into two games at once. Both opponents then
 * believe they have a match against someone who can only play one of them.
 *
 * <h2>Why an application check cannot fix it</h2>
 * No amount of care in Java closes this. The two requests are in different
 * transactions, possibly in different JVMs. A synchronized block guards one
 * instance and does nothing across three. A read at READ COMMITTED cannot see
 * an uncommitted insert from a concurrent transaction, so both reads correctly
 * return nothing.
 *
 * <h2>Solution</h2>
 * A partial unique index:
 *
 * <pre>
 *   CREATE UNIQUE INDEX uq_mmq_one_active_ticket_per_user
 *       ON matchmaking_queue (user_id) WHERE status = 'WAITING';
 * </pre>
 *
 * The database serialises the two inserts and rejects the loser. Partial, so
 * it constrains only active tickets and a player can queue again after a
 * previous ticket was matched or cancelled.
 *
 * <h2>Why this works</h2>
 * Uniqueness is enforced at the point of write by the one component both
 * requests share. It holds regardless of instance count, isolation level or
 * request ordering, which is precisely what an application-level check cannot
 * offer.
 *
 * <h2>Tradeoffs</h2>
 * The loser surfaces as a constraint violation rather than a clean branch, so
 * the service must translate it into the same 409 the fast-path check
 * produces. That translation is not defensive decoration; it is the mechanism.
 * The pre-check exists only so the ordinary duplicate avoids a write.
 */
@Tag("concurrency")
@DisplayName("Matchmaking: simultaneous joins by one player")
class MatchmakingConcurrencyTest extends IntegrationTestBase {

    /** Ironroot Siege, the multiplayer seed game. */
    private static final UUID GAME_ID = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    private static final int ATTEMPTS = 12;

    @Autowired private MatchmakingService matchmaking;
    @Autowired private MatchmakingTicketRepository tickets;
    @Autowired private UserRepository users;
    @Autowired private UserProfileRepository profiles;

    private UUID userId;

    @BeforeEach
    void createPlayer() {
        UserEntity user = new UserEntity();
        user.setUsername("queuer" + UUID.randomUUID().toString().substring(0, 8));
        user.setEmail(user.getUsername() + "@example.test");
        user.setPasswordHash("not-a-real-hash");
        userId = users.saveAndFlush(user).getId();

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(userId);
        profile.setDisplayName("Queuer");
        profile.setRegion(Region.EU_WEST);
        profile.setSkillRating(1200);
        profiles.saveAndFlush(profile);
    }

    @Test
    @DisplayName("only one ticket is created no matter how many joins arrive at once")
    void onlyOneTicketSurvives() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        CyclicBarrier startLine = new CyclicBarrier(ATTEMPTS);

        try (ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS)) {
            List<Callable<Void>> joins = new ArrayList<>();

            for (int i = 0; i < ATTEMPTS; i++) {
                joins.add(() -> {
                    startLine.await();
                    try {
                        matchmaking.join(userId, new MatchmakingJoinRequest(GAME_ID, 30));
                        accepted.incrementAndGet();
                    } catch (AlreadyQueuedException e) {
                        // The expected rejection, whether it came from the
                        // fast-path check or from the unique index being
                        // translated. The caller cannot tell, which is the
                        // point: one consistent behaviour either way.
                        rejected.incrementAndGet();
                    } catch (DataIntegrityViolationException e) {
                        // Reaching here would mean the service failed to
                        // translate the constraint violation, so the API would
                        // return 500 instead of 409 for an ordinary
                        // double-tap.
                        throw new AssertionError(
                                "constraint violation escaped untranslated", e);
                    }
                    return null;
                });
            }

            for (Future<Void> future : pool.invokeAll(joins)) {
                future.get();
            }
        }

        assertThat(accepted.get())
                .withFailMessage("expected exactly one accepted join, got %d", accepted.get())
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(ATTEMPTS - 1);

        // The invariant restated against the database, not against the
        // counters. A test that only counted exceptions would pass even if
        // two rows had been written and one call had thrown for an unrelated
        // reason.
        assertThat(tickets.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .filter(t -> t.getStatus() == TicketStatus.WAITING)
                .count())
                .withFailMessage("more than one WAITING ticket exists for the player")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a player can queue again once the previous ticket is cancelled")
    void canRequeueAfterCancelling() {
        matchmaking.join(userId, new MatchmakingJoinRequest(GAME_ID, 30));
        matchmaking.leave(userId);

        // The index is partial on status = 'WAITING', so the cancelled row
        // does not block this. A plain unique index on user_id would, and the
        // player could never queue a second time.
        matchmaking.join(userId, new MatchmakingJoinRequest(GAME_ID, 30));

        assertThat(tickets.findByUserIdAndStatus(userId, TicketStatus.WAITING)).isPresent();
    }

    @Test
    @DisplayName("leaving is idempotent and never errors when not queued")
    void leavingIsIdempotent() {
        // A client that has already been matched, or that retries a cancel,
        // has reached the state it wanted and must not be shown an error.
        matchmaking.leave(userId);
        matchmaking.leave(userId);

        assertThat(tickets.findByUserIdAndStatus(userId, TicketStatus.WAITING)).isEmpty();
    }
}
