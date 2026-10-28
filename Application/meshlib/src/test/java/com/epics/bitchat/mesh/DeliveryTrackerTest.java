package com.epics.bitchat.mesh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeliveryTrackerTest {

    private static final long NOW = 1_000_000L;

    @Test
    public void noExpiryBeforeTimeout() {
        DeliveryTracker tracker = new DeliveryTracker(12_000);
        tracker.track("TOK1", "C", NOW);
        assertEquals(0, tracker.sweep(NOW + 11_999).size());
        assertTrue(tracker.isPending("TOK1"));
    }

    @Test
    public void expiresAfterTimeout() {
        DeliveryTracker tracker = new DeliveryTracker(12_000);
        tracker.track("TOK1", "C", NOW);
        DeliveryTracker.Expired expired = tracker.sweep(NOW + 12_000).get(0);
        assertEquals("TOK1", expired.token);
        assertEquals("C", expired.dstId);
        assertFalse(tracker.isPending("TOK1"));
    }

    @Test
    public void ackClearsPendingSend() {
        DeliveryTracker tracker = new DeliveryTracker(12_000);
        tracker.track("TOK1", "C", NOW);
        assertTrue(tracker.ack("TOK1", NOW + 5_000));
        assertFalse(tracker.isPending("TOK1"));
        assertEquals(0, tracker.sweep(NOW + 60_000).size());
    }

    @Test
    public void unknownAckDoesNotMatch() {
        DeliveryTracker tracker = new DeliveryTracker(12_000);
        tracker.track("TOK1", "C", NOW);
        assertFalse(tracker.ack("TOK2", NOW));
    }

    @Test
    public void tracksMultipleSendsIndependently() {
        DeliveryTracker tracker = new DeliveryTracker(12_000);
        tracker.track("TOK1", "C", NOW);
        tracker.track("TOK2", "B", NOW);
        assertEquals(2, tracker.size());
        assertTrue(tracker.ack("TOK1", NOW + 1_000));
        assertEquals(1, tracker.size());
    }
}
