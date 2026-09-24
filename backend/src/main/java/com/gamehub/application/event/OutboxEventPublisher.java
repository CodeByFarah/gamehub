package com.gamehub.application.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamehub.domain.event.GameHubEvent;
import com.gamehub.infrastructure.persistence.entity.OutboxEventEntity;
import com.gamehub.infrastructure.persistence.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes an event into the transactional outbox.
 *
 * <h2>The contract</h2>
 * This method MUST be called inside the caller transaction. It is annotated
 * MANDATORY rather than REQUIRED, so calling it without one throws at startup
 * of the call instead of quietly opening its own transaction.
 *
 * <p>That distinction is the whole design. With REQUIRED, a caller that forgot
 * its own transaction would get a separate one for the outbox row, and the
 * event would commit even when the business change rolled back. The failure
 * would be invisible in testing and would surface as achievements awarded for
 * games that never finished. MANDATORY makes the mistake impossible to make.
 *
 * <p>Nothing is sent to Kafka here. The row is just a row; {@link OutboxRelay}
 * publishes it after the transaction commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    /**
     * @throws IllegalStateException via Spring when called with no active
     *                               transaction, which is a programming error
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(GameHubEvent event, UUID aggregateId) {
        OutboxEventEntity row = new OutboxEventEntity();
        // The outbox row id IS the event id. Reusing it is what lets a
        // consumer recognise a redelivery of the same logical event.
        row.setId(event.eventId());
        row.setEventType(event.type());
        row.setSchemaVersion(event.schemaVersion());
        row.setAggregateId(aggregateId);
        row.setPartitionKey(event.partitionKey());
        row.setOccurredAt(event.occurredAt());
        row.setPayload(serialise(event));

        outbox.save(row);
        log.debug("queued {} {} for publication", event.type(), event.eventId());
    }

    /** As above, additionally tagging the row for per-user and per-game queries. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(GameHubEvent event, UUID aggregateId, UUID userId, UUID gameId) {
        OutboxEventEntity row = new OutboxEventEntity();
        row.setId(event.eventId());
        row.setEventType(event.type());
        row.setSchemaVersion(event.schemaVersion());
        row.setAggregateId(aggregateId);
        row.setPartitionKey(event.partitionKey());
        row.setUserId(userId);
        row.setGameId(gameId);
        row.setOccurredAt(event.occurredAt());
        row.setPayload(serialise(event));

        outbox.save(row);
    }

    /**
     * Serialisation failure is unrecoverable and must abort the transaction.
     *
     * <p>Swallowing it would commit the business change with no event, which
     * is precisely the silent divergence the outbox exists to prevent. Better
     * to fail the whole request loudly.
     */
    private String serialise(GameHubEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not serialise " + event.type() + " " + event.eventId(), e);
        }
    }
}
