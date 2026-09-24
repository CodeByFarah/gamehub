package com.gamehub.domain.matchmaking;

import com.gamehub.domain.common.Region;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Test fixture builder for {@link MatchTicket}.
 *
 * <p>Exists so a matchmaking test can say what it is actually about. A test
 * that reads {@code ticket(1500, Region.EU_WEST, 20, waitedSeconds(0))} states
 * its intent; the same test with seven positional UUID and Instant arguments
 * inline does not, and that difference is what makes the property tests below
 * readable enough to trust.
 */
final class MatchTickets {

    /** Fixed so that every test in this package ages tickets against one instant. */
    static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    /** One game for the whole fixture, since the engine only ever sees one bucket. */
    static final UUID GAME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MatchTickets() {
    }

    static MatchTicket ticket(int skillRating, Region region, int latencyMs, long waitedSeconds) {
        return new MatchTicket(
                UUID.randomUUID(),
                UUID.randomUUID(),
                GAME,
                region,
                skillRating,
                latencyMs,
                NOW.minusSeconds(waitedSeconds));
    }

    /** A ticket with a caller-chosen id, for tests that assert on ordering. */
    static MatchTicket ticket(UUID ticketId, int skillRating, long waitedSeconds) {
        return new MatchTicket(
                ticketId,
                UUID.randomUUID(),
                GAME,
                Region.EU_WEST,
                skillRating,
                20,
                NOW.minusSeconds(waitedSeconds));
    }

    /**
     * A realistic mixed population: ratings clustered around 1200 with a long
     * tail, latencies mostly good with occasional bad ones, and a spread of
     * wait times. Seeded, so a failure is reproducible.
     */
    static List<MatchTicket> population(int size, long seed) {
        Random random = new Random(seed);
        Region[] regions = Region.values();
        List<MatchTicket> tickets = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            int rating = (int) Math.max(0, Math.round(1200 + random.nextGaussian() * 350));
            int latency = 15 + random.nextInt(random.nextInt(10) == 0 ? 400 : 60);
            long waited = random.nextInt(120);
            tickets.add(ticket(rating, regions[random.nextInt(regions.length)], latency, waited));
        }
        return tickets;
    }
}
