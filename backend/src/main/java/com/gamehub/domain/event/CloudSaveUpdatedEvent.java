package com.gamehub.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A cloud save was accepted at a new version.
 *
 * <p>Carries the version but deliberately not the payload. Save blobs run to
 * 1 MiB, and a Kafka topic is the wrong place to keep them: it would multiply
 * broker storage by the retention window for data that already lives in
 * Postgres. Consumers that need the bytes read them by id.
 *
 * @param previousVersion the version this write replaced, so a consumer can
 *                        detect a gap and know it missed an intermediate write
 */
public record CloudSaveUpdatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID cloudSaveId,
        UUID userId,
        UUID gameId,
        short slot,
        long version,
        long previousVersion,
        int payloadSizeBytes,
        String checksum,
        String deviceId) implements GameHubEvent {

    public CloudSaveUpdatedEvent {
        if (eventId == null || cloudSaveId == null || userId == null || gameId == null) {
            throw new IllegalArgumentException(
                    "eventId, cloudSaveId, userId and gameId are required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (version < 1 || version <= previousVersion) {
            throw new IllegalArgumentException(
                    "version must be positive and strictly greater than previousVersion, got "
                            + version + " after " + previousVersion);
        }
    }

    @Override
    public EventType type() {
        return EventType.CLOUD_SAVE_UPDATED;
    }

    @Override
    public String partitionKey() {
        return userId.toString();
    }
}
