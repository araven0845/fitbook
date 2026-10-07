package com.fitbook.exception;

import org.springframework.http.HttpStatus;

/** Base for deliberate, client-safe failures. The message is written to be shown to the user. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
