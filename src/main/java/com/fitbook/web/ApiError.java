package com.fitbook.web;

import java.time.Instant;

/** JSON error body: a status code and a safe message, never a stack trace. */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {}
