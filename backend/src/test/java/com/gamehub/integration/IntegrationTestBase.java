package com.gamehub.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need real infrastructure.
 *
 * <h2>Why real containers and not H2 or an embedded broker</h2>
 * Nearly every interesting behaviour in this system is database-specific and
 * would be invisible against a generic in-memory database:
 *
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED} in the outbox relay, which H2 does not
 *       implement.</li>
 *   <li>{@code ON CONFLICT DO NOTHING}, the idempotency mechanism for both
 *       consumers and achievements.</li>
 *   <li>Partial unique indexes, which enforce one active matchmaking ticket
 *       per player and are the entire concurrency defence there.</li>
 *   <li>{@code websearch_to_tsquery} and trigram similarity, which are the
 *       search implementation.</li>
 * </ul>
 *
 * A suite that passed against H2 would prove nothing about any of them, and
 * would give false confidence exactly where the risk is concentrated.
 *
 * <h2>Container lifecycle</h2>
 * Started once per JVM as static fields, not per test class. Postgres takes a
 * few seconds and Kafka rather longer; restarting per class would turn a
 * minute of tests into many minutes of container startup.
 *
 * <p>Isolation therefore comes from transactional rollback and from unique
 * data per test, not from a fresh database. Tests that genuinely need a clean
 * slate truncate explicitly.
 *
 * <h2>Reuse</h2>
 * {@code withReuse(true)} keeps containers alive between runs, removing
 * startup from the inner development loop. It is opt-in per developer through
 * {@code ~/.testcontainers.properties} and off in CI, where a fresh
 * environment matters more than speed.
 */
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    /**
     * Pinned to the same major version as production. Testing against a
     * different major would verify the behaviours above on an engine that is
     * not the one running them.
     */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("gamehub")
                    .withUsername("gamehub")
                    .withPassword("test")
                    .withReuse(true);

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"))
                    .withReuse(true);

    static {
        // Started in a static block rather than with @Container, because
        // @Container on a static field in an abstract base gets restarted per
        // concrete subclass. Explicit start gives one set of containers for
        // the whole JVM, which is the intent.
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    /**
     * Injects the randomly assigned container ports.
     *
     * <p>Random on purpose: fixed ports collide with whatever the developer
     * already has running from docker-compose, and that collision produces a
     * suite that passes or fails depending on what else is open on the
     * machine.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Flyway runs against the container, so every integration test
        // exercises the real migrations. A broken migration fails here rather
        // than on deploy.
        registry.add("spring.flyway.enabled", () -> true);
    }
}
