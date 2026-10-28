package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The 5-second rebroadcast window for internet-ingested messages: cadence,
 * expiry (cancel-if-no-receivers), early close, and id-eviction behavior.
 * Time is injected, so every scenario runs without sleeping.
 */
public class RebroadcastWindowTest {

    private static class FakeClock implements RebroadcastWindow.Clock {
        final AtomicLong now = new AtomicLong(0);

        @Override
        public long nowMs() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    @Test
    public void firstBeginOpensWindowSecondIsIgnored() {
        RebroadcastWindow window = new RebroadcastWindow();
        assertTrue(window.begin("evt1"));
        assertFalse("re-begin must not restart the window", window.begin("evt1"));
        assertTrue(window.isOpen("evt1"));
    }

    @Test
    public void rebroadcastsAtFixedCadenceThenCancels() {
        FakeClock clock = new FakeClock();
        RebroadcastWindow window = new RebroadcastWindow(
                RebroadcastWindow.DEFAULT_WINDOW_MS, clock, 256);

        // begin() itself is the initial send; the loop observes re-broadcasts.
        window.begin("evt1");
        int sends = 0;
        long step = RebroadcastWindow.DEFAULT_WINDOW_MS / RebroadcastWindow.SENDS_PER_WINDOW;
        for (long t = 0; t <= RebroadcastWindow.DEFAULT_WINDOW_MS; t += step / 4) {
            if (window.shouldRebroadcast("evt1")) {
                sends++;
            }
            clock.advance(step / 4);
        }
        // Re-broadcasts at 1000..4000 ms; the 5 s boundary closes the window.
        assertEquals(RebroadcastWindow.SENDS_PER_WINDOW - 1, sends);
        assertFalse("window must cancel after 5 s", window.isOpen("evt1"));
        assertFalse(window.shouldRebroadcast("evt1"));
    }

    @Test
    public void closeStopsRebroadcastImmediately() {
        FakeClock clock = new FakeClock();
        RebroadcastWindow window = new RebroadcastWindow(
                RebroadcastWindow.DEFAULT_WINDOW_MS, clock, 256);
        window.begin("evt1");
        // The initial send already happened at begin(); the first scheduled
        // re-broadcast is one interval away.
        assertFalse(window.shouldRebroadcast("evt1"));

        window.close("evt1");
        clock.advance(1000);
        assertFalse(window.shouldRebroadcast("evt1"));
        assertFalse(window.isOpen("evt1"));
    }

    @Test
    public void unknownIdNeverRebroadcasts() {
        RebroadcastWindow window = new RebroadcastWindow();
        assertFalse(window.shouldRebroadcast("never-begun"));
        assertFalse(window.isOpen("never-begun"));
    }

    @Test
    public void expiredIdCanBeTrackedAgain() {
        FakeClock clock = new FakeClock();
        RebroadcastWindow window = new RebroadcastWindow(100, clock, 256);
        window.begin("evt1");
        clock.advance(150);
        assertFalse(window.isOpen("evt1"));

        assertTrue("after expiry the id is tracked fresh again", window.begin("evt1"));
        assertTrue(window.isOpen("evt1"));
    }

    @Test
    public void oldestEvictedBeyondTrackingLimit() {
        FakeClock clock = new FakeClock();
        RebroadcastWindow window = new RebroadcastWindow(10_000, clock, 2);
        assertTrue(window.begin("a"));
        assertTrue(window.begin("b"));
        assertTrue(window.begin("c")); // evicts "a"

        assertFalse(window.isOpen("a"));
        assertTrue(window.isOpen("b"));
        assertTrue(window.isOpen("c"));
        assertTrue("evicted id can begin again", window.begin("a"));
    }
}
