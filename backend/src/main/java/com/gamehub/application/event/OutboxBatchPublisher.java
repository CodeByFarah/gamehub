package com.gamehub.application.event;

import com.gamehub.infrastructure.persistence.entity.OutboxEventEntity;
import com.gamehub.infrastructure.persistence.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Claims, publishes and stamps one batch of outbox rows.
 *
 * <h2>Why this is a separate bean from {@link OutboxRelay}</h2>
 * It has to be. The relay invokes this work through a lambda handed to the
 * distributed lease, and Spring transaction management is implemented with a
 * proxy: a call that originates inside the same object never crosses the
 * proxy, so a {@code @Transactional} method called as {@code this::drainBatch}
 * silently runs with no transaction at all.
 *
 * <p>That failure would be particularly nasty here, because the claim query
 * relies on {@code FOR UPDATE SKIP LOCKED}. Without a surrounding transaction
 * those row locks are released the instant the statement returns, so two
 * instances could claim and publish the same rows, and every event would be
 * duplicated. The code would look correct and the tests would pass on a single
 * instance.
 *
 * <p>Putting the transactional work in its own bean makes the call cross the
 * proxy and the annotation actually take effect.
 */
@Slf4j
@Component
public class OutboxBatchPublisher {

    /**
     * Rows per run. Large enough to amortise the round trip, small enough that
     * a run holds its row locks briefly. An unbounded batch would let a
     * backlog spike hold locks for the entire catch-up.
     */
    private static final int BATCH_SIZE = 200;

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter published;
    private final Counter failed;

    public OutboxBatchPublisher(OutboxEventRepository outbox,
                                KafkaTemplate<String, String> kafkaTemplate,
                                MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.published = Counter.builder("gamehub.outbox.published")
                .description("Outbox rows successfully published to Kafka")
                .register(meterRegistry);
        this.failed = Counter.builder("gamehub.outbox.failed")
                .description("Outbox rows whose publication attempt failed")
                .register(meterRegistry);
    }

    /**
     * Transactional, so the locks taken by SKIP LOCKED are held for the whole
     * batch and released on commit.
     *
     * <p>Sends are awaited rather than fired and forgotten. An asynchronous
     * send would let the transaction commit and stamp rows published before
     * Kafka had acknowledged them, reintroducing exactly the loss the outbox
     * exists to prevent.
     *
     * @return how many rows were published
     */
    @Transactional
    public int drainBatch() {
        List<OutboxEventEntity> batch = outbox.claimUnpublishedBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }

        List<UUID> succeeded = new ArrayList<>(batch.size());

        for (OutboxEventEntity row : batch) {
            try {
                Message<String> message = MessageBuilder
                        .withPayload(row.getPayload())
                        .setHeader(KafkaHeaders.TOPIC, row.getEventType().topic())
                        // Partition key: same key means same partition means
                        // per-user ordering is preserved downstream.
                        .setHeader(KafkaHeaders.KEY, row.getPartitionKey())
                        .setHeader("eventId", row.getId().toString())
                        .setHeader("eventType", row.getEventType().name())
                        .setHeader("schemaVersion", String.valueOf(row.getSchemaVersion()))
                        .build();

                kafkaTemplate.send(message).get();
                succeeded.add(row.getId());
            } catch (InterruptedException e) {
                // Restore the flag and stop. Swallowing it would leave the
                // thread uninterruptible and block a graceful shutdown.
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // One bad row must not abort the batch. It keeps its lock
                // until commit and becomes eligible again on the next run.
                failed.increment();
                row.markFailed(e.getMessage());
                log.warn("failed to publish outbox row {} (attempt {}): {}",
                        row.getId(), row.getPublishAttempts(), e.getMessage());
            }
        }

        if (!succeeded.isEmpty()) {
            outbox.markPublished(succeeded, Instant.now());
            published.increment(succeeded.size());
            log.debug("published {} of {} outbox rows", succeeded.size(), batch.size());
        }
        return succeeded.size();
    }
}
