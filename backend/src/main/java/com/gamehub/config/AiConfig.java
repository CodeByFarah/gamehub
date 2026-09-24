package com.gamehub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.application.port.AiClient;
import com.gamehub.config.properties.AiProperties;
import com.gamehub.infrastructure.ai.FallbackAiClient;
import com.gamehub.infrastructure.ai.GeminiAiClient;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Chooses the AI implementation at startup.
 *
 * <h2>Why a missing key is a supported mode, not an error</h2>
 * The instance starts either way. With a key it uses Gemini and falls back on
 * failure; without one it uses the deterministic client for everything.
 *
 * <p>That is deliberate. A reviewer can clone this repository and run the
 * whole stack with no Google account, and the AI endpoints still return
 * useful, honest answers rather than 503s. Requiring a key to boot would make
 * a paid third-party dependency a hard prerequisite for running a game
 * platform, which is the wrong coupling: AI here is a feature, not the
 * product.
 */
@Slf4j
@Configuration
public class AiConfig {

    /**
     * The fallback is always a bean, even when Gemini is active, because
     * {@link GeminiAiClient} delegates to it on every failure path.
     */
    @Bean
    public FallbackAiClient fallbackAiClient() {
        return new FallbackAiClient();
    }

    @Bean
    @Primary
    public AiClient aiClient(AiProperties properties,
                             ObjectMapper objectMapper,
                             FallbackAiClient fallback,
                             MeterRegistry meterRegistry) {

        if (!properties.hasApiKey()) {
            log.warn("GEMINI_API_KEY is not set. AI endpoints will run in degraded mode "
                    + "using deterministic keyword extraction. This is a supported "
                    + "configuration, see docs/ai-architecture.md.");
            return fallback;
        }

        log.info("Gemini AI client enabled with model {}", properties.getModel());
        return new GeminiAiClient(properties, objectMapper, fallback, meterRegistry);
    }
}
