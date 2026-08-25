package com.notificationapp.ratelimiter.utils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public class MutableClock extends Clock {
    private Instant currentInstant;
    private final ZoneId zoneId;

    public MutableClock(Instant initialInstant, ZoneId zoneId) {
        this.currentInstant = initialInstant;
        this.zoneId = zoneId;
    }

    @Override
    public ZoneId getZone() { return zoneId; }

    @Override
    public Clock withZone(ZoneId zone) { return new MutableClock(currentInstant, zone); }

    @Override
    public Instant instant() { return currentInstant; }

    // Helper method to advance time deterministically in tests
    public void advanceBy(Duration duration) {
        this.currentInstant = this.currentInstant.plus(duration);
    }
}
