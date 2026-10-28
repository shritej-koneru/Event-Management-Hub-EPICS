package com.epics.bitchat.mesh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dedup + timed rebroadcast window for messages ingested from outside the
 * mesh (the internet backhaul use case).
 *
 * <p>Flow: a server-originated update reaches one connected phone, which
 * floods it over BLE. Every receiving phone relays it with the SAME message
 * id, so duplicate copies are ignored by the flood-router's seen-set. Each
 * phone that heard the advert keeps rebroadcasting that SAME id for a short
 * window so stragglers (phones that just entered range, a second neighbor
 * whose first copy was lost) still pick it up — then stops.
 *
 * <p>A phone with its own internet connection that receives the message
 * dedups on the id: the payload was already applied, and continuing to
 * rebroadcast after everyone nearby has heard it would only burn airtime —
 * so the window exists to bridge the discovery gap, not to loop forever.
 *
 * <p>The window schedule itself lives in this pure class (no Android deps,
 * clock injected) so tests can drive it deterministically.
 *
 * <p>Send budget: {@value #SENDS_PER_WINDOW} transmissions per window — the
 * initial flood (at {@link #begin}) plus one re-broadcast per interval.
 */
public class RebroadcastWindow {

    /** How long one ingested message keeps being re-broadcast. */
    public static final long DEFAULT_WINDOW_MS = 5_000L;

    /** Total transmissions per open window: initial + evenly spaced re-broadcasts. */
    public static final int SENDS_PER_WINDOW = 5;

    private final long windowMs;
    private final Clock clock;

    private final Map<String, Schedule> schedules =
            new LinkedHashMap<String, Schedule>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Schedule> eldest) {
            return schedules.size() > maxTracked;
        }
    };

    private final int maxTracked;

    /** Injectable clock so tests move time instead of sleeping. */
    public interface Clock {
        long nowMs();
    }

    private static class Schedule {
        final long startMs;
        final long endMs;
        /** -1 = no re-broadcast has gone out yet (avoids Long.MIN_VALUE overflow). */
        long lastSentMs = -1;

        Schedule(long startMs, long windowMs) {
            this.startMs = startMs;
            this.endMs = startMs + windowMs;
        }
    }

    public RebroadcastWindow() {
        this(DEFAULT_WINDOW_MS, System::currentTimeMillis, 256);
    }

    public RebroadcastWindow(long windowMs, Clock clock, int maxTracked) {
        this.windowMs = windowMs;
        this.clock = clock;
        this.maxTracked = maxTracked;
    }

    /**
     * Report the start of a rebroadcast cycle for {@code msgId} (first send).
     * Returns true when the send should go out — the first call for an
     * unknown id always does; re-calling for an already-active window does
     * not restart it.
     */
    public synchronized boolean begin(String msgId) {
        long now = clock.nowMs();
        Schedule existing = schedules.get(msgId);
        if (existing != null) {
            if (now >= existing.endMs) {
                // The previous window already expired: this is a fresh cycle
                // for the same id (e.g. the server re-sent the event).
                schedules.remove(msgId);
            } else {
                return false; // already flooding this id
            }
        }
        Schedule schedule = new Schedule(now, windowMs);
        // The initial flood counts as the first send of the schedule, so
        // re-broadcasts start one full interval after begin().
        schedule.lastSentMs = now;
        schedules.put(msgId, schedule);
        return true;
    }

    /**
     * Should the message be re-broadcast right now? True on the fixed cadence
     * while the window is open; false once {@code windowMs} has elapsed since
     * {@link #begin} (cancel-if-no-receivers is the window expiring).
     */
    public synchronized boolean shouldRebroadcast(String msgId) {
        Schedule s = schedules.get(msgId);
        if (s == null) {
            return false;
        }
        long now = clock.nowMs();
        if (now >= s.endMs) {
            schedules.remove(msgId); // window closed: stop flooding this id
            return false;
        }
        if (s.lastSentMs < 0
                || now - s.lastSentMs >= windowMs / SENDS_PER_WINDOW) {
            s.lastSentMs = now;
            return true;
        }
        return false;
    }

    /**
     * Mark an id as fully handled (e.g. this phone also got it from the
     * internet, or the flood confirmed everyone nearby has it): the window
     * closes and no further re-broadcasts go out for it.
     */
    public synchronized void close(String msgId) {
        schedules.remove(msgId);
    }

    /** True while the window for {@code msgId} is open (test visibility). */
    public synchronized boolean isOpen(String msgId) {
        Schedule s = schedules.get(msgId);
        return s != null && clock.nowMs() < s.endMs;
    }
}
