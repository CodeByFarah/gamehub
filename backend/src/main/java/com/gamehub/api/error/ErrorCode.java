package com.gamehub.api.error;

/**
 * Stable error codes.
 *
 * <p>Constants rather than free text, because these are part of the API
 * contract: a client branches on them. Collecting them here makes the full set
 * reviewable, and makes it obvious when a new endpoint invents a code that
 * duplicates an existing one.
 */
public final class ErrorCode {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String ALREADY_QUEUED = "ALREADY_QUEUED";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCode() {
    }
}
