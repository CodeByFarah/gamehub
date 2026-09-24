package com.gamehub.infrastructure.kafka;

import com.gamehub.infrastructure.persistence.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs consumer work exactly once per event, per consumer group.
 *
 * <h2>The problem</h2>
 * Kafka guarantees at-least-once delivery, and the outbox relay can also
 * republish a record it failed to stamp. So every consumer must assume it will
 * see the same event again. Without protection, a replayed GameCompleted event
 * increments games played twice, awards the same achievement again, and
 * inflates playtime.
 *
 * <h2>The mechanism</h2>
 * Before doing any work, insert a row into {@code processed_events} keyed by
 * (event id, consumer group) with {@code ON CONFLICT DO NOTHING}. A return of
 * zero means the event was already handled, so the work is skipped.
 *
 * <p>The insert must share a transaction with the work it guards, which is why
 * this method is {@code MANDATORY}. Called without an enclosing transaction it
 * throws rather than silently running unguarded.
 *
 * <p>If they were separate transactions, a crash between them would leave the
 * event marked processed with none of its effects applied, and the replay
 * would then skip it. That is the consumer-side version of exactly the
 * dual-write problem the outbox exists to solve on the producer side.
 *
 * <h2>Why the claim is not a check-then-act</h2>
 * A SELECT followed by an INSERT has a window in which two consumer threads
 * both see no row and both proceed. The atomic upsert has no such window, so
 * concurrency within one consumer group is safe as well as across replays.
 *
 * <p>Keyed by consumer group rather than globally, because one GameCompleted
 * event is legitimately processed three times: once each by statistics,
 * achievements and recommendations. A global key would let whichever ran first
 * suppress the other two.
 */
@Slf4j
@Component
public class IdempotentConsumer {

    private final ProcessedEventRepository processedEvents;
    private final Counter processed;
    private final Counter skipped;

    public IdempotentConsumer(ProcessedEventRepository processedEvents,
                              MeterRegistry meterRegistry) {
        this.processedEvents = processedEvents;
        this.processed = Counter.builder("gamehub.consumer.events")
                .tag("result", "processed")
                .description("Events handled for the first time")
                .register(meterRegistry);
        // A steady trickle is normal and healthy. A spike means the relay or
        // the broker is redelivering heavily, which is worth looking at even
        // though nothing is incorrect.
        this.skipped = Counter.builder("gamehub.consumer.events")
                .tag("result", "duplicate")
                .description("Events skipped because they were already handled")
                .register(meterRegistry);
    }

    /**
     * Runs {@code work} if this event has not been handled by this group.
     *
     * @return true if the work ran, false if it was a duplicate
     * @throws IllegalStateException via Spring when there is no active
     *                               transaction, which is a programming error
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean runOnce(UUID eventId, String consumerGroup, Consumer<UUID> work) {
        int claimed = processedEvents.claim(eventId, consumerGroup);

        if (claimed == 0) {
            skipped.increment();
            log.debug("skipping duplicate event {} for group {}", eventId, consumerGroup);
            return false;
        }

        work.accept(eventId);
        processed.increment();
        return true;
    }
}
