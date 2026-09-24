package com.gamehub.application.service;

import com.gamehub.api.dto.CloudSaveResponse;
import com.gamehub.api.dto.CloudSaveUploadRequest;
import com.gamehub.application.event.OutboxEventPublisher;
import com.gamehub.application.exception.CloudSaveConflictException;
import com.gamehub.application.exception.ResourceNotFoundException;
import com.gamehub.application.exception.ValidationException;
import com.gamehub.domain.event.CloudSaveUpdatedEvent;
import com.gamehub.infrastructure.persistence.entity.CloudSaveEntity;
import com.gamehub.infrastructure.persistence.entity.CloudSaveHistoryEntity;
import com.gamehub.infrastructure.persistence.repository.CloudSaveHistoryRepository;
import com.gamehub.infrastructure.persistence.repository.CloudSaveRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cloud save reads and writes under optimistic concurrency control.
 *
 * <p>The problem, the race, the solution and the trade-offs are written up in
 * docs/cloud-saves.md. In short: two devices holding the same version both try
 * to write; exactly one wins, and the loser is told what it lost rather than
 * having its write silently dropped or silently applied over newer data.
 */
@Slf4j
@Service
public class CloudSaveService {

    private final CloudSaveRepository saves;
    private final CloudSaveHistoryRepository history;
    private final OutboxEventPublisher events;
    private final Counter accepted;
    private final Counter conflicts;

    public CloudSaveService(CloudSaveRepository saves,
                            CloudSaveHistoryRepository history,
                            OutboxEventPublisher events,
                            MeterRegistry meterRegistry) {
        this.saves = saves;
        this.history = history;
        this.events = events;
        this.accepted = Counter.builder("gamehub.cloudsave.write")
                .tag("result", "accepted").register(meterRegistry);
        // Tracked because the conflict rate is the evidence for whether
        // optimistic control is still the right choice here. A rate climbing
        // into double digits would be the signal to revisit the strategy.
        this.conflicts = Counter.builder("gamehub.cloudsave.write")
                .tag("result", "conflict").register(meterRegistry);
    }

