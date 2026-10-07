package com.fitbook;

import com.fitbook.model.Role;
import com.fitbook.repository.SlotRepository;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, role-based access (401/403), input validation, and the shape of error responses. */
@FitbookIntegrationTest
class AuthAndRbacTest {

    private static final String PASSWORD = "integration-test-password";
    private static final AtomicInteger SLOT_COUNTER = new AtomicInteger(1000);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SlotRepository slotRepository;

    private String customerName;
    private String otherCustomerName;
    private String providerName;

    @BeforeEach
    void createUsers() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        customerName = "cust_" + id;
        otherCustomerName = "cust2_" + id;
        providerName = "prov_" + id;
        insertUser(customerName, Role.CUSTOMER);
        insertUser(otherCustomerName, Role.CUSTOMER);
        long providerUserId = insertUser(providerName, Role.PROVIDER);
        jdbc.update("INSERT INTO providers (user_id, specialty) VALUES (?, 'Test')", providerUserId);
    }

    private long insertUser(String username, Role role) {
        jdbc.update("INSERT INTO users (username, password_hash, role, full_name, email) VALUES (?, ?, ?, ?, ?)",
                username, passwordEncoder.encode(PASSWORD), role.name(), username, username + "@test.example");
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").param("username", username).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login must create a session");
        return session;
    }

    private long newOpenSlot() {
        LocalDateTime start = LocalDateTime.now().plusDays(90).withSecond(0).withNano(0).plusMinutes(SLOT_COUNTER.incrementAndGet());
        return slotRepository.insert(1, 1, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
    }

    // ---- login ----

    @Test
    void login_withCorrectPassword_redirectsByRoleReadFromTheDatabase() throws Exception {
        mockMvc.perform(post("/login").param("username", customerName).param("password", PASSWORD))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/browse"));
        mockMvc.perform(post("/login").param("username", providerName).param("password", PASSWORD))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/provider"));
    }

    @Test
    void login_withWrongPassword_isRejectedWithoutCreatingASession() throws Exception {
        MvcResult result = mockMvc.perform(post("/login").param("username", customerName).param("password", "wrong"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertNull(result.getRequest().getSession(false), "failed login must not create a session");
    }

    @Test
    void login_withBlankFields_isABadRequest() throws Exception {
        mockMvc.perform(post("/login").param("username", "").param("password", "")).andExpect(status().isBadRequest());
    }

    @Test
    void seedAccountPasswordsAreBCryptHashes() {
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM users WHERE username LIKE 'coach_%' OR username LIKE 'member_%'", String.class);
        assertEquals(4, hashes.size());
        hashes.forEach(h -> assertTrue(h.startsWith("$2"), "stored password must be a BCrypt hash"));
    }

    // ---- RBAC ----

    @Test
    void loggedOut_protectedPage_is401ForApiClientsAndRedirectsBrowsersToLogin() throws Exception {
        mockMvc.perform(get("/my-appointments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/my-appointments").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound()).andExpect(header().string("Location", "/login"));
        mockMvc.perform(get("/provider")).andExpect(status().isUnauthorized());
    }

    @Test
    void customer_isRejectedWith403_onEveryProviderOnlyEndpoint() throws Exception {
        MockHttpSession session = login(customerName);

        mockMvc.perform(get("/provider").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/provider/appointments").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/provider/slots").session(session).param("serviceId", "1").param("startTime", "2099-01-01T09:00"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/provider/slots/1/remove").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void provider_isRejectedWith403_onEveryCustomerOnlyEndpoint() throws Exception {
        MockHttpSession session = login(providerName);

        mockMvc.perform(get("/my-appointments").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/book").session(session).param("slotId", "1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/book").session(session).param("slotId", "1").param("serviceId", "1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/appointments/1/cancel").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void rightRole_canReachItsOwnPages() throws Exception {
        mockMvc.perform(get("/my-appointments").session(login(customerName))).andExpect(status().isOk());
        mockMvc.perform(get("/provider").session(login(providerName))).andExpect(status().isOk());
        mockMvc.perform(get("/provider/appointments").session(login(providerName))).andExpect(status().isOk());
    }

    @Test
    void publicPages_needNoLogin() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/browse")).andExpect(status().isOk());
        mockMvc.perform(get("/slots")).andExpect(status().isOk());
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void logout_endsTheSession() throws Exception {
        MockHttpSession session = login(customerName);
        mockMvc.perform(post("/logout").session(session)).andExpect(status().isFound());
        assertTrue(session.isInvalid());
    }

    // ---- booking flow errors: 400 / 404 / 409 / 403 ----

    @Test
    void booking_withInvalidInput_is400_withAClearMessage() throws Exception {
        MockHttpSession session = login(customerName);

        mockMvc.perform(post("/book").session(session).param("slotId", "-5").param("serviceId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("slotId must be positive")));
        mockMvc.perform(post("/book").session(session)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/book").session(session).param("slotId", "abc").param("serviceId", "1")).andExpect(status().isBadRequest());
    }

    @Test
    void booking_unknownSlot_is404() throws Exception {
        mockMvc.perform(post("/book").session(login(customerName)).param("slotId", "999999").param("serviceId", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void booking_aSlotAlreadyTaken_is409_andTheLoserCanSeeNoInternals() throws Exception {
        long slotId = newOpenSlot();
        mockMvc.perform(post("/book").session(login(customerName)).param("slotId", String.valueOf(slotId)).param("serviceId", "1"))
                .andExpect(status().isFound());

        String body = mockMvc.perform(post("/book").session(login(otherCustomerName)).param("slotId", String.valueOf(slotId)).param("serviceId", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("Exception"), body);
        assertFalse(body.contains("at com.fitbook"), body);
    }

    @Test
    void cancelling_someoneElsesAppointment_is403_andTheOwnerCanCancelTheirOwn() throws Exception {
        long slotId = newOpenSlot();
        MockHttpSession owner = login(customerName);
        mockMvc.perform(post("/book").session(owner).param("slotId", String.valueOf(slotId)).param("serviceId", "1"))
                .andExpect(status().isFound());
        long appointmentId = jdbc.queryForObject("SELECT id FROM appointments WHERE slot_id = ?", Long.class, slotId);

        mockMvc.perform(post("/appointments/" + appointmentId + "/cancel").session(login(otherCustomerName)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/confirmation/" + appointmentId).session(login(otherCustomerName)))
                .andExpect(status().isForbidden());
        assertEquals("BOOKED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointmentId));

        mockMvc.perform(post("/appointments/" + appointmentId + "/cancel").session(owner)).andExpect(status().isFound());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appointmentId));
    }

    @Test
    void provider_cannotRemoveAnotherProvidersSlot_orABookedSlot() throws Exception {
        long slotId = newOpenSlot(); // belongs to provider 1 (Maya), not the test provider
        MockHttpSession provider = login(providerName);

        mockMvc.perform(post("/provider/slots/" + slotId + "/remove").session(provider)).andExpect(status().isForbidden());
    }

    @Test
    void provider_createsAndRemovesOwnSlot() throws Exception {
        MockHttpSession provider = login(providerName);
        String start = SlotTime.format(LocalDateTime.now().plusDays(45).withSecond(0).withNano(0).plusMinutes(SLOT_COUNTER.incrementAndGet()))
                .replace(' ', 'T');

        mockMvc.perform(post("/provider/slots").session(provider).param("serviceId", "1").param("startTime", start))
                .andExpect(status().isFound());
        long slotId = jdbc.queryForObject(
                "SELECT s.id FROM availability_slots s JOIN providers p ON p.id = s.provider_id JOIN users u ON u.id = p.user_id WHERE u.username = ?",
                Long.class, providerName);

        mockMvc.perform(post("/provider/slots").session(provider).param("serviceId", "1").param("startTime", start))
                .andExpect(status().isConflict()); // same time again overlaps
        mockMvc.perform(post("/provider/slots/" + slotId + "/remove").session(provider)).andExpect(status().isFound());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM availability_slots WHERE id = ?", String.class, slotId));
    }

    // ---- error responses never leak internals ----

    @Test
    void unexpectedException_is500_withAGenericMessageAndNoStackTraceOrInternalDetail() throws Exception {
        String body = mockMvc.perform(get("/test-only/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("secret internal detail"), body);
        assertFalse(body.contains("IllegalStateException"), body);
        assertFalse(body.contains("jdbc:sqlite"), body);
        assertFalse(body.contains("\n\tat "), body);
    }

    @Test
    void unknownPath_is404Json_andBrowsersGetTheErrorPage() throws Exception {
        mockMvc.perform(get("/no-such-page")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/no-such-page").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
    }

    @Test
    void invalidDateFilter_is400() throws Exception {
        mockMvc.perform(get("/slots").param("date", "13/45/2026")).andExpect(status().isBadRequest());
    }
}
