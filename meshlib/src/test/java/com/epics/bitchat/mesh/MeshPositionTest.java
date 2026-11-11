package com.epics.bitchat.mesh;

import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Graph;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Navigation without hardware beacons: mesh venue adverts (phones announcing
 * "I am at <venue>", relayed multi-hop by other users) must act as positioning
 * anchors alongside direct beacon RSSI.
 */
public class MeshPositionTest {

    private Graph graph;
    private PositionEstimator estimator;

    @Before
    public void setUp() {
        graph = DemoGraphFactory.buildCampusGraph();
        estimator = new PositionEstimator(graph);
    }

    private static Peer beacon(String nodeId, int rssi) {
        Peer peer = new Peer(nodeId, nodeId + " Beacon", "beacon:x", true);
        peer.setRssi(rssi);
        return peer;
    }

    @Test
    public void beaconOnlyStillWorks() {
        estimator.feed(Collections.singletonList(beacon("Hall 1", -60)));
        assertEquals("Hall 1", estimator.estimate());
        assertEquals("Hall 1 via beacon RSSI -60", estimator.describeEstimate());
    }

    @Test
    public void venueAdvertAnchorsPosition() {
        assertTrue(estimator.observeVenueAdvert("Hall 2", 2));
        assertEquals("Hall 2", estimator.estimate());
        assertEquals("Hall 2 via mesh venue advert (2 hops)", estimator.describeEstimate());
    }

    @Test
    public void strongBeaconBeatsFarAdvert() {
        // Beacon at -60 (direct) vs advert heard at 2 hops (synthetic -69).
        estimator.observeVenueAdvert("Hall 2", 2);
        estimator.feed(Collections.singletonList(beacon("Hall 1", -60)));
        assertEquals("stronger measured signal wins", "Hall 1", estimator.estimate());
    }

    @Test
    public void nearAdvertBeatsWeakBeacon() {
        // Weak beacon at -85 vs advert at 2 hops (synthetic -69).
        estimator.observeVenueAdvert("Hall 2", 2);
        estimator.feed(Collections.singletonList(beacon("Hall 1", -85)));
        assertEquals("closer venue knowledge wins", "Hall 2", estimator.estimate());
    }

    @Test
    public void exactTiePrefersHardwareBeacon() {
        // -55 - 2*7 = -69: manufacture an exact tie with the beacon.
        estimator.observeVenueAdvert("Hall 2", 2);
        estimator.feed(Collections.singletonList(beacon("Hall 1", -69)));
        assertEquals("measured signal beats synthetic on a tie",
                "Hall 1", estimator.estimate());
    }

    @Test
    public void tooManyHopsFallsOffSignalFloor() {
        // -55 - 7*7 = -104, below the -100 floor: not trustworthy.
        assertFalse(estimator.observeVenueAdvert("Hall 2", 7));
        assertNull(estimator.estimate());
    }

    @Test
    public void unknownVenueIsIgnored() {
        assertFalse(estimator.observeVenueAdvert("Atlantis", 0));
        assertFalse(estimator.observeVenueAdvert("", 0));
        assertFalse(estimator.observeVenueAdvert(null, 0));
    }

    @Test
    public void advertsExpireWhenDirectoryForgetsThem() {
        estimator.observeVenueAdvert("Hall 2", 1);
        assertEquals("Hall 2", estimator.estimate());
        estimator.retainVenueAnchors(new HashSet<>());
        assertNull("expired directory entry drops the anchor", estimator.estimate());
    }

    @Test
    public void retainResolvesVenueNamesToNodeIds() {
        estimator.observeVenueAdvert("Hall 2", 1);
        // The directory stores the announced venue name; resolution must be
        // case-insensitive so a slightly different spelling keeps the anchor.
        estimator.retainVenueAnchors(new HashSet<>(Collections.singletonList("hall 2")));
        assertEquals("Hall 2", estimator.estimate());
    }

    @Test
    public void directBeaconFeedsDoNotWipeVenueAnchors() {
        estimator.observeVenueAdvert("Hall 2", 1);
        // Feed peers that contain no fresh beacons.
        List<Peer> stale = new ArrayList<>();
        Peer phone = new Peer("node_7", "Node 7", "sim:7", false);
        phone.setRssi(-40);
        stale.add(phone);
        estimator.feed(stale);
        assertEquals("venue anchors survive beacon-less feeds",
                "Hall 2", estimator.estimate());
    }

