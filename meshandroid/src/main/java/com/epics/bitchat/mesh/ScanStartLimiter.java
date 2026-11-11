package com.epics.bitchat.mesh;

import java.util.ArrayDeque;

/**
 * Android (Samsung in particular) silently delivers NO scan results for ~30 s
 * when an app starts scanning more than 5 times in 30 s. A stall watchdog that
 * re-arms on silence can trip that throttle, see more silence and re-arm again
 * (seen on a Samsung SM-M476B in the 3-phone device test). This keeps starts at
 * {@link #MAX_STARTS} per {@link #WINDOW_MS} and says how long to wait otherwise.
 */
final class ScanStartLimiter {
    static final int MAX_STARTS = 4;      // one below the platform limit of 5
    static final long WINDOW_MS = 30_000;

    private final ArrayDeque<Long> starts = new ArrayDeque<>();

    /** 0 if a start is allowed now (and records it), else ms to wait before trying again. */
    synchronized long tryAcquire(long nowMs) {
        while (!starts.isEmpty() && nowMs - starts.peekFirst() >= WINDOW_MS) {
            starts.pollFirst();
        }
        if (starts.size() < MAX_STARTS) {
            starts.addLast(nowMs);
            return 0;
        }
        return WINDOW_MS - (nowMs - starts.peekFirst()) + 1;
    }

    synchronized void reset() {
        starts.clear();
    }
}
