package com.gamehub.application.exception;

import lombok.Getter;

import java.util.Map;

/**
 * Base for failures the API is expected to translate into a specific status.
 *
 * <p>Carries its own HTTP status and error code so the exception handler stays
 * a thin mapper instead of a long instanceof chain that has to be edited every
 * time a new failure mode is added.
 *
 * <p>Extends RuntimeException so it does not force try/catch through layers
 * that have nothing useful to do about it. The handler is the only place that
 * catches these.
 */
@Getter
public abstract class GameHubException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Map<String, Object> meta;

    protected GameHubException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    protected GameHubException(int status, String code, String message, Map<String, Object> meta) {
        super(message);
        this.status = status;
        this.code = code;
        this.meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    /**
     * Stack traces are suppressed for this hierarchy.
     *
     * <p>These represent expected outcomes, such as a version conflict or a
     * missing row, not defects. Filling in a stack trace for each one costs
     * real CPU on a hot path, and the traces add nothing: the code and message
     * already say everything the operator needs. Genuine bugs are unchecked
     * exceptions from elsewhere and keep their traces.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
