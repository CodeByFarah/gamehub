package com.gamehub.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.application.port.AiClient;
import com.gamehub.config.properties.AiProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gemini adapter.
 *
 * <h2>The rule this class exists to enforce</h2>
 * Never trust model output. Everything that comes back is treated as untrusted
 * text from a third party that may be wrong, malformed, slow, or absent, and
 * it is validated field by field before anything downstream sees it.
 *
 * <p>Concretely, all of the following are handled and none of them reach a
 * caller as an exception: a timeout, an HTTP error, a rate limit, an empty
 * candidate list, a response that is not JSON, JSON of the wrong shape, a
 * genre that does not exist in the catalogue, a negative session length, a
 * confidence outside 0 to 1. Each degrades to the deterministic fallback.
 *
 * <h2>Layered protection</h2>
 * <ol>
 *   <li><b>Timeout</b> on the HTTP client, well below the inbound request
 *       budget, so a slow provider cannot park a request thread.</li>
 *   <li><b>Retry</b> with exponential backoff and jitter, for transient
 *       failures only, and deliberately few attempts.</li>
 *   <li><b>Circuit breaker</b>, so sustained failure stops costing latency
 *       within seconds rather than retrying into a dead provider.</li>
 *   <li><b>Validation</b> of the parsed result.</li>
 *   <li><b>Fallback</b> to {@link FallbackAiClient} whenever any of the above
 *       gives up.</li>
 * </ol>
 *
 * <p>Retry sits inside the circuit breaker on purpose. The other way round,
 * the breaker would count one logical failure three times and trip on a single
 * unlucky request.
 */
@Slf4j
public class GeminiAiClient implements AiClient {

