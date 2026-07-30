package com.example.srmremoter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ControlRateTest {
    @Test
    public void rateIsClampedToSupportedRange() {
        assertEquals(1, ControlRate.clamp(0));
        assertEquals(1, ControlRate.clamp(1));
        assertEquals(50, ControlRate.clamp(50));
        assertEquals(100, ControlRate.clamp(5000));
    }

    @Test
    public void periodSupportsOneToOneHundredHertz() {
        assertEquals(1_000_000_000L, ControlRate.periodNanos(1));
        assertEquals(20_000_000L, ControlRate.periodNanos(50));
        assertEquals(10_000_000L, ControlRate.periodNanos(100));
    }
}
