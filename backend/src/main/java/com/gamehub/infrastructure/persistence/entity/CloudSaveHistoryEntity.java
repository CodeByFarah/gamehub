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
 * Append-only record of every accepted cloud-save write.
 *
 * <p>Two jobs. It lets a player recover from a bad overwrite, and it turns
 * "how often do save conflicts actually happen?" into a query rather than a
 * guess, which is what tells us whether the conflict-resolution strategy needs
 * to get smarter.
 */
@Entity
@Table(name = "cloud_save_history")
@Getter
@Setter
@NoArgsConstructor
public class CloudSaveHistoryEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "cloud_save_id", nullable = false)
    private UUID cloudSaveId;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private byte[] payload;

    @Column(nullable = false)
    private String checksum;

    @Column(name = "device_id")
    private String deviceId;

    @Column(name = "written_at", nullable = false, updatable = false)
    private Instant writtenAt = Instant.now();

    public static CloudSaveHistoryEntity snapshotOf(CloudSaveEntity save) {
        CloudSaveHistoryEntity history = new CloudSaveHistoryEntity();
        history.setCloudSaveId(save.getId());
        history.setVersion(save.getVersion());
        history.setPayload(save.getPayload());
        history.setChecksum(save.getChecksum());
        history.setDeviceId(save.getDeviceId());
        return history;
    }

    @Override
    public String toString() {
        return "CloudSaveHistoryEntity(cloudSaveId=" + cloudSaveId + ", version=" + version + ")";
    }
}
