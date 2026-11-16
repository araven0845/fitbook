package com.fitbook.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock tests can move forward by hand, to exercise expiry without sleeping. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
