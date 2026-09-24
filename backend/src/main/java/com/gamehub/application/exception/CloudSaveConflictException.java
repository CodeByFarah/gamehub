package com.gamehub.application.exception;

import com.gamehub.api.error.ErrorCode;

import java.util.Map;

/**
 * Raised when a cloud-save write presents a version the server has moved past.
 *
 * <p>The meta map carries the server current version and checksum. That is
 * deliberate: a 409 that only says "conflict" forces the client into a second
 * round trip to find out what it lost, during which the version can change
 * again. Returning the current state with the rejection lets the client merge
 * immediately, which is what makes the conflict recoverable rather than just
 * reported. See docs/cloud-saves.md.
 */
public class CloudSaveConflictException extends GameHubException {

    public CloudSaveConflictException(long expectedVersion, long actualVersion, String checksum) {
        super(409, ErrorCode.VERSION_CONFLICT,
                "cloud save has been updated by another client: expected version "
                        + expectedVersion + " but the server holds " + actualVersion,
                Map.of(
                        "expectedVersion", expectedVersion,
                        "serverVersion", actualVersion,
                        "serverChecksum", checksum));
    }
}
