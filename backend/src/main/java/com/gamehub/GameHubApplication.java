package com.gamehub;

import com.gamehub.config.properties.AiProperties;
import com.gamehub.config.properties.CacheProperties;
import com.gamehub.config.properties.MatchmakingProperties;
import com.gamehub.config.properties.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point.
 *
 * <p>Scheduling is enabled here because three background jobs depend on it: the
 * matchmaker tick, the transactional outbox relay, and the ticket expiry
 * sweeper. Each of those runs on every instance, so each guards itself with a
 * Redis lease rather than assuming it is the only one running. See
 * docs/architecture.md for why that pattern is used instead of electing a
 * single leader.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({
        MatchmakingProperties.class,
        CacheProperties.class,
        AiProperties.class,
        SecurityProperties.class
})
public class GameHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameHubApplication.class, args);
    }
}
