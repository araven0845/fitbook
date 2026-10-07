package com.fitbook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** startTime is local time as produced by an HTML datetime-local input, e.g. 2026-10-08T09:00. */
public record CreateSlotRequest(
        @NotNull(message = "serviceId is required") @Positive(message = "serviceId must be positive") Long serviceId,
        @NotBlank(message = "startTime is required")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}", message = "startTime must look like 2026-10-08T09:00")
        String startTime
) {}
