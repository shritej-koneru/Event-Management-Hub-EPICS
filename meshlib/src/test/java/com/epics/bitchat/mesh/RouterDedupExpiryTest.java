package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Router dedup identity, sliding expiry and bounds, with an injected clock (no sleeps).
 * JVM logic tests only; they say nothing about BLE behaviour on real phones.
 */
public class RouterDedupExpiryTest {

    private static final long TTL = Router.DEFAULT_SEEN_TTL_MS;       // 120 s sliding
    private static final long MAX_AGE = Router.DEFAULT_SEEN_MAX_AGE_MS; // 600 s absolute

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<MeshMessage> delivered = new ArrayList<>();
    private final MessageLog log = new MessageLog(50);

    private Router router(int maxSeen) {
        return new Router("ME1", log, new Router.Sink() {
            @Override
            public void onDelivered(MeshMessage message, int rssi) {
                delivered.add(message);
            }

            @Override
            public void onRelay(MeshMessage message, int rssi) {
            }
        }, maxSeen, TTL, MAX_AGE, now::get);
    }

    private static MeshMessage msg(String id, String src, MeshKind kind, String dst, String payload) {
        return MeshMessage.createWithId(id, src, dst, kind, payload, 7);
    }

    private static MeshMessage chat(String id, String src, String payload) {
        return msg(id, src, MeshKind.CHAT, "", payload);
    }

    // ---- identity ---------------------------------------------------------------------------

    @Test
    public void sameIdAndSourceWithDifferentPayloadAreDifferentMessages() {
        Router r = router(4096);
        r.onReceive(chat("a1b", "AAA", "ok"), -60, "N");
        r.onReceive(chat("a1b", "AAA", "no"), -60, "N");
        assertEquals(2, delivered.size());
    }

    @Test
    public void differentKindOrDestinationAreDifferentMessages() {
        Router r = router(4096);
        r.onReceive(msg("a1b", "AAA", MeshKind.CHAT, "", "x"), -60, "N");
        r.onReceive(msg("a1b", "AAA", MeshKind.BEACON, "", "x"), -60, "N");
        r.onReceive(msg("a1b", "AAA", MeshKind.CHAT, "ME1", "x"), -60, "N");
        assertEquals(3, delivered.size());
    }

    @Test
    public void relayedCopyWithDifferentHopsAndTtlIsTheSameMessage() {
        Router r = router(4096);
        MeshMessage m = chat("a1b", "AAA", "ok");
        r.onReceive(m, -60, "N1");
        r.onReceive(m.relayed(), -60, "N2");
        r.onReceive(m.relayed().relayed(), -60, "N3");
        assertEquals(1, delivered.size());
    }

    @Test
    public void announceKindIgnoresTheSourceSoInternetIngestCopiesStillCollapse() {
        Router r = router(4096);
        // Two phones ingest the same server event: same wire id and payload, different src.
        r.onReceive(msg("ev1", "PH1", MeshKind.ANNOUNCE, "", "Venue moved"), -60, "N1");
        r.onReceive(msg("ev1", "PH2", MeshKind.ANNOUNCE, "", "Venue moved"), -60, "N2");
        assertEquals("ingest copies from different phones must collapse", 1, delivered.size());
        // ...whereas ordinary chat from different sources with the same id/payload does not.
        r.onReceive(chat("c1c", "PH1", "same"), -60, "N1");
        r.onReceive(chat("c1c", "PH2", "same"), -60, "N2");
        assertEquals(3, delivered.size());
    }

    // ---- expiry -----------------------------------------------------------------------------

    @Test
    public void repeatWithinTheWindowIsADuplicate() {
        Router r = router(4096);
        MeshMessage m = chat("a1b", "AAA", "ok");
        r.onReceive(m, -60, "N");
        now.addAndGet(TTL - 1);
        r.onReceive(m, -60, "N");
        assertEquals(1, delivered.size());
    }

    @Test
    public void entryExpiresAfterTheTtlWithoutAnySighting() {
        Router r = router(4096);
        MeshMessage m = chat("a1b", "AAA", "ok");
        r.onReceive(m, -60, "N");
        now.addAndGet(TTL + 1);
        r.onReceive(m, -60, "N"); // an identical message long after: a new message again
        assertEquals(2, delivered.size());
    }

    @Test
    public void everyRepeatRefreshesTheEntry() {
        Router r = router(4096);
        MeshMessage m = chat("a1b", "AAA", "ok");
        r.onReceive(m, -60, "N");           // t = 0
        now.addAndGet(100_000);
        r.onReceive(m, -60, "N");           // t = 100 s: duplicate, refreshes
        now.addAndGet(100_000);
        r.onReceive(m, -60, "N");           // t = 200 s: 100 s since the refresh -> still a duplicate
        assertEquals("without refresh-on-hit this late echo would be delivered again", 1, delivered.size());
    }

    @Test
    public void refreshingCannotKeepAnEntryAliveBeyondTheMaxAge() {
        Router r = router(4096);
        MeshMessage m = chat("a1b", "AAA", "ok");
        r.onReceive(m, -60, "N");
        long end = now.get() + MAX_AGE + 1;
        while (now.get() < end) {
            now.addAndGet(100_000); // always inside the sliding TTL
            r.onReceive(m, -60, "N");
        }
        assertTrue("entry must have been forgotten once past the max age", delivered.size() >= 2);
    }

    @Test
    public void firstRepeatIsCountedOnceAsDroppedDup() {
        Router r = router(4096);
        MeshMessage m = chat("a1b", "AAA", "ok");
        r.onReceive(m, -60, "N");
        r.onReceive(m, -60, "N");
        r.onReceive(m, -60, "N");
        r.onReceive(m, -60, "N");
        assertEquals(1, r.getStats().getDroppedDup());
    }

    // ---- hasSeen(wireId) used by the internet-ingest path ----------------------------------------

    @Test
    public void hasSeenFollowsLiveEntriesAndExpires() {
        Router r = router(4096);
        MeshMessage m = chat("a1b", "AAA", "ok");
        assertFalse(r.hasSeen("a1b"));
        r.onLocalSend(m);
        assertTrue(r.hasSeen("a1b"));
        now.addAndGet(TTL + 1);
        assertFalse("an expired entry must no longer count as seen", r.hasSeen("a1b"));
    }

    @Test
    public void hasSeenStaysTrueWhileAnyMessageWithThatIdIsLive() {
        Router r = router(4096);
        r.onReceive(chat("a1b", "AAA", "one"), -60, "N");
        now.addAndGet(60_000);
        r.onReceive(chat("a1b", "BBB", "two"), -60, "N");
        now.addAndGet(70_000); // first entry now expired, second still live
        assertTrue(r.hasSeen("a1b"));
    }

    // ---- bound ------------------------------------------------------------------------------

    @Test
    public void capEvictsTheLeastRecentlySightedAndKeepsTheIdIndexConsistent() {
        Router r = router(3);
        for (int i = 0; i < 5; i++) {
            r.onReceive(chat("id" + i, "AAA", "p" + i), -60, "N");
        }
        assertFalse("oldest entries were evicted", r.hasSeen("id0"));
        assertFalse(r.hasSeen("id1"));
        assertTrue(r.hasSeen("id2"));
        assertTrue(r.hasSeen("id4"));
    }

    @Test
    public void resetForgetsEverything() {
        Router r = router(4096);
        r.onReceive(chat("a1b", "AAA", "ok"), -60, "N");
        r.reset();
        assertFalse(r.hasSeen("a1b"));
        r.onReceive(chat("a1b", "AAA", "ok"), -60, "N");
        assertEquals(2, delivered.size());
    }
}
