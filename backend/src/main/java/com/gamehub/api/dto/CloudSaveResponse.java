package com.gamehub.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Cloud save metadata, and the payload only when explicitly downloaded.
 *
 * <p>The listing endpoint returns these with payloadBase64 null, so the Cloud
 * Saves screen does not transfer several megabytes to render a list of slots.
 */
public record CloudSaveResponse(
        UUID id,
        UUID gameId,
        int slot,
        long version,
        int payloadSizeBytes,
        String checksum,
        String deviceId,
        Instant updatedAt,
        String payloadBase64) {

    public CloudSaveResponse withoutPayload() {
        return new CloudSaveResponse(id, gameId, slot, version, payloadSizeBytes,
                checksum, deviceId, updatedAt, null);
    }
}
