package com.fitbook.util;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Slot times are stored as local 'yyyy-MM-dd HH:mm' text, which sorts lexicographically. */
public final class SlotTime {

    public static final DateTimeFormatter DB_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SlotTime() {}

    public static String now(Clock clock) {
        return DB_FORMAT.format(LocalDateTime.now(clock));
    }

    public static String format(LocalDateTime time) {
        return DB_FORMAT.format(time);
    }
}
