package com.gamehub.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error shape every endpoint returns.
 *
 * <p>One shape for every failure means a client writes one error handler
 * rather than one per endpoint. The Android client in this repository relies
 * on that: it parses this envelope and decides retry behaviour from
 * {@code error}, never from a parsed message string.
 *
 * @param timestamp when the failure was produced, ISO-8601 UTC
 * @param status    HTTP status, duplicated in the body so the envelope stays
 *                  meaningful once it has been logged or forwarded away from
 *                  its response
 * @param error     stable machine-readable code. Clients branch on this.
 *                  Never localised and never reworded, because changing it is
 *                  a breaking API change even though it looks like prose.
 * @param message   human-readable text for developers. Safe to change.
 * @param path      request path
 * @param traceId   correlation id, also on every log line for this request.
 *                  A user can quote it in a bug report and it leads straight
 *                  to the trace, which is the whole reason it is surfaced.
 * @param details   field-level validation failures, omitted when empty
 * @param meta      error-specific context, for example the server version on
 *                  a cloud-save conflict so the client can merge immediately
 *                  instead of issuing another request to find out
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId,
        List<FieldError> details,
        Map<String, Object> meta) {

    public record FieldError(String field, String rejectedValue, String reason) {
    }

    public static ApiError of(int status, String code, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, code, message, path, traceId, List.of(), Map.of());
    }

    public ApiError withDetails(List<FieldError> fieldErrors) {
        return new ApiError(timestamp, status, error, message, path, traceId, fieldErrors, meta);
    }

    public ApiError withMeta(Map<String, Object> extra) {
        return new ApiError(timestamp, status, error, message, path, traceId, details, extra);
    }
}
