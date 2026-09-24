package com.gamehub.application.exception;

import com.gamehub.api.error.ErrorCode;

/**
 * Raised only when the AI path fails AND no fallback could be produced.
 *
 * <p>Rare by design. A provider timeout, a malformed response or an open
 * circuit all degrade to the deterministic fallback rather than failing, so
 * reaching this exception means both paths failed, which is a real outage
 * worth alerting on rather than routine provider flakiness.
 */
public class AiUnavailableException extends GameHubException {

    public AiUnavailableException(String reason) {
        super(503, ErrorCode.AI_UNAVAILABLE,
                "AI assistance is temporarily unavailable: " + reason);
    }
}
