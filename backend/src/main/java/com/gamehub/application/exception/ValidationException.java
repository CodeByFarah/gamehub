package com.gamehub.application.exception;

import com.gamehub.api.error.ErrorCode;

public class ValidationException extends GameHubException {

    public ValidationException(String message) {
        super(400, ErrorCode.VALIDATION_FAILED, message);
    }
}