    @Transactional(readOnly = true)
    public List<CloudSaveResponse> listFor(UUID userId) {
        return saves.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                // Metadata only. The listing must not ship several megabytes
                // of blob just to render a list of slots.
                .map(save -> toResponse(save, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public CloudSaveResponse download(UUID userId, UUID gameId, short slot) {
        CloudSaveEntity save = saves.findByUserIdAndGameIdAndSlot(userId, gameId, slot)
                .orElseThrow(() -> new ResourceNotFoundException("cloud save", gameId + "/" + slot));
        return toResponse(save, true);
    }

    /**
     * Creates or updates a save.
     *
     * <p>Two distinct paths, failing differently on purpose:
     *
     * <ul>
     *   <li>No row yet: insert. A concurrent insert loses on the unique index
     *       (user, game, slot) and surfaces as a 409 through the integrity
     *       handler, which is correct because the second writer did not know
     *       about the first.</li>
     *   <li>Row exists: conditional UPDATE. Zero rows affected means another
     *       device wrote first, and that is a 409 carrying the server state so
     *       the client can merge.</li>
     * </ul>
     */
    @Transactional
    public CloudSaveResponse upload(UUID userId, CloudSaveUploadRequest request) {
        byte[] payload = decodeAndVerify(request);
        short slot = (short) request.slot();

        Optional<CloudSaveEntity> existing =
                saves.findByUserIdAndGameIdAndSlot(userId, request.gameId(), slot);

        if (existing.isEmpty()) {
            return createNew(userId, request, payload, slot);
        }

        CloudSaveEntity save = existing.get();

        // Fail fast on an obviously stale version, so the common conflict is
        // detected without a write. The conditional UPDATE below is still what
        // guarantees safety; this is an optimisation and deliberately not the
        // enforcement point.
        if (request.expectedVersion() != save.getVersion()) {
            conflicts.increment();
            throw new CloudSaveConflictException(
                    request.expectedVersion(), save.getVersion(), save.getChecksum());
        }

        // Snapshot the version about to be replaced.
        history.save(CloudSaveHistoryEntity.snapshotOf(save));

        int updated = saves.updateIfVersionMatches(
                save.getId(), request.expectedVersion(), payload,
                payload.length, request.checksum(), request.deviceId());

        if (updated == 0) {
            // Lost the race between the read above and this statement. This
            // branch is what actually prevents data loss, and it is why the
            // early check cannot be the only guard.
            conflicts.increment();
            CloudSaveEntity current = saves.findById(save.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("cloud save", save.getId()));
            log.info("cloud save conflict for user {} game {} slot {}: client had {}, server holds {}",
                    userId, request.gameId(), slot, request.expectedVersion(), current.getVersion());
            throw new CloudSaveConflictException(
                    request.expectedVersion(), current.getVersion(), current.getChecksum());
        }

        long newVersion = request.expectedVersion() + 1;
        accepted.increment();

        events.publish(new CloudSaveUpdatedEvent(
                        UUID.randomUUID(), Instant.now(), save.getId(), userId, request.gameId(),
                        slot, newVersion, request.expectedVersion(), payload.length,
                        request.checksum(), request.deviceId()),
                save.getId(), userId, request.gameId());

        return new CloudSaveResponse(save.getId(), request.gameId(), slot, newVersion,
                payload.length, request.checksum(), request.deviceId(), Instant.now(), null);
    }

    private CloudSaveResponse createNew(UUID userId, CloudSaveUploadRequest request,
                                        byte[] payload, short slot) {
        if (request.expectedVersion() != 0) {
            // The client believes it is updating something that does not
            // exist. Usually the save was deleted, or the client is pointed at
            // the wrong environment. Either way, do not silently create it at
            // a version the client invented.
            throw new CloudSaveConflictException(request.expectedVersion(), 0, "");
        }

        CloudSaveEntity save = new CloudSaveEntity();
        save.setUserId(userId);
        save.setGameId(request.gameId());
        save.setSlot(slot);
        save.setVersion(1L);
        save.replacePayload(payload, request.checksum());
        save.setDeviceId(request.deviceId());

        CloudSaveEntity persisted = saves.save(save);
        history.save(CloudSaveHistoryEntity.snapshotOf(persisted));
        accepted.increment();

        events.publish(new CloudSaveUpdatedEvent(
                        UUID.randomUUID(), Instant.now(), persisted.getId(), userId,
                        request.gameId(), slot, 1L, 0L, payload.length,
                        request.checksum(), request.deviceId()),
                persisted.getId(), userId, request.gameId());

        return toResponse(persisted, false);
    }

    /**
     * Decodes the payload and verifies the client checksum.
     *
     * <p>Verified server-side rather than trusted, because the point of a
     * checksum is to catch a truncated or corrupted upload. A checksum the
     * server never checks detects nothing: a client that corrupted the bytes
     * would usually compute the digest over the corrupted copy anyway.
     */
    private byte[] decodeAndVerify(CloudSaveUploadRequest request) {
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(request.payloadBase64());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("payloadBase64 is not valid base64");
        }

        if (payload.length == 0) {
            throw new ValidationException("payload must not be empty");
        }
        if (payload.length > CloudSaveEntity.MAX_PAYLOAD_BYTES) {
            throw new ValidationException(
                    "payload of " + payload.length + " bytes exceeds the 1 MiB limit");
        }

        String actual = sha256Hex(payload);
        if (!actual.equalsIgnoreCase(request.checksum())) {
            throw new ValidationException(
                    "checksum mismatch: the upload was corrupted or truncated in transit");
        }
        return payload;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform spec. Unreachable on a real JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private CloudSaveResponse toResponse(CloudSaveEntity save, boolean includePayload) {
        String encoded = includePayload
                ? Base64.getEncoder().encodeToString(save.getPayload())
                : null;
        return new CloudSaveResponse(save.getId(), save.getGameId(), save.getSlot(),
                save.getVersion(), save.getPayloadSizeBytes(), save.getChecksum(),
                save.getDeviceId(), save.getUpdatedAt(), encoded);
    }
}
