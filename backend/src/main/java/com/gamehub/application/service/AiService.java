package com.gamehub.application.service;

import com.gamehub.api.dto.AiDtos.AiAssistantResponse;
import com.gamehub.api.dto.AiDtos.AiSearchIntent;
import com.gamehub.api.dto.AiDtos.AiSearchResponse;
import com.gamehub.api.dto.GameSummaryResponse;
import com.gamehub.application.port.AiClient;
import com.gamehub.application.port.AiClient.CatalogueVocabulary;
import com.gamehub.application.port.AiClient.GameContext;
import com.gamehub.application.port.AiClient.SearchIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Turns natural language into catalogue results.
 *
 * <h2>Two-stage design</h2>
 * The model only ever decides <em>what to search for</em>. The search itself
 * is an ordinary indexed Postgres query. The model never produces SQL, never
 * sees the database, and never influences which rows a user is allowed to see.
 *
 * <p>That split is the entire security posture of the AI feature. A prompt
 * injection can at worst make the filters odd; it cannot exfiltrate data or
 * bypass authorisation, because model output never reaches a place where those
 * are decided.
 *
 * <p>It is also what makes the feature testable: intent extraction is
 * verified against a stub client, and execution is verified against a real
 * database, without either test needing the other half.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    /** Results returned for one prompt. */
    private static final int SEARCH_LIMIT = 20;

    /** Games handed to the assistant as grounding context. */
    private static final int CONTEXT_LIMIT = 8;

    /**
     * The vocabulary the model may choose from.
     *
     * <p>Static for now, matching the seeded catalogue. A larger catalogue
     * would derive this from a cached DISTINCT query; the important part is
     * that a vocabulary exists at all, because it is what makes a hallucinated
     * genre detectable.
     */
    private static final List<String> GENRES = List.of(
            "Action", "Adventure", "Puzzle", "Racing", "Strategy",
            "Sports", "RPG", "Simulation", "Arcade", "Card");

    private static final List<String> TAGS = List.of(
            "competitive", "cooperative", "casual", "relaxing", "fast-paced",
            "story-rich", "retro", "multiplayer", "single-player", "tournament",
            "skill-based", "family-friendly", "roguelike", "sandbox");

    private final AiClient aiClient;
    private final GameCatalogService catalogue;

    /**
     * Natural-language discovery.
     *
     * <p>Stage one extracts structured filters. Stage two runs them. When
     * extraction produced nothing usable, the prompt is used as a plain
     * full-text query instead, so the user still gets results rather than an
     * empty screen.
     */
    public AiSearchResponse search(String prompt) {
        SearchIntent intent = aiClient.extractSearchIntent(
                prompt, new CatalogueVocabulary(GENRES, TAGS));

        List<GameSummaryResponse> results = executeIntent(intent, prompt);

        return new AiSearchResponse(toDto(intent), results, intent.degraded());
    }

    private List<GameSummaryResponse> executeIntent(SearchIntent intent, String prompt) {
        boolean hasFilters = intent.genre() != null
                || !intent.tags().isEmpty()
                || intent.multiplayer() != null
                || intent.maxSessionMinutes() != null
                || intent.minRating() != null;

        if (!hasFilters) {
            // Nothing structured came back. Falling through to full-text
            // search is better than returning the whole catalogue ordered by
            // popularity, which would look like the filters were ignored.
            return catalogue.search(prompt, SEARCH_LIMIT, 0);
        }

        List<GameSummaryResponse> filtered = catalogue.filter(
                intent.genre(), intent.multiplayer(), intent.maxSessionMinutes(),
                intent.minRating(), intent.tags(), SEARCH_LIMIT, 0);

        if (filtered.isEmpty()) {
            // Over-constrained. Rather than showing nothing, widen to
            // full-text on the original prompt and let relevance decide.
            log.debug("AI filters matched nothing, widening to full-text search");
            return catalogue.search(prompt, SEARCH_LIMIT, 0);
        }
        return filtered;
    }

    /**
     * Answers a question, grounded in real catalogue rows.
     *
     * <p>Context is gathered first and handed to the model. Asking without it
     * would make the model answer from training data about a catalogue it has
     * never seen, which produces confident descriptions of games that do not
     * exist here.
     */
    public AiAssistantResponse assist(String question, UUID gameId) {
        List<GameSummaryResponse> context = gameId != null
                ? catalogue.byIds(List.of(gameId))
                : catalogue.search(question, CONTEXT_LIMIT, 0);

        AiClient.AssistantAnswer answer = aiClient.answer(question, context.stream()
                .map(AiService::toContext)
                .toList());

        return new AiAssistantResponse(
                answer.answer(), context, answer.grounded(), answer.degraded());
    }

    private static GameContext toContext(GameSummaryResponse game) {
        return new GameContext(
                game.id().toString(), game.title(), game.genre(), game.tags(),
                game.shortDescription(), game.avgSessionMinutes(), game.supportsMultiplayer());
    }

    private static AiSearchIntent toDto(SearchIntent intent) {
        return new AiSearchIntent(
                intent.genre(), intent.tags(), intent.multiplayer(),
                intent.maxSessionMinutes(), intent.minRating(),
                intent.interpretation(), intent.confidence(), intent.degraded());
    }
}
