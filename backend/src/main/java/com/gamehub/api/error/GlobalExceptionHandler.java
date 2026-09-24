package com.gamehub.api.error;

import com.gamehub.application.exception.GameHubException;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Objects;

/**
 * Turns every exception into the one documented error envelope.
 *
 * <h2>The rule this enforces</h2>
 * No exception reaches the client unshaped. A raw Spring error page leaks the
 * exception class, sometimes a stack trace, and always an inconsistent body
 * that clients cannot parse. Worse, a driver exception can carry a connection
 * string or a fragment of SQL containing user data.
 *
 * <p>So the catch-all at the bottom logs the real cause at ERROR with the
 * trace id and returns a generic message. The operator gets everything; the
 * caller gets a code, a correlation id, and nothing exploitable.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Tracer tracer;

    /**
     * Anything deliberately thrown by the application, carrying its own status
     * and code. Logged at WARN rather than ERROR: a 404 or a version conflict
     * is the system working, and paging on it trains people to ignore alerts.
     */
    @ExceptionHandler(GameHubException.class)
    public ResponseEntity<ApiError> handleGameHub(GameHubException e, HttpServletRequest request) {
        log.warn("{} on {}: {}", e.getCode(), request.getRequestURI(), e.getMessage());
        return ResponseEntity
                .status(e.getStatus())
                .body(base(e.getStatus(), e.getCode(), e.getMessage(), request)
                        .withMeta(e.getMeta()));
    }

    /** Bean validation on a request body. Reports every bad field at once. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {

        List<ApiError.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(
                        f.getField(),
                        // Never echo the rejected value verbatim: it may be a
                        // password or a save blob, and it would land in logs
                        // and in client-side error displays alike.
                        f.getRejectedValue() == null ? "null" : "<redacted>",
                        Objects.requireNonNullElse(f.getDefaultMessage(), "is invalid")))
                .toList();

        return ResponseEntity.badRequest()
                .body(base(400, ErrorCode.VALIDATION_FAILED,
                        "request validation failed", request).withDetails(fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParamValidation(
            ConstraintViolationException e, HttpServletRequest request) {

        List<ApiError.FieldError> fields = e.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(
                        v.getPropertyPath().toString(), "<redacted>", v.getMessage()))
                .toList();

        return ResponseEntity.badRequest()
                .body(base(400, ErrorCode.VALIDATION_FAILED,
                        "request validation failed", request).withDetails(fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformed(Exception e, HttpServletRequest request) {
        // The parser message is not echoed: it can quote the offending input.
        return ResponseEntity.badRequest()
                .body(base(400, ErrorCode.VALIDATION_FAILED,
                        "request body or parameter could not be parsed", request));
    }

    /**
     * A constraint the application could not check beforehand without racing.
     *
     * <p>The partial unique index on matchmaking tickets lands here when two
     * joins arrive at once. Mapped to 409 rather than 500, because it is the
     * database correctly rejecting a duplicate, not a defect.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(
            DataIntegrityViolationException e, HttpServletRequest request) {

        log.warn("constraint violation on {}: {}", request.getRequestURI(),
                e.getMostSpecificCause().getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(base(409, ErrorCode.CONFLICT,
                        "the request conflicts with the current state of the resource",
                        request));
    }

    /**
     * Postgres or Redis unreachable. 503 with Retry-After rather than 500: the
     * client should back off and retry, and a 500 tells it the opposite.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiError> handleDependencyDown(
            DataAccessResourceFailureException e, HttpServletRequest request) {

        log.error("datastore unreachable on {}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(base(503, ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "a required datastore is temporarily unavailable", request));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(
            AuthenticationException e, HttpServletRequest request) {
        // Never distinguish unknown user from wrong password. Doing so turns
        // the login endpoint into a username enumeration oracle.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(base(401, ErrorCode.UNAUTHORIZED, "authentication failed", request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(base(403, ErrorCode.FORBIDDEN,
                        "you do not have permission to perform this action", request));
    }

    /**
     * Everything unanticipated. The only handler that logs at ERROR with the
     * full trace, and the only one whose message is deliberately vague.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = currentTraceId();
        log.error("unhandled exception on {} [traceId={}]", request.getRequestURI(), traceId, e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, ErrorCode.INTERNAL_ERROR,
                        "an unexpected error occurred, quote the trace id when reporting it",
                        request.getRequestURI(), traceId));
    }

    private ApiError base(int status, String code, String message, HttpServletRequest request) {
        return ApiError.of(status, code, message, request.getRequestURI(), currentTraceId());
    }

    /** Null-safe: a span may be unsampled, and an error must still be returned. */
    private String currentTraceId() {
        return tracer.currentSpan() == null
                ? "untraced"
                : tracer.currentSpan().context().traceId();
    }
}
