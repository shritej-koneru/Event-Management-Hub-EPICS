package com.epics.bitchat.mesh;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * "Users as nodes": relaying must be toggleable and visible in the stats,
 * and venue adverts must build a directory usable for mesh navigation.
 */
public class RelayAndVenueTest {

    private MessageLog log;
    private Router router;
    private final List<MeshMessage> relays = new ArrayList<>();
    private final List<MeshMessage> deliveries = new ArrayList<>();

    @Before
    public void setUp() {
        log = new MessageLog();
        relays.clear();
        deliveries.clear();
        router = new Router("me", log, new Router.Sink() {
            @Override
            public void onDelivered(MeshMessage message, int rssi) {
                deliveries.add(message);
            }

            @Override
            public void onRelay(MeshMessage relayedMessage, int rssi) {
                relays.add(relayedMessage);
            }
        });
    }

    private static MeshMessage incoming(String dst, MeshKind kind, String payload, int hops, int ttl) {
        return new MeshMessage("MSG" + hops + ttl + kind, "peer1", dst, kind, payload,
                ttl, hops, System.currentTimeMillis());
    }

    @Test
    public void relayedWhenEnabled() {
        MeshMessage m = incoming("other", MeshKind.CHAT, "hi", 0, 5);
        router.onReceive(m, -60, "peer1");
        assertEquals(1, relays.size());
        assertEquals(1, relays.get(0).getHops());
        assertEquals(4, relays.get(0).getTtl());
        assertEquals(1, router.getStats().getRelayed());
    }

    @Test
    public void noRelayWhenDisabled() {
        router.setRelayEnabled(false);
        MeshMessage m = incoming("other", MeshKind.CHAT, "hi", 0, 5);
        router.onReceive(m, -60, "peer1");
        assertTrue(relays.isEmpty());
        assertEquals(1, router.getStats().getDroppedNoRelay());
    }

    @Test
    public void deliveryStillWorksWithRelayDisabled() {
        router.setRelayEnabled(false);
        router.onReceive(incoming("me", MeshKind.CHAT, "for me", 1, 5), -60, "peer1");
        assertEquals(1, deliveries.size());
        assertEquals(1, router.getStats().getDelivered());
    }

    @Test
    public void statsTrackMaxHops() {
        router.onReceive(incoming("me", MeshKind.CHAT, "a", 3, 5), -60, "peer1");
        router.onReceive(incoming("me", MeshKind.CHAT, "b", 1, 5), -60, "peer1");
        assertEquals(3, router.getStats().getMaxHopsSeen());
        assertEquals(3, router.getStats().getMaxHops("peer1"));
        assertEquals(2, router.getStats().getDelivered());
    }

    @Test
    public void noRelayDropHasOwnStatus() {
        router.setRelayEnabled(false);
        router.onReceive(incoming("other", MeshKind.CHAT, "hi", 0, 0), -60, "peer1");
        boolean found = false;
        for (MeshLogEntry entry : log.getEntries()) {
            found |= entry.getStatus() == MeshStatus.DROPPED_NO_RELAY;
        }
        assertTrue(found);
    }

    // --- venue directory ------------------------------------------------------

    @Test
    public void venueAdvertParsing() {
        assertTrue(VenueDirectory.isVenueAdvert("V:Hall 2"));
        assertTrue(VenueDirectory.isVenueAdvert("V:Library"));
        assertFalse(VenueDirectory.isVenueAdvert("hello"));
        assertFalse(VenueDirectory.isVenueAdvert("V:"));
        assertFalse(VenueDirectory.isVenueAdvert(null));
        assertEquals("Hall 2", VenueDirectory.venueOf("V:Hall 2"));
        assertEquals("V:", VenueDirectory.venuePrefix());
    }

    @Test
    public void observeRecordsAndDedups() {
        VenueDirectory directory = new VenueDirectory();
        assertTrue(directory.observe("Hall 2", "AB12CD", 2));
        assertFalse("same info again is not new", directory.observe("Hall 2", "AB12CD", 2));
        assertTrue("shorter route is new", directory.observe("Hall 2", "AB12CD", 1));
        assertEquals(1, directory.hopsTo("Hall 2"));
        assertEquals(1, directory.size());
    }

    @Test
    public void venuesSortedByHops() {
        VenueDirectory directory = new VenueDirectory();
        directory.observe("Library", "AAAAAA", 3);
        directory.observe("Cafeteria", "BBBBBB", 1);
        directory.observe("Hall 2", "CCCCCC", 2);
        assertEquals(java.util.Arrays.asList("Cafeteria", "Hall 2", "Library"),
                directory.venues());
        assertEquals(-1, directory.hopsTo("Nowhere"));
        assertEquals(3, directory.entriesForVenue("Library").get(0).hops);
    }

    @Test
    public void entriesExpire() {
        VenueDirectory directory = new VenueDirectory(50L);
        directory.observe("Hall 2", "AB12CD", 0);
        assertEquals(1, directory.size());
        try {
            Thread.sleep(80);
        } catch (InterruptedException ignored) {
        }
        assertEquals(0, directory.size());
        assertEquals(-1, directory.hopsTo("Hall 2"));
    }

    @Test
    public void meshNodeObservesVenueAdverts() {
        // Stub transport: nothing running, we inject a received packet directly.
        MeshTransport transport = new MeshTransport() {
            @Override public void start() { }
            @Override public void stop() { }
            @Override public boolean isRunning() { return false; }
            @Override public void send(MeshMessage message) { }
            @Override public String getLocalNodeId() { return "me"; }
            @Override public List<Peer> getPeers() { return new ArrayList<>(); }
            @Override public void setListener(Listener listener) { /* not needed */ }
        };
        MeshNode node = new MeshNode("me", "Phone A", transport, new MessageLog());
        final AtomicInteger changes = new AtomicInteger();
        node.addListener(new MeshEventListener() {
            @Override public void onLogEntry(MeshLogEntry entry) { }
            @Override public void onPeerDiscovered(Peer peer) { }
            @Override public void onPeerLost(Peer peer) { }
            @Override public void onMessageDelivered(MeshMessage message) { }
            @Override public void onPositionUpdated(String graphNodeId) { }
            @Override public void onVenueDirectoryChanged() {
                changes.incrementAndGet();
            }
        });

        MeshMessage advert = new MeshMessage("ADV1", "AB12CD", "", MeshKind.BEACON,
                "V:Hall 2", 1, 2, System.currentTimeMillis());
        node.onPacketReceived(advert, -70, "AB12CD");

        assertEquals(1, changes.get());
        assertEquals(1, node.getVenueDirectory().size());
        assertEquals(2, node.getVenueDirectory().hopsTo("Hall 2"));
    }
}
