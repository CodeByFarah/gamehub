package com.gamehub.config;

import com.gamehub.config.properties.MatchmakingProperties;
import com.gamehub.domain.matchmaking.GreedyMatchmakingEngine;
import com.gamehub.domain.matchmaking.MatchCandidateScorer;
import com.gamehub.domain.matchmaking.MatchmakingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the pure matchmaking domain into the Spring context.
 *
 * <p>This is the only place that knows both worlds. The engine, scorer and
 * policy have no Spring annotations at all, which is what lets them be
 * constructed directly in a unit test with no container and no mocks.
 * Declaring them as beans here is the whole of the cost of that decision.
 */
@Configuration
public class MatchmakingConfig {

    /**
     * Built through {@link MatchmakingProperties#toPolicy()}, so the record
     * cross-field invariants are checked during startup. A policy with
     * maxSkillWindowElo below baseSkillWindowElo fails the context refresh
     * rather than surfacing at the first tick.
     */
    @Bean
    public MatchmakingPolicy matchmakingPolicy(MatchmakingProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    public MatchCandidateScorer matchCandidateScorer(MatchmakingPolicy policy) {
        return new MatchCandidateScorer(policy);
    }

    @Bean
    public GreedyMatchmakingEngine greedyMatchmakingEngine(MatchCandidateScorer scorer,
                                                           MatchmakingProperties properties) {
        return new GreedyMatchmakingEngine(scorer, properties.getCandidateLimit());
    }
}
