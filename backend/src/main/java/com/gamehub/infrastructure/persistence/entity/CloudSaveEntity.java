package com.gamehub.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A cloud save slot.
 *
 * <h2>Why there is no @Version here</h2>
 * JPA optimistic locking would work, but it would hide the mechanism. The
 * version in this design is not an implementation detail of the ORM: it is
 * part of the public API contract, returned to the client and presented back
 * on the next write. Modelling it as a plain column, updated by an explicit
 * conditional UPDATE in the repository, keeps that contract visible and lets
 * the service distinguish a genuine version conflict from any other
 * persistence failure.
 *
 * <p>The write is:
 * <pre>
 *   UPDATE cloud_saves SET version = version + 1, ...
 *   WHERE id = :id AND version = :expected
 * </pre>
 * which affects one row or zero. Zero means another device got there first,
 * and the service turns that into 409 CONFLICT carrying the server state, so
 * the client can merge rather than guess. See docs/cloud-saves.md.
 */
@Entity
@Table(name = "cloud_saves")
@Getter
@Setter
@NoArgsConstructor
public class CloudSaveEntity {

    /** Hard ceiling, mirrored by ck_cloud_saves_size in the schema. */
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(nullable = false)
    private short slot;

    @Column(nullable = false)
    private long version = 1L;

    @Column(nullable = false)
    private byte[] payload;

    @Column(name = "payload_size_bytes", nullable = false)
    private int payloadSizeBytes;

    @Column(nullable = false)
    private String checksum;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Sets payload and its derived size together, so the two can never
     * disagree. ck_cloud_saves_size_matches enforces the same thing in the
     * database, which is what catches any path that bypasses this method.
     */
    public void replacePayload(byte[] newPayload, String newChecksum) {
        if (newPayload == null || newPayload.length == 0) {
            throw new IllegalArgumentException("a cloud save payload must not be empty");
        }
        if (newPayload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "payload of " + newPayload.length + " bytes exceeds the "
                            + MAX_PAYLOAD_BYTES + " byte limit");
        }
        this.payload = newPayload;
        this.payloadSizeBytes = newPayload.length;
        this.checksum = newChecksum;
    }

    /** Never print the payload. A 1 MiB blob in a log line helps nobody. */
    @Override
    public String toString() {
        return "CloudSaveEntity(id=" + id + ", userId=" + userId + ", gameId=" + gameId
                + ", slot=" + slot + ", version=" + version
                + ", payloadSizeBytes=" + payloadSizeBytes + ")";
    }
}
