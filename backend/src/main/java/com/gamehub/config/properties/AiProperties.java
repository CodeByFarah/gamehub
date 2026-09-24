package com.gamehub.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the AI provider, bound from the gamehub.ai prefix.
 *
 * <h2>The API key is not here</h2>
 * {@link #apiKey} is populated from the GEMINI_API_KEY environment variable and
 * is deliberately allowed to be blank. A blank key is a supported operating
 * mode, not a misconfiguration: the application starts, and
 * {@code AiClientConfig} wires the deterministic fallback implementation
 * instead of the Gemini one.
 *
 * <p>That choice is what lets a reviewer clone this repository and run the
 * whole stack with no Google account, while the AI endpoints still return
 * useful, honest responses rather than 500s. See docs/ai-architecture.md.
 *
 * <p>The field is never logged. {@link #toString()} is overridden below so that
 * a stray debug log of this object cannot leak the key, which is the usual way
 * credentials escape into log aggregation.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gamehub.ai")
public class AiProperties {

    /**
     * Gemini API key. Blank means run in fallback mode.
     *
     * <p>Not @NotBlank on purpose. See the class comment.
     */
    private String apiKey = "";

    @NotBlank
    private String model = "gemini-2.5-flash";

    @NotBlank
    private String baseUrl = "https://generativelanguage.googleapis.com";

    /**
     * Hard ceiling on one provider call.
     *
     * <p>Sized well below the inbound HTTP read timeout so that a slow provider
     * surfaces as a fast, clean fallback rather than as a request thread parked
     * long enough to exhaust the pool. A hung dependency taking down unrelated
     * endpoints is the classic way a nice-to-have feature becomes an outage.
     */
    @NotNull
    private Duration timeout = Duration.ofSeconds(8);

    /**
     * Retries for transient failures only.
     *
     * <p>Kept deliberately low. Retrying a request that is timing out because
     * the provider is overloaded adds load to an overloaded provider, and the
     * circuit breaker is the mechanism meant to handle sustained failure.
     */
    @Min(0)
    private int maxRetries = 2;

    /**
     * Maximum characters accepted in a user prompt.
     *
     * <p>Bounds both cost and prompt-injection surface. Anything longer is
     * rejected with 400 at the API edge, before it reaches the provider.
     */
    @Min(1)
    private int maxPromptLength = 500;

    /** How long a generated recommendation set stays cached in Redis. */
    @NotNull
    private Duration recommendationTtl = Duration.ofHours(6);

    /**
     * Redacts the key. Never remove this: it is the only thing standing between
     * a debug-level log statement and a credential in the log store.
     */
    @Override
    public String toString() {
        return "AiProperties(model=" + model
                + ", baseUrl=" + baseUrl
                + ", timeout=" + timeout
                + ", maxRetries=" + maxRetries
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<absent>" : "<redacted>")
                + ")";
    }

    /** Whether a real provider call is possible at all. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
