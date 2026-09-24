package com.gamehub.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Upload or update a cloud save.
 *
 * @param expectedVersion the version the client believes is current. Required,
 *                        with no default. Making it optional would let a
 *                        client omit it and silently get last-write-wins,
 *                        which is exactly the data loss this design exists to
 *                        prevent. Zero means "create, and fail if one exists".
 * @param payloadBase64   the save blob. Base64 rather than a binary body so
 *                        one JSON envelope carries the version and checksum
 *                        alongside it. The 33 percent size cost is bounded by
 *                        the 1 MiB ceiling and buys a much simpler contract.
 * @param checksum        SHA-256 of the decoded bytes, verified server-side.
 *                        Catches truncated uploads that would otherwise be
 *                        stored as a corrupt save and only noticed by the
 *                        player.
 */
public record CloudSaveUploadRequest(
        @NotNull(message = "gameId is required")
        UUID gameId,

        @Min(value = 0, message = "slot must be between 0 and 9")
        @Max(value = 9, message = "slot must be between 0 and 9")
        int slot,

        @Min(value = 0, message = "expectedVersion must not be negative")
        long expectedVersion,

        @NotBlank(message = "payloadBase64 is required")
        @Size(max = 1_400_000, message = "payload exceeds the 1 MiB limit once decoded")
        String payloadBase64,

        @NotBlank(message = "checksum is required")
        @Size(min = 64, max = 64, message = "checksum must be a 64 character SHA-256 hex digest")
        String checksum,

        String deviceId) {
}
