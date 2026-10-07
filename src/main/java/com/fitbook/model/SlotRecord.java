package com.fitbook.model;

/** Internal row representation of `availability_slots`, including the optimistic-lock version. */
public record SlotRecord(long id, long providerId, long serviceId, String startTime, String endTime,
                         SlotStatus status, long version) {}
