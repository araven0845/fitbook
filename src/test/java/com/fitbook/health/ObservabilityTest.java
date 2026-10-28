package com.fitbook.health;

import com.fitbook.FitbookIntegrationTest;
import com.fitbook.dto.BookingRequest;
import com.fitbook.repository.SlotRepository;
import com.fitbook.repository.UserRepository;
import com.fitbook.service.BookingService;
import com.fitbook.util.SlotTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /health and the metrics endpoint, against the real app wiring. */
@FitbookIntegrationTest
class ObservabilityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void health_isUp_andIncludesADatabaseConnectivityCheck() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checks.database.status").value("UP"))
                .andExpect(jsonPath("$.checks.database.responseTimeMs").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void metrics_exposeBookingOutcomesAndLatency() throws Exception {
        long customer = userRepository.findByUsername("member_lena").orElseThrow().id();
        LocalDateTime start = LocalDateTime.now().plusDays(95).withSecond(0).withNano(0);
        long slotId = slotRepository.insert(1, 1, SlotTime.format(start), SlotTime.format(start.plusMinutes(30)));
        bookingService.book(customer, new BookingRequest(slotId, 1L));

        mockMvc.perform(get("/actuator/metrics/fitbook.bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fitbook.bookings"));
        mockMvc.perform(get("/actuator/metrics/fitbook.booking.latency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }
}
