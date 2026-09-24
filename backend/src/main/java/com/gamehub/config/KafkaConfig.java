package com.gamehub.config;

import com.gamehub.domain.event.EventType;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer error handling and topic declarations.
 *
 * <h2>The failure taxonomy</h2>
 * A consumer failure is one of two kinds, and conflating them is how systems
 * lose data or stall:
 *
 * <ul>
 *   <li><b>Transient</b>: Postgres briefly unreachable, a lock timeout, a
 *       deadlock. Retrying works. Sending these to a dead-letter topic would
 *       discard perfectly good events over a two-second blip.</li>
 *   <li><b>Poison</b>: a payload this consumer can never handle, such as a
 *       schema it does not understand or a null where one is required.
 *       Retrying never works, and retrying forever blocks the partition
 *       behind it, so every later event for every other player stops.</li>
 * </ul>
 *
 * <p>The configuration below retries with backoff, then dead-letters. That
 * ordering is the whole point: transient problems resolve themselves, and a
 * genuinely poisonous record is set aside so the partition keeps moving.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    /**
     * Partition count.
     *
     * <p>Three, matching the listener concurrency, because a partition is the
     * unit of parallelism: a fourth consumer thread against three partitions
     * would sit idle. Chosen as a modest number that can be raised later;
     * partitions can be added to a topic but never removed, and raising the
     * count changes which partition a key lands on, so ordering guarantees
     * only hold from that point forward.
     */
    private static final int PARTITIONS = 3;

    /**
     * Replication factor 1, which is correct for the single-broker local
     * setup and wrong for production. Cloud deployment overrides this; see
     * docs/deployment.md.
     */
    private static final short REPLICAS = 1;

    @Bean
    public NewTopic gameEventsTopic() {
        return TopicBuilder.name(EventType.Topics.GAME_EVENTS)
                .partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    public NewTopic achievementEventsTopic() {
        return TopicBuilder.name(EventType.Topics.ACHIEVEMENT_EVENTS)
                .partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    public NewTopic matchEventsTopic() {
        return TopicBuilder.name(EventType.Topics.MATCH_EVENTS)
                .partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean
    public NewTopic cloudSaveEventsTopic() {
        return TopicBuilder.name(EventType.Topics.CLOUD_SAVE_EVENTS)
                .partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * Retry then dead-letter.
     *
     * <h3>Backoff</h3>
     * Exponential from 500ms, capped at 10s, with a hard ceiling of about a
     * minute of total elapsed time. The ceiling matters: {@code
     * max.poll.interval.ms} is five minutes, and a retry sequence that
     * outlasts it would get the whole consumer evicted from the group,
     * triggering a rebalance and replaying the entire in-flight batch. So the
     * retry budget is deliberately well inside the poll interval.
     *
     * <h3>Dead-letter routing</h3>
     * The recoverer sends a failed record to {@code <topic>.DLT} on the same
     * partition number. Keeping the partition means a human reading the DLT
     * can still reason about ordering and about which key was affected.
     *
     * <p>Nothing consumes the DLT automatically, which is intentional. A
     * poison record needs a person to look at it; a process that silently
     * reprocessed or deleted it would hide the bug that produced it.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> {
                    log.error("dead-lettering record from {} partition {} offset {}: {}",
                            record.topic(), record.partition(), record.offset(),
                            exception.getMessage());
                    return new TopicPartition(
                            record.topic() + EventType.Topics.DLT_SUFFIX, record.partition());
                });

        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0d);
        backOff.setMaxInterval(10_000L);
        backOff.setMaxElapsedTime(60_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Deserialisation and validation failures can never succeed on a
        // retry, so they skip straight to the dead-letter topic. Retrying them
        // would burn the whole backoff budget to reach the same conclusion,
        // while holding up every later record on the partition.
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);

        handler.setRetryListeners((record, exception, attempt) ->
                log.warn("retry {} for record from {} offset {}: {}",
                        attempt, record.topic(), record.offset(), exception.getMessage()));

        return handler;
    }
}
