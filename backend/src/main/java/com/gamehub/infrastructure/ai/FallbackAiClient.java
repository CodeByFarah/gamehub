package com.gamehub.infrastructure.ai;

import com.gamehub.application.port.AiClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic keyword extraction, used when no model is available.
 *
 * <h2>Why this exists</h2>
 * Two different situations need it:
 *
 * <ul>
 *   <li>No API key configured. A reviewer clones this repository, runs it with
 *       no Google account, and AI search still returns sensible results
 *       instead of a 503.</li>
 *   <li>The provider is down, slow, rate limited or returning nonsense. The
 *       circuit breaker opens and traffic lands here.</li>
 * </ul>
 *
 * <p>In both cases the answer is honest: results are marked {@code degraded},
 * and the UI says the interpretation was made without AI rather than
 * pretending a model produced it.
 *
 * <h2>What it actually does</h2>
 * Keyword and phrase matching against the catalogue vocabulary, plus a few
 * hand-written rules for the dimensions that matter most: session length, and
 * whether the player wants company. It is not clever and is not meant to be.
 * It is meant to be correct, instant, free, and incapable of hallucinating a
 * genre that does not exist.
 *
 * <p>Complexity: O(P * V) for a prompt of P tokens against V vocabulary terms,
 * both small and bounded. Microseconds in practice.
 */
@Slf4j
public class FallbackAiClient implements AiClient {

    // Character classes rather than backslash-d and backslash-s: equivalent
    // here, and they keep the pattern readable without a second layer of
    // escaping.
    private static final Pattern MINUTES = Pattern.compile("([0-9]{1,3})[ ]*(minute|min)");
    private static final Pattern HOURS = Pattern.compile("([0-9]{1,2})[ ]*(hour|hr)");

    /** Phrases implying a session-length ceiling. */
    private static final Map<String, Integer> DURATION_HINTS = Map.of(
            "quick", 10,
            "short", 15,
            "coffee break", 15,
            "half an hour", 30,
            "long session", 120,
            "all evening", 180);

    private static final Set<String> MULTIPLAYER_HINTS = Set.of(
            "multiplayer", "competitive", "versus", "pvp", "friends",
            "with others", "against people", "team");

    private static final Set<String> SINGLEPLAYER_HINTS = Set.of(
            "single player", "singleplayer", "solo", "alone", "by myself",
            "offline", "story");

    @Override
    public SearchIntent extractSearchIntent(String prompt, CatalogueVocabulary vocabulary) {
        if (prompt == null || prompt.isBlank()) {
            return SearchIntent.empty("No prompt supplied");
        }

        String normalised = prompt.toLowerCase(Locale.ROOT);

        String genre = firstMatch(normalised, vocabulary.genres());
        List<String> tags = allMatches(normalised, vocabulary.tags());
        Boolean multiplayer = detectMultiplayer(normalised);
        Integer maxMinutes = detectDuration(normalised);

        // Confidence reflects how many dimensions were actually pinned down,
        // not a guess at quality. The UI uses it only to decide whether to
        // show the interpretation back for confirmation.
        int signals = (genre != null ? 1 : 0) + (tags.isEmpty() ? 0 : 1)
                + (multiplayer != null ? 1 : 0) + (maxMinutes != null ? 1 : 0);

        return new SearchIntent(genre, tags, multiplayer, maxMinutes, null,
                describe(genre, tags, multiplayer, maxMinutes), signals / 4.0d, true);
    }

    /**
     * Returns the supplied context rather than composing prose.
     *
     * <p>Deliberately does not attempt a written answer. A template-generated
     * sentence pretending to be an assistant reply would be worse than saying
     * plainly that AI is unavailable: it would look like the feature working
     * while giving the user nothing.
     */
    @Override
    public AssistantAnswer answer(String question, List<GameContext> context) {
        if (context == null || context.isEmpty()) {
            return new AssistantAnswer(
                    "The AI assistant is unavailable right now, and no matching games were "
                            + "found for that question. Try browsing by genre instead.",
                    List.of(), false, true);
        }

        List<String> ids = context.stream().map(GameContext::gameId).toList();
        String titles = context.stream().map(GameContext::title).limit(5)
                .reduce((a, b) -> a + ", " + b).orElse("");

        return new AssistantAnswer(
                "The AI assistant is unavailable right now. Based on a keyword match, these "
                        + "games look relevant: " + titles + ".",
                ids, true, true);
    }

    @Override
    public boolean isAvailable() {
        // Always true, and that is the point: this implementation cannot fail,
        // so the AI endpoints always have something to fall back to.
        return true;
    }

    private String firstMatch(String prompt, List<String> vocabulary) {
        return vocabulary.stream()
                .filter(term -> prompt.contains(term.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private List<String> allMatches(String prompt, List<String> vocabulary) {
        List<String> found = new ArrayList<>();
        for (String term : vocabulary) {
            if (prompt.contains(term.toLowerCase(Locale.ROOT))) {
                found.add(term);
            }
        }
        return List.copyOf(found);
    }

    private Boolean detectMultiplayer(String prompt) {
        // Single player is checked first: phrases like "not multiplayer" and
        // "solo story" both contain multiplayer-adjacent words, and the
        // explicit solo signal is the more reliable of the two.
        if (SINGLEPLAYER_HINTS.stream().anyMatch(prompt::contains)) {
            return Boolean.FALSE;
        }
        if (MULTIPLAYER_HINTS.stream().anyMatch(prompt::contains)) {
            return Boolean.TRUE;
        }
        return null;
    }

    private Integer detectDuration(String prompt) {
        // An explicit number beats a vague phrase: "20 minutes" is a far
        // stronger statement of intent than "quick".
        Integer explicit = parseExplicitMinutes(prompt);
        if (explicit != null) {
            return explicit;
        }
        return DURATION_HINTS.entrySet().stream()
                .filter(entry -> prompt.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .min(Integer::compareTo)
                .orElse(null);
    }

    /** Matches patterns such as "20 minutes" or "2 hours". */
    private Integer parseExplicitMinutes(String prompt) {
        Matcher minutes = MINUTES.matcher(prompt);
        if (minutes.find()) {
            return clampMinutes(Integer.parseInt(minutes.group(1)));
        }
        Matcher hours = HOURS.matcher(prompt);
        if (hours.find()) {
            return clampMinutes(Integer.parseInt(hours.group(1)) * 60);
        }
        return null;
    }

    /**
     * Guards against a prompt like "999 hours" producing an overflow, or a
     * filter so wide it is meaningless.
     */
    private Integer clampMinutes(int minutes) {
        return Math.min(Math.max(minutes, 1), 600);
    }

    private String describe(String genre, List<String> tags,
                            Boolean multiplayer, Integer maxMinutes) {
        StringBuilder sb = new StringBuilder("Interpreted without AI as: ");
        sb.append(genre == null ? "any genre" : genre);
        if (multiplayer != null) {
            sb.append(Boolean.TRUE.equals(multiplayer) ? ", multiplayer" : ", single player");
        }
        if (maxMinutes != null) {
            sb.append(", sessions up to ").append(maxMinutes).append(" minutes");
        }
        if (!tags.isEmpty()) {
            sb.append(", tagged ").append(String.join(" and ", tags));
        }
        return sb.toString();
    }
}
