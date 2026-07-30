package com.example.srmremoter;

final class ControlRate {
    static final int MIN_HZ = 1;
    static final int MAX_HZ = 100;
    static final int DEFAULT_HZ = 50;
    static final int STEP_HZ = 1;

    private ControlRate() {
    }

    static int clamp(int rateHz) {
        return Math.max(MIN_HZ, Math.min(MAX_HZ, rateHz));
    }

    static long periodNanos(int rateHz) {
        return TimeUnitNanos.PER_SECOND / clamp(rateHz);
    }

    private static final class TimeUnitNanos {
        static final long PER_SECOND = 1_000_000_000L;

        private TimeUnitNanos() {
        }
    }
}
