package com.fitbook.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BookingRequest(
        @NotNull(message = "slotId is required") @Positive(message = "slotId must be positive") Long slotId,
        @NotNull(message = "serviceId is required") @Positive(message = "serviceId must be positive") Long serviceId
) {}