    @Test
    public void meshNodeAdvertBecomesPositionEndToEnd() {
        MeshTransport transport = new MeshTransport() {
            @Override public void start() { }
            @Override public void stop() { }
            @Override public boolean isRunning() { return false; }
            @Override public void send(MeshMessage message) { }
            @Override public String getLocalNodeId() { return "me"; }
            @Override public List<Peer> getPeers() { return new ArrayList<>(); }
            @Override public void setListener(Listener listener) { /* stub */ }
        };
        MeshNode node = new MeshNode("me", "Phone A", transport, new MessageLog());
        node.setPositionEstimator(new PositionEstimator(graph));
        final AtomicReference<String> position = new AtomicReference<>();
        node.addListener(new MeshEventListener() {
            @Override public void onLogEntry(MeshLogEntry entry) { }
            @Override public void onPeerDiscovered(Peer peer) { }
            @Override public void onPeerLost(Peer peer) { }
            @Override public void onMessageDelivered(MeshMessage message) { }
            @Override public void onVenueDirectoryChanged() { }
            @Override public void onDeliveryStatus(String token, String dstId, boolean delivered) { }
            @Override public void onPositionUpdated(String graphNodeId) {
                position.set(graphNodeId);
            }
        });

        // A phone 2 relay hops away announces it is at Hall 2.
        MeshMessage advert = new MeshMessage("ADV1", "AB12CD", "", MeshKind.BEACON,
                "V:Hall 2", 1, 2, System.currentTimeMillis());
        node.onPacketReceived(advert, -70, "AB12CD");

        assertEquals("mesh venue advert alone fixes the position",
                "Hall 2", position.get());
        assertEquals("Hall 2", node.getLastPositionNodeId());
        assertEquals("source is classified as mesh-derived",
                MeshNode.AnchorSource.MESH, node.getLastPositionSource());
        assertEquals(2, node.getVenueDirectory().hopsTo("Hall 2"));
    }

    @Test
    public void beaconAnchorClassifiesSourceAsBeacon() {
        PositionEstimator estimator = new PositionEstimator(graph);
        List<Peer> peers = new ArrayList<>();
        peers.add(beacon("Hall 1", -60));
        estimator.feed(peers);
        estimator.estimate();
        assertEquals(PositionEstimator.class.getSimpleName() + " reports beacon anchor",
                true, estimator.strongestAnchor().fromBeacon);

        // Through the node: a direct beacon peer classifies as BEACON.
        MeshTransport transport = new MeshTransport() {
            @Override public void start() { }
            @Override public void stop() { }
            @Override public boolean isRunning() { return false; }
            @Override public void send(MeshMessage message) { }
            @Override public String getLocalNodeId() { return "me"; }
            @Override public List<Peer> getPeers() { return peers; }
            @Override public void setListener(Listener listener) { /* stub */ }
        };
        MeshNode node = new MeshNode("me", "Phone A", transport, new MessageLog());
        node.setPositionEstimator(estimator);
        node.refreshPeersForTest();
        assertEquals("Hall 1", node.getLastPositionNodeId());
        assertEquals(MeshNode.AnchorSource.BEACON, node.getLastPositionSource());
    }

    @Test
    public void lostAnchorsReportNoSource() {
        MeshTransport transport = new MeshTransport() {
            @Override public void start() { }
            @Override public void stop() { }
            @Override public boolean isRunning() { return false; }
            @Override public void send(MeshMessage message) { }
            @Override public String getLocalNodeId() { return "me"; }
            @Override public List<Peer> getPeers() { return new ArrayList<>(); }
            @Override public void setListener(Listener listener) { /* stub */ }
        };
        MeshNode node = new MeshNode("me", "Phone A", transport, new MessageLog());
        node.setPositionEstimator(new PositionEstimator(graph));
        node.refreshPeersForTest();
        assertEquals("no peers in, no position", null, node.getLastPositionNodeId());
        assertEquals(MeshNode.AnchorSource.NONE, node.getLastPositionSource());
    }
}
