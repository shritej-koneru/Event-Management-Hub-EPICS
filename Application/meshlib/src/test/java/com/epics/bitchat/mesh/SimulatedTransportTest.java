package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimulatedTransportTest {

    private static final int TIMEOUT_MS = 5000;

    private SimulatedTransport newSim(boolean demoTraffic, double loss) {
        SimulatedTransport.Config config = new SimulatedTransport.Config();
        config.nodeCount = 5;
        config.packetLossPct = loss;
        config.demoTraffic = demoTraffic;
        config.seed = 42L;
        config.latencyMs = 1;
        return new SimulatedTransport(config);
    }

    @Test
    public void multiHopMessageReachesDestination() throws Exception {
        SimulatedTransport sim = newSim(false, 0.0);
        sim.start();
        sim.awaitIdle(100);
        try {
            // nodeCount=5, beaconCount=3 → nodes: node_0 (me), 3 venue beacons,
            // then phones node_4 — the farthest node in the grid.
            MeshMessage msg = MeshMessage.create(sim.getLocalNodeId(), "node_4",
                    MeshKind.CHAT, "multi-hop hello");
            sim.send(msg);
            sim.awaitIdle(TIMEOUT_MS);

            MessageLog log = sim.getLog();
            assertTrue(hasEntry(log, "node_4", MeshStatus.DELIVERED));
            assertTrue(hasEntry(log, "node_4", MeshStatus.IN_FLIGHT));
        } finally {
            sim.stop();
        }
    }

    @Test
    public void lossyLinkDropsPackets() throws Exception {
        SimulatedTransport sim = newSim(false, 1.0);
        sim.start();
        try {
            sim.send(MeshMessage.create(sim.getLocalNodeId(), "Hall 1", MeshKind.PING, "gone"));
            sim.awaitIdle(TIMEOUT_MS);

            MessageLog log = sim.getLog();
            assertTrue(hasStatus(log, MeshStatus.DROPPED_LOSS));
            assertFalse(hasStatus(log, MeshStatus.DELIVERED));
        } finally {
            sim.stop();
        }
    }

    @Test
    public void floodRelayDedupsLoops() throws Exception {
        SimulatedTransport sim = newSim(false, 0.0);
        sim.start();
        try {
            MeshMessage msg = MeshMessage.create(sim.getLocalNodeId(), "node_2",
                    MeshKind.CHAT, "dedup check");
            sim.send(msg);
            sim.awaitIdle(TIMEOUT_MS);

            MessageLog log = sim.getLog();
            // every node may see the same message once; duplicates must be dropped
            assertTrue(hasStatus(log, MeshStatus.DROPPED_DUP));
        } finally {
            sim.stop();
        }
    }

    @Test
    public void peersExposeBeaconsAndNodes() {
        SimulatedTransport sim = newSim(false, 0.0);
        List<Peer> peers = sim.getPeers();
        assertFalse(peers.isEmpty());
        boolean anyBeacon = false;
        for (Peer peer : peers) {
            if (peer.isBeacon()) {
                anyBeacon = true;
            }
        }
        assertTrue(anyBeacon);
    }

    @Test
    public void beaconNodeIdsAreVenueNames() {
        SimulatedTransport sim = newSim(false, 0.0);
        // Beacons carry venue-name wire ids so positioning resolves them on
        // the navigation graph; phones stay node_N.
        assertEquals(java.util.Arrays.asList("Hall 1", "Hall 2", "Cafeteria"),
                sim.getBeaconNodeIds());
        assertEquals(java.util.Collections.singletonList("node_4"),
                sim.getPhoneNodeIds());
    }

    private boolean hasEntry(MessageLog log, String to, MeshStatus status) {
        for (MeshLogEntry entry : log.getEntries()) {
            if (entry.getTo().equals(to) && entry.getStatus() == status) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStatus(MessageLog log, MeshStatus status) {
        for (MeshLogEntry entry : log.getEntries()) {
            if (entry.getStatus() == status) {
                return true;
            }
        }
        return false;
    }
}
