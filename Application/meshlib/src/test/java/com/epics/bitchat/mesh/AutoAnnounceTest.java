package com.epics.bitchat.mesh;

import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Graph;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Auto-announce: every phone becomes a navigation beacon. Gates under test:
 * <ul>
 *   <li>only <b>beacon-backed</b> (measured) positions are announced —
 *   rebroadcasting mesh hearsay would hop-reset adverts and make the whole
 *   mesh echo one venue;</li>
 *   <li>loud hearsay cannot silence a phone that has a measured position;</li>
 *   <li>a manual pin always outranks the automatic estimate;</li>
 *   <li>transition nodes (stairs/elevator/ramp) are never announced;</li>
 *   <li>announcing happens on venue change; the 15 s cycle re-announces.</li>
 * </ul>
 */
public class AutoAnnounceTest {

    /** Test harness: a MeshNode over a stub transport with a controllable estimator. */
    private static final class Harness {
        final Graph graph = DemoGraphFactory.buildCampusGraph();
        final PositionEstimator estimator = new PositionEstimator(graph);
        final List<MeshMessage> sent = new ArrayList<>();
        final MeshNode node;

        Harness() {
            MeshTransport transport = new MeshTransport() {
                @Override public void start() { }
                @Override public void stop() { }
                @Override public boolean isRunning() { return false; }
                @Override public void send(MeshMessage message) { sent.add(message); }
                @Override public String getLocalNodeId() { return "me"; }
                @Override public List<Peer> getPeers() { return new ArrayList<>(); }
                @Override public void setListener(Listener listener) { /* stub */ }
            };
            node = new MeshNode("me", "Phone A", transport, new MessageLog());
            node.setPositionEstimator(estimator);
            node.setAutoAnnounceEnabled(true);
            node.announceVenue(""); // no pre-existing venue state
        }

        /** Beacon at the given venue with the given smoothed RSSI. */
        void see(String venue, int rssi) {
            Peer beacon = new Peer(venue, venue + " Beacon", "beacon:x", true);
            beacon.setRssi(rssi);
            List<Peer> peers = new ArrayList<>();
            peers.add(beacon);
            estimator.feed(peers);
        }

        /** A relayed venue advert from someone else (mesh hearsay). */
        void hearAdvert(String venue, int hops) {
            estimator.observeVenueAdvert(venue, hops);
        }

        /** Run one auto-announce tick; returns messages sent by THIS tick. */
        int autoAnnounce() {
            int before = sent.size();
            node.autoAnnounceNow();
            return sent.size() - before;
        }

        String lastSentPayload() {
            return sent.get(sent.size() - 1).getPayload();
        }
    }

    @Before
    public void setUp() {
        // no shared state; each test builds its own harness
    }

    @Test
    public void beaconBackedPositionIsAnnounced() {
        Harness h = new Harness();
        h.see("Hall 1", -60);
        assertEquals(1, h.autoAnnounce());
        assertEquals("V:Hall 1", h.lastSentPayload());
        assertEquals(MeshKind.BEACON, h.sent.get(h.sent.size() - 1).getKind());
    }

    @Test
    public void hearsayIsNeverRebroadcast() {
        Harness h = new Harness();
        h.hearAdvert("Hall 2", 2); // strongest anchor overall — but synthetic
        assertEquals(0, h.autoAnnounce());
    }

    @Test
    public void loudHearsayDoesNotSilenceMeasuredPosition() {
        Harness h = new Harness();
        h.hearAdvert("Hall 2", 0); // synthetic -55, stronger than the beacon
        h.see("Hall 1", -60);      // measured but weaker
        assertEquals("measured anchor must drive the announcement",
                1, h.autoAnnounce());
        assertEquals("V:Hall 1", h.lastSentPayload());
    }

    @Test
    public void transitionNodesAreSkipped() {
        Harness h = new Harness();
        h.see("stairwell_1", -50);
        assertEquals("stairs are not a venue", 0, h.autoAnnounce());
    }

    @Test
    public void announceOnVenueChangeOnly() {
        Harness h = new Harness();
        h.see("Hall 1", -60);
        assertEquals(1, h.autoAnnounce());
        assertEquals("no re-announce while staying put", 0, h.autoAnnounce());
        h.see("Cafeteria", -55);
        assertEquals(1, h.autoAnnounce());
        assertEquals("V:Cafeteria", h.lastSentPayload());
    }

    @Test
    public void pinOutranksAutoEstimate() {
        Harness h = new Harness();
        h.see("Hall 1", -60);
        h.node.setAutoAnnounceOverride("Library");
        h.autoAnnounce(); // applies the pin
        assertEquals("V:Library", h.lastSentPayload());
        // Auto estimate no longer announces while the pin stands.
        h.see("Cafeteria", -50);
        assertEquals("pinned venue suppresses the auto estimate",
                0, h.autoAnnounce());
    }

    @Test
    public void clearingThePinReturnsControlToAuto() {
        Harness h = new Harness();
        h.node.setAutoAnnounceOverride("Library");
        h.autoAnnounce();
        h.node.setAutoAnnounceOverride("");
        h.see("Hall 1", -60);
        h.autoAnnounce();
        assertEquals("V:Hall 1", h.lastSentPayload());
    }

    @Test
    public void disabledMeansSilent() {
        Harness h = new Harness();
        h.see("Hall 1", -60);
        h.node.setAutoAnnounceEnabled(false);
        assertEquals(0, h.autoAnnounce());
    }

    @Test
    public void stopsWhenEvidenceExpires() {
        Harness h = new Harness();
        h.see("Hall 1", -60);
        h.autoAnnounce(); // V:Hall 1 sent
        // Beacon goes stale: feed() with no fresh beacons wipes the anchor.
        h.estimator.feed(new ArrayList<>());
        assertEquals("no measured anchor left, nothing more to announce",
                0, h.autoAnnounce());
    }

    @Test
    public void strongestBeaconAnchorIgnoresAdverts() {
        PositionEstimator estimator = new PositionEstimator(DemoGraphFactory.buildCampusGraph());
        estimator.observeVenueAdvert("Hall 2", 0); // synthetic, strong
        assertEquals(null, estimator.strongestBeaconAnchor());
        Peer beacon = new Peer("Hall 1", "Hall 1 Beacon", "beacon:x", true);
        beacon.setRssi(-80);
        List<Peer> peers = new ArrayList<>();
        peers.add(beacon);
        estimator.feed(peers);
        assertEquals("Hall 1", estimator.strongestBeaconAnchor().nodeId);
        assertTrue(estimator.strongestBeaconAnchor().fromBeacon);
    }
}
