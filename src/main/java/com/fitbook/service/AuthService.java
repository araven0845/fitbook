package com.fitbook.service;

import com.fitbook.dto.SessionUser;

public interface AuthService {

    /** Verifies the credentials; the role comes from the user's row in the database. */
    SessionUser login(String username, String password);
}
