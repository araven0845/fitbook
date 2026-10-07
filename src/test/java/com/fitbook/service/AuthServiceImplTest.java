package com.fitbook.service;

import com.fitbook.dto.SessionUser;
import com.fitbook.exception.InvalidCredentialsException;
import com.fitbook.model.Role;
import com.fitbook.model.UserRecord;
import com.fitbook.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private AuthServiceImpl service;
    private UserRecord provider;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(userRepository, encoder);
        provider = new UserRecord(5, "coach", encoder.encode(PASSWORD), Role.PROVIDER, "Coach Name");
    }

    @Test
    void login_correctPassword_returnsSessionUserWithRoleFromTheRow() {
        when(userRepository.findByUsername("coach")).thenReturn(Optional.of(provider));

        SessionUser user = service.login("coach", PASSWORD);

        assertEquals(5, user.id());
        assertEquals(Role.PROVIDER, user.role());
        assertEquals("Coach Name", user.fullName());
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        when(userRepository.findByUsername("coach")).thenReturn(Optional.of(provider));

        assertThrows(InvalidCredentialsException.class, () -> service.login("coach", "wrong"));
    }

    @Test
    void login_unknownUser_throwsSameInvalidCredentials() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        InvalidCredentialsException e = assertThrows(InvalidCredentialsException.class, () -> service.login("ghost", PASSWORD));
        assertEquals("Invalid username or password", e.getMessage());
    }

    @Test
    void passwordsAreStoredAsBCryptHashesNotPlaintext() {
        assertNotEquals(PASSWORD, provider.passwordHash());
        assertTrue(provider.passwordHash().startsWith("$2"));
    }
}
