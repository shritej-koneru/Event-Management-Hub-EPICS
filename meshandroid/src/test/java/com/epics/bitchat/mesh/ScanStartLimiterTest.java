package com.epics.bitchat.mesh;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScanStartLimiterTest {

    @Test
    public void allowsFourStartsThenDefersUntilTheWindowFrees() {
        ScanStartLimiter limiter = new ScanStartLimiter();
        for (int i = 0; i < ScanStartLimiter.MAX_STARTS; i++) {
            assertEquals(0, limiter.tryAcquire(1_000 + i * 1_000L));
        }
        long wait = limiter.tryAcquire(5_000);
        assertTrue("5th start inside 30 s must be deferred", wait > 0);
        // First start was at 1 000 ms, so the window frees just after 31 000 ms.
        assertEquals(31_001 - 5_000, wait);
        assertEquals(0, limiter.tryAcquire(5_000 + wait));
    }

    @Test
    public void resetClearsHistory() {
        ScanStartLimiter limiter = new ScanStartLimiter();
        for (int i = 0; i < ScanStartLimiter.MAX_STARTS; i++) {
            limiter.tryAcquire(0);
        }
        limiter.reset();
        assertEquals(0, limiter.tryAcquire(1));
    }
}
