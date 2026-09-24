package com.gamehub.domain.common;

import java.util.Map;
import java.util.Set;

/**
 * Deployment regions a player can be routed to.
 *
 * <p>This enum carries the inter-region affinity used by matchmaking. Modelling
 * affinity as data rather than as a chain of if-statements means the
 * matchmaking cost function stays a pure arithmetic expression, and adding a
 * region is a change to one table instead of an edit to every call site.
 *
 * <p>The affinity values are ordinal proximity, not measured RTT. Real latency
 * between two players is measured by the client and passed on the ticket
 * ({@code latencyMs}); this enum only answers "how bad is it to put these two
 * players on the same server at all". Keeping the two separate matters: a
 * player on a bad mobile connection inside their own region should be penalised
 * by their latency, not excused by their region.
 */
public enum Region {

    NA_EAST,
    NA_WEST,
    EU_WEST,
    EU_CENTRAL,
    SA_EAST,
    AP_SOUTHEAST,
    AP_NORTHEAST,
    ME_CENTRAL;

    /**
     * Regions that share a continent and are therefore cheap to cross.
     * Symmetry is asserted in {@code RegionTest}; an asymmetric table would
     * make the matchmaking cost function order-dependent, which would in turn
     * make match results depend on queue insertion order.
     */
    private static final Map<Region, Set<Region>> NEIGHBOURS = Map.of(
            NA_EAST,      Set.of(NA_WEST),
            NA_WEST,      Set.of(NA_EAST),
            EU_WEST,      Set.of(EU_CENTRAL),
            EU_CENTRAL,   Set.of(EU_WEST, ME_CENTRAL),
            ME_CENTRAL,   Set.of(EU_CENTRAL),
            AP_SOUTHEAST, Set.of(AP_NORTHEAST),
            AP_NORTHEAST, Set.of(AP_SOUTHEAST),
            SA_EAST,      Set.of()
    );

    /** Same region. No penalty. */
    public static final double AFFINITY_SAME = 0.0d;

    /** Adjacent region, typically same continent. Playable, mildly penalised. */
    public static final double AFFINITY_NEIGHBOUR = 0.35d;

    /** Cross-continent. Allowed only once a player has waited long enough. */
    public static final double AFFINITY_DISTANT = 1.0d;

    /**
     * Normalised penalty in {@code [0, 1]} for pairing a player in this region
     * with a player in {@code other}. Symmetric by construction.
     *
     * @param other the other player's region, never {@code null}
     * @return 0.0 for the same region, 0.35 for a neighbour, 1.0 otherwise
     */
    public double affinityPenaltyTo(Region other) {
        if (other == null) {
            throw new IllegalArgumentException("other region must not be null");
        }
        if (this == other) {
            return AFFINITY_SAME;
        }
        return NEIGHBOURS.getOrDefault(this, Set.of()).contains(other)
                ? AFFINITY_NEIGHBOUR
                : AFFINITY_DISTANT;
    }
}
