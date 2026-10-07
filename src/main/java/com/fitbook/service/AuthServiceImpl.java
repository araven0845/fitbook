package com.fitbook.service;

import com.fitbook.dto.SessionUser;
import com.fitbook.exception.InvalidCredentialsException;
import com.fitbook.model.UserRecord;
import com.fitbook.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // Checked when the username does not exist, so unknown users cost the same BCrypt time as wrong passwords.
    private final String dummyHash;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Override
    public SessionUser login(String username, String password) {
        UserRecord user = userRepository.findByUsername(username).orElse(null);
        String hash = user != null ? user.passwordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(password, hash);
        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException("Invalid username or password");
        }
        return new SessionUser(user.id(), user.username(), user.fullName(), user.role());
    }
}
