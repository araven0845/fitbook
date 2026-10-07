package com.fitbook.dto;

import com.fitbook.model.Role;

import java.io.Serializable;

/** What the HTTP session remembers about the logged-in user (no password hash). */
public record SessionUser(long id, String username, String fullName, Role role) implements Serializable {}
