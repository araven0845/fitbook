package com.fitbook.repository;

import com.fitbook.model.UserRecord;

import java.util.Optional;

public interface UserRepository {
    Optional<UserRecord> findByUsername(String username);
}
