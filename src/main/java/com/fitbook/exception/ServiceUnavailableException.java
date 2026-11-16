package com.fitbook.exception;

import org.springframework.http.HttpStatus;

/** A dependency we need for this request (the AI model) is not configured or not answering. */
public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
