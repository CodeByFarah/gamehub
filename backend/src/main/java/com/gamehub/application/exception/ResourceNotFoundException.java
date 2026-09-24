package com.gamehub.application.exception;

import com.gamehub.api.error.ErrorCode;

public class ResourceNotFoundException extends GameHubException {

    public ResourceNotFoundException(String resource, Object id) {
        super(404, ErrorCode.NOT_FOUND, resource + " " + id + " was not found");
    }
}
