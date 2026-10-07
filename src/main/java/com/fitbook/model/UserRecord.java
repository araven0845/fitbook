package com.fitbook.model;

/** Internal row representation of `users` - holds the password hash, so it never leaves the service layer. */
public record UserRecord(long id, String username, String passwordHash, Role role, String fullName) {}
