package com.fitbook.notification;

import com.fitbook.FitbookIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The mock notification service as a black box: accepts a DTO, stores it, ignores duplicates, rejects bad input. */
@FitbookIntegrationTest
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private String json(String key) {
        return "{\"idempotencyKey\":\"" + key + "\",\"recipient\":\"user@example.com\",\"type\":\"BOOKING_CONFIRMED\",\"message\":\"hi\"}";
    }

    @Test
    void validNotification_isAcceptedAndStored_andADuplicateIsAcknowledgedWithoutAnotherRow() throws Exception {
        String key = "k-" + UUID.randomUUID();

        mockMvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(json(key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(false));
        mockMvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(json(key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE idempotency_key = ?", Long.class, key));
    }

    @Test
    void invalidNotification_is400() throws Exception {
        mockMvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"\",\"recipient\":\"not-an-email\",\"type\":\"BOOKING_CONFIRMED\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJson_is400_notA500() throws Exception {
        mockMvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
