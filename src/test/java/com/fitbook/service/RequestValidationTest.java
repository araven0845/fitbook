package com.fitbook.service;

import com.fitbook.dto.BookingRequest;
import com.fitbook.dto.CreateSlotRequest;
import com.fitbook.dto.LoginRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void bookingRequest_validIds_passes() {
        assertTrue(validator.validate(new BookingRequest(1L, 2L)).isEmpty());
    }

    @Test
    void bookingRequest_missingIds_isRejected() {
        assertEquals(2, validator.validate(new BookingRequest(null, null)).size());
    }

    @Test
    void bookingRequest_nonPositiveIds_isRejected() {
        assertEquals(2, validator.validate(new BookingRequest(0L, -3L)).size());
    }

    @Test
    void createSlotRequest_wellFormed_passes() {
        assertTrue(validator.validate(new CreateSlotRequest(1L, "2026-10-08T09:00")).isEmpty());
    }

    @Test
    void createSlotRequest_badTimeFormat_isRejected() {
        assertEquals(1, validator.validate(new CreateSlotRequest(1L, "tomorrow at nine")).size());
    }

    @Test
    void createSlotRequest_blankTimeAndMissingService_isRejected() {
        assertTrue(validator.validate(new CreateSlotRequest(null, "")).size() >= 2);
    }

    @Test
    void loginRequest_blankFields_areRejected() {
        assertEquals(2, validator.validate(new LoginRequest(" ", "")).size());
    }
}
