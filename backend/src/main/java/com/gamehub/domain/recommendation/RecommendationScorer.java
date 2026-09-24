package com.gamehub.domain.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Content-based recommendation scoring.
 *
 * <h2>Why content-based and not collaborative filtering</h2>
 * Collaborative filtering gives better recommendations once there is a large
 * interaction matrix, and nothing at all before that. It cannot recommend a
 * newly published game to anyone, and it cannot recommend anything to a new
 * player: the cold-start problem, in both directions. A platform that
 * continually adds games and players hits both cases constantly.
 *
 * <p>Content-based scoring works from the first session and from a game first
 * day, at the cost of a narrower discovery window: it will not surprise a
 * player with something unlike what they have played. That is the right trade
 * at this stage. The honest upgrade path is a hybrid once interaction volume
 * justifies it, not pretending collaborative filtering works on an empty
 * matrix.
 *
 * <h2>The score</h2>
 * <pre>
 *   score = tagWeight        * jaccard(playerTags, gameTags)
 *         + genreWeight      * genreMatch
 *         + popularityWeight * normalisedPopularity
 *         + recencyWeight    * recencyBoost
 * </pre>
 *
 * Every term lies in the closed interval 0 to 1 and the weights sum to 1, so
 * the result is a genuine 0 to 1 score. That matters because it is persisted
 * in a NUMERIC(8,6) column with a CHECK constraint, and because a score
 * comparable across users is what makes the ranking meaningful at all.
 *
 * <h3>Jaccard, not raw overlap</h3>
 * Raw overlap count rewards games that simply carry many tags. Jaccard divides
 * by the union, so a game tagged exactly like the player taste outranks one
 * that happens to include those tags among twenty others.
 *
 * <p>Popularity is included at a low weight purely as a tie-break: among games
 * a player is equally likely to enjoy, the one others actually play is the
 * better bet. Weighted higher it would collapse into a bestseller list.
 *
 * <p>Complexity: O(G * T) for G candidates and T tags each, plus O(G log G)
 * for the sort. Pure and allocation-light, so it runs inside a Kafka consumer
 * with no database round trip per candidate.
 */
public final class RecommendationScorer {

    private static final double TAG_WEIGHT = 0.45d;
    private static final double GENRE_WEIGHT = 0.25d;
    private static final double POPULARITY_WEIGHT = 0.15d;
    private static final double RECENCY_WEIGHT = 0.15d;

    /** Days over which the recency term decays to zero. */
    private static final double RECENCY_WINDOW_DAYS = 180.0d;

    private RecommendationScorer() {
    }

    /** What the player has shown a taste for, derived from recent sessions. */
    public record PlayerTaste(Set<String> tags, Set<String> genres, Set<UUID> alreadyPlayed) {

        public PlayerTaste {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            genres = genres == null ? Set.of() : Set.copyOf(genres);
            alreadyPlayed = alreadyPlayed == null ? Set.of() : Set.copyOf(alreadyPlayed);
        }
    }

    /** A candidate game, reduced to what scoring actually needs. */
    public record Candidate(UUID gameId, String title, String genre, Set<String> tags,
                            int popularityScore, int daysSinceRelease) {
    }

    public record ScoredCandidate(UUID gameId, double score, String reason) {
    }

    /**
     * Ranks candidates for one player.
     *
     * @param maxPopularity highest popularity in the candidate set, used to
     *                      normalise. Passed in rather than derived so scores
     *                      stay comparable across invocations that see
     *                      different slices of the catalogue.
     */
    public static List<ScoredCandidate> rank(PlayerTaste taste, List<Candidate> candidates,
                                             int maxPopularity, int limit) {
        if (taste == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<ScoredCandidate> scored = new ArrayList<>(candidates.size());

        for (Candidate candidate : candidates) {
            // Never recommend something already played. The point is
            // discovery, and a list of games you own reads as broken.
            if (taste.alreadyPlayed().contains(candidate.gameId())) {
                continue;
            }

            double tagScore = jaccard(taste.tags(), candidate.tags());
            double genreScore = taste.genres().contains(candidate.genre()) ? 1.0d : 0.0d;
            double popularityScore = maxPopularity <= 0
                    ? 0.0d
                    : Math.min(1.0d, (double) candidate.popularityScore() / maxPopularity);
            double recencyScore = recencyBoost(candidate.daysSinceRelease());

            double score = TAG_WEIGHT * tagScore
                    + GENRE_WEIGHT * genreScore
                    + POPULARITY_WEIGHT * popularityScore
                    + RECENCY_WEIGHT * recencyScore;

            // Zero means nothing about this game matches. Including it would
            // pad the list with noise that erodes trust in the rest.
            if (score <= 0.0d) {
                continue;
            }

            scored.add(new ScoredCandidate(candidate.gameId(), round(score),
                    explain(tagScore, genreScore, recencyScore, candidate)));
        }

        scored.sort(Comparator
                .comparingDouble(ScoredCandidate::score).reversed()
                // Stable tie-break, so two runs over the same data give the
                // same order and the Home screen does not reshuffle at random.
                .thenComparing(ScoredCandidate::gameId));

        return scored.stream().limit(Math.max(limit, 0)).toList();
    }

    /**
     * Jaccard similarity: intersection over union, in the closed interval 0
     * to 1. Empty on either side scores zero rather than dividing by zero.
     */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) {
            return 0.0d;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /**
     * Linear decay to zero over the recency window.
     *
     * <p>Linear rather than exponential, because the intent is a mild nudge
     * towards new releases and not a feed dominated by whatever shipped this
     * week. Exponential decay at this weight would make anything older than a
     * month effectively invisible.
     */
    static double recencyBoost(int daysSinceRelease) {
        if (daysSinceRelease < 0) {
            return 0.0d;
        }
        return Math.max(0.0d, 1.0d - (daysSinceRelease / RECENCY_WINDOW_DAYS));
    }

    /**
     * A human-readable justification, chosen from whichever term dominated.
     *
     * <p>Generated alongside the score rather than reconstructed later, so the
     * explanation cannot drift from the arithmetic that produced the ranking.
     */
    private static String explain(double tagScore, double genreScore,
                                  double recencyScore, Candidate candidate) {
        if (tagScore >= 0.5d) {
            return "Matches what you usually play";
        }
        if (genreScore > 0) {
            return "More " + candidate.genre() + " games like the ones you play";
        }
        if (recencyScore > 0.8d) {
            return "New release you might not have seen";
        }
        return "Popular with players like you";
    }

    /** Six decimal places, matching the NUMERIC(8,6) column it is stored in. */
    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
