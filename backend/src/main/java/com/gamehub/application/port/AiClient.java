package com.gamehub.application.port;

import java.util.List;

/**
 * The application view of an AI provider.
 *
 * <h2>Why a port at all</h2>
 * Nothing above this interface knows that Gemini exists. The service layer
 * asks for a structured intent or a grounded answer; the adapter decides how
 * to get one. That buys three things:
 *
 * <ul>
 *   <li>The provider can be swapped without touching a service or a test.</li>
 *   <li>The deterministic fallback is just another implementation, so
 *       degraded mode is a real code path rather than a special case wired
 *       through if-statements.</li>
 *   <li>Unit tests use a stub and need no network, no key and no budget.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * Implementations must never throw for provider trouble. A timeout, a rate
 * limit, malformed JSON or an open circuit are all normal operating
 * conditions for something that calls a third party over the internet, and
 * they are reported by returning a result marked degraded. Only a programming
 * error should escape as an exception.
 *
 * <p>That rule is what keeps a slow provider from becoming a slow API.
 */
public interface AiClient {

    /**
     * Turns a natural-language prompt into structured search parameters.
     *
     * @return the extracted intent, never null. A degraded result is a valid
     *         answer produced without the model.
     */
    SearchIntent extractSearchIntent(String prompt, CatalogueVocabulary vocabulary);

    /**
     * Answers a question about games, grounded in the supplied context.
     *
     * @param context game summaries the model may refer to. Supplied rather
     *                than relying on model knowledge, because a model asked
     *                about a catalogue it has never seen will confidently
     *                invent entries.
     */
    AssistantAnswer answer(String question, List<GameContext> context);

    /** Whether a real provider is reachable, used by the health indicator. */
    boolean isAvailable();

    /**
     * Structured output of intent extraction.
     *
     * <p>Every field is nullable because a prompt need not constrain every
     * dimension. "something relaxing" says nothing about session length, and
     * inventing a value there would filter out results the user wanted.
     *
     * @param degraded true when this came from the fallback rather than a model
     */
    record SearchIntent(
            String genre,
            List<String> tags,
            Boolean multiplayer,
            Integer maxSessionMinutes,
            Double minRating,
            String interpretation,
            double confidence,
            boolean degraded) {

        public static SearchIntent empty(String interpretation) {
            return new SearchIntent(null, List.of(), null, null, null,
                    interpretation, 0.0d, true);
        }
    }

    record AssistantAnswer(String answer, List<String> referencedGameIds,
                           boolean grounded, boolean degraded) {
    }

    /**
     * The vocabulary a model is allowed to choose from.
     *
     * <p>Passed into extraction so the model is constrained to values that
     * actually exist. Without it a model will happily return a genre like
     * "cozy roguelike deckbuilder", which matches nothing and produces an
     * empty result set that looks like a bug.
     */
    record CatalogueVocabulary(List<String> genres, List<String> tags) {
    }

    record GameContext(String gameId, String title, String genre,
                       List<String> tags, String shortDescription,
                       int avgSessionMinutes, boolean multiplayer) {
    }
}