    /**
     * Response schema handed to the model.
     *
     * <p>Gemini supports a response schema, and using it removes an entire
     * class of failure: the model cannot return prose wrapped in a markdown
     * fence instead of JSON. Validation still runs afterwards, because a
     * schema constrains shape and not meaning: nothing stops the model
     * returning a syntactically perfect genre that does not exist.
     */
    private static final Map<String, Object> INTENT_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "genre", Map.of("type", "STRING", "nullable", true),
                    "tags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                    "multiplayer", Map.of("type", "BOOLEAN", "nullable", true),
                    "maxSessionMinutes", Map.of("type", "INTEGER", "nullable", true),
                    "minRating", Map.of("type", "NUMBER", "nullable", true),
                    "interpretation", Map.of("type", "STRING"),
                    "confidence", Map.of("type", "NUMBER")),
            "required", List.of("interpretation", "confidence"));

    private final RestClient restClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final FallbackAiClient fallback;
    private final Counter successes;
    private final Counter failures;
    private final Counter invalidResponses;
    private final Timer latency;

    public GeminiAiClient(AiProperties properties,
                          ObjectMapper objectMapper,
                          FallbackAiClient fallback,
                          MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fallback = fallback;

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(timeoutFactory(properties.getTimeout()))
                .build();

        this.successes = Counter.builder("gamehub.ai.request")
                .tag("result", "success").register(meterRegistry);
        this.failures = Counter.builder("gamehub.ai.request")
                .tag("result", "failure").register(meterRegistry);
        // Separated from failures deliberately. A provider that is up but
        // returning unusable output is a different problem from one that is
        // down, and it needs a different response: a prompt change, not a
        // page.
        this.invalidResponses = Counter.builder("gamehub.ai.request")
                .tag("result", "invalid").register(meterRegistry);
        this.latency = Timer.builder("gamehub.ai.latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory(
            Duration timeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    @Override
    @CircuitBreaker(name = "gemini", fallbackMethod = "intentFallback")
    @Retry(name = "gemini")
    public SearchIntent extractSearchIntent(String prompt, CatalogueVocabulary vocabulary) {
        String sanitised = sanitise(prompt);

        JsonNode response = latency.record(() ->
                callGenerateContent(intentPrompt(sanitised, vocabulary), INTENT_SCHEMA));

        String json = extractText(response);
        if (json == null) {
            invalidResponses.increment();
            log.warn("Gemini returned no usable candidate for an intent request");
            return fallback.extractSearchIntent(prompt, vocabulary);
        }

        SearchIntent parsed = parseIntent(json, vocabulary);
        if (parsed == null) {
            invalidResponses.increment();
            return fallback.extractSearchIntent(prompt, vocabulary);
        }

        successes.increment();
        return parsed;
    }

    @Override
    @CircuitBreaker(name = "gemini", fallbackMethod = "answerFallback")
    @Retry(name = "gemini")
    public AssistantAnswer answer(String question, List<GameContext> context) {
        String sanitised = sanitise(question);

        JsonNode response = latency.record(() ->
                callGenerateContent(assistantPrompt(sanitised, context), null));

        String text = extractText(response);
        if (text == null || text.isBlank()) {
            invalidResponses.increment();
            return fallback.answer(question, context);
        }

        successes.increment();
        return new AssistantAnswer(
                truncate(text, 2_000),
                context.stream().map(GameContext::gameId).toList(),
                // Grounded only when context was actually supplied. Without it
                // the model answers from training data about a catalogue it
                // has never seen, and the UI must say so.
                !context.isEmpty(),
                false);
    }

    @Override
    public boolean isAvailable() {
        return properties.hasApiKey();
    }

    /**
     * Resilience4j fallback for intent extraction.
     *
     * <p>The signature mirrors the guarded method plus a Throwable. Invoked
     * when the breaker is open, when retries are exhausted, or on any
     * exception the call throws.
     */
    @SuppressWarnings("unused")
    private SearchIntent intentFallback(String prompt, CatalogueVocabulary vocabulary,
                                        Throwable cause) {
        failures.increment();
        log.warn("Gemini intent extraction unavailable ({}), using deterministic fallback",
                cause.getClass().getSimpleName());
        return fallback.extractSearchIntent(prompt, vocabulary);
    }

    @SuppressWarnings("unused")
    private AssistantAnswer answerFallback(String question, List<GameContext> context,
                                           Throwable cause) {
        failures.increment();
        log.warn("Gemini assistant unavailable ({}), using deterministic fallback",
                cause.getClass().getSimpleName());
        return fallback.answer(question, context);
    }

    private JsonNode callGenerateContent(String prompt, Map<String, Object> schema) {
        Map<String, Object> generationConfig = schema == null
                ? Map.of("temperature", 0.2, "maxOutputTokens", 1024)
                : Map.of("temperature", 0.1,
                         "maxOutputTokens", 1024,
                         "responseMimeType", "application/json",
                         "responseSchema", schema);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", generationConfig);

        return restClient.post()
                // The key goes in a header, never a query string. Query
                // strings land in access logs, proxy logs and browser history.
                .uri("/v1beta/models/{model}:generateContent", properties.getModel())
                .header("x-goog-api-key", properties.getApiKey())
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Digs the generated text out of the response, tolerating any shape. */
    private String extractText(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            // Also the shape returned when a response was blocked by safety
            // filters, which is a legitimate outcome rather than an error.
            return null;
        }
        JsonNode text = candidates.get(0).path("content").path("parts").path(0).path("text");
        return text.isTextual() ? text.asText() : null;
    }

    /**
     * Parses and validates the intent.
     *
     * <p>This is where model output stops being trusted. Every field is range
     * checked, and genre and tags are intersected with the real catalogue
     * vocabulary, so a hallucinated value becomes an absent filter rather than
     * a query that silently matches nothing.
     *
     * @return null when the payload cannot be used, which the caller turns
     *         into a fallback rather than an error
     */
    private SearchIntent parseIntent(String json, CatalogueVocabulary vocabulary) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("Gemini returned text that is not JSON: {}", truncate(json, 200));
            return null;
        }
        if (!node.isObject()) {
            return null;
        }

        String genre = textOrNull(node, "genre");
        // Not in the catalogue means not a filter. Passing it through would
        // produce zero results and look like a broken search.
        if (genre != null && !containsIgnoreCase(vocabulary.genres(), genre)) {
            log.debug("discarding hallucinated genre from Gemini: {}", genre);
            genre = null;
        }

        List<String> tags = new ArrayList<>();
        JsonNode tagNode = node.path("tags");
        if (tagNode.isArray()) {
            for (JsonNode tag : tagNode) {
                if (tag.isTextual() && containsIgnoreCase(vocabulary.tags(), tag.asText())) {
                    tags.add(tag.asText());
                }
            }
        }

        Boolean multiplayer = node.path("multiplayer").isBoolean()
                ? node.path("multiplayer").asBoolean()
                : null;

        Integer maxMinutes = null;
        if (node.path("maxSessionMinutes").isInt()) {
            int value = node.path("maxSessionMinutes").asInt();
            // A negative or absurd duration is discarded rather than clamped.
            // Clamping would invent a constraint the user never expressed.
            if (value > 0 && value <= 600) {
                maxMinutes = value;
            }
        }

        Double minRating = null;
        if (node.path("minRating").isNumber()) {
            double value = node.path("minRating").asDouble();
            if (value >= 0 && value <= 5) {
                minRating = value;
            }
        }

        double confidence = node.path("confidence").isNumber()
                ? Math.min(1.0d, Math.max(0.0d, node.path("confidence").asDouble()))
                : 0.0d;

        String interpretation = textOrNull(node, "interpretation");

        return new SearchIntent(genre, List.copyOf(tags), multiplayer, maxMinutes, minRating,
                interpretation == null
                        ? "Interpreted your request"
                        : truncate(interpretation, 300),
                confidence, false);
    }

    private String intentPrompt(String prompt, CatalogueVocabulary vocabulary) {
        return """
                You convert a player request into search filters for a game catalogue.

                Choose genre only from this list, or null if none fit:
                %s

                Choose tags only from this list, or an empty array:
                %s

                Rules:
                - Never invent a genre or tag that is not listed above.
                - Set a field to null when the request does not mention it.
                - interpretation is one short sentence describing what you understood.
                - confidence is between 0 and 1.

                Player request: %s
                """.formatted(
                String.join(", ", vocabulary.genres()),
                String.join(", ", vocabulary.tags()),
                prompt);
    }

    private String assistantPrompt(String question, List<GameContext> context) {
        StringBuilder games = new StringBuilder();
        for (GameContext game : context) {
            games.append("- ").append(game.title())
                 .append(" (").append(game.genre()).append(", ")
                 .append(game.avgSessionMinutes()).append(" min, ")
                 .append(game.multiplayer() ? "multiplayer" : "single player").append("): ")
                 .append(game.shortDescription()).append('\n');
        }

        return """
                You answer questions about games in this catalogue.

                Catalogue:
                %s

                Rules:
                - Answer only from the catalogue above.
                - If the catalogue does not contain the answer, say so plainly.
                - Never invent a game that is not listed.
                - Keep the answer under 120 words.

                Question: %s
                """.formatted(games.toString(), question);
    }

    /**
     * Blunt prompt-injection mitigation.
     *
     * <p>Strips the delimiters and directive phrasing most commonly used to
     * break out of a prompt, and caps the length. This reduces attack surface
     * and is explicitly not a guarantee: no input filter makes an LLM
     * injection-proof.
     *
     * <p>The real containment is architectural. Model output is only ever used
     * as search filters drawn from a fixed vocabulary, or as display text. It
     * never becomes a query, a command, a file path, or an authorisation
     * decision, so the worst a successful injection achieves is a strange
     * search result.
     */
    private String sanitise(String input) {
        if (input == null) {
            return "";
        }
        String cleaned = input
                .replaceAll("(?i)ignore (all|any|previous|the above)[^.]*", " ")
                .replaceAll("(?i)system prompt", " ")
                .replace("```", " ")
                .replace("\u0000", " ")
                .trim();
        return truncate(cleaned, properties.getMaxPromptLength());
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        return values.stream().anyMatch(v -> v.equalsIgnoreCase(candidate));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
