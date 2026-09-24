package com.gamehub.application.exception;

import com.gamehub.api.error.ErrorCode;

import java.util.Map;

public class RateLimitedException extends GameHubException {

    public RateLimitedException(long retryAfterSeconds) {
        super(429, ErrorCode.RATE_LIMITED,
                "too many requests, retry in " + retryAfterSeconds + " seconds",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
