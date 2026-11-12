package com.epics.bitchat.mesh;

import com.epics.bitchat.nav.Graph;
import com.epics.bitchat.nav.GraphNode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ranging + weighted multi-anchor positioning: the AltBeacon-library path-loss
 * curve turns smoothed RSSI into meters, and the weighted centroid over ALL
 * anchors (not just the strongest) snaps to the nearest graph node with a
 * confidence value — the standard BLE indoor-positioning refinement.
 */
public class PositionEstimatorDistanceTest {

    private static Peer beacon(String nodeId, int rssi) {
        Peer peer = new Peer(nodeId, nodeId + " Beacon", "beacon:x", true);
        peer.setRssi(rssi);
        return peer;
    }

    /** Three collinear venues: A --- M --- B, 5 m apart. */
    private static Graph corridorGraph() {
        Graph graph = new Graph();
        graph.addNode(new GraphNode("A", "A", 0, 0, 0, true));
        graph.addNode(new GraphNode("M", "M", 5, 0, 0, false));
        graph.addNode(new GraphNode("B", "B", 10, 0, 0, true));
        return graph;
    }

    @Test
    public void distanceModelMatchesTheAltBeaconCurve() {
        // Reference points of the published best-fit curve.
        assertEquals(1.01, PositionEstimator.distanceMeters(-59), 0.05);
        assertEquals(0.50, PositionEstimator.distanceMeters(-55), 0.05);
        assertEquals(9.53, PositionEstimator.distanceMeters(-80), 0.6);
        // Monotone: weaker signal is farther away.
        assertTrue(PositionEstimator.distanceMeters(-55) < PositionEstimator.distanceMeters(-59));
        assertTrue(PositionEstimator.distanceMeters(-59) < PositionEstimator.distanceMeters(-70));
        assertTrue(PositionEstimator.distanceMeters(-70) < PositionEstimator.distanceMeters(-100));
        // No measurement.
        assertEquals(-1.0, PositionEstimator.distanceMeters(0), 0.0);
        // Custom 1 m reference still lands on the same curve shape.
        assertEquals(1.01, PositionEstimator.distanceMeters(-70, -70), 0.05);
    }

    @Test
    public void weightedCentroidSnapsToTheNodeBetweenAnchors() {
        PositionEstimator estimator = new PositionEstimator(corridorGraph());
        List<Peer> peers = new ArrayList<>();
        peers.add(beacon("A", -60));
        peers.add(beacon("B", -60));
        estimator.feed(peers);

        PositionEstimator.Position pos = estimator.estimatePosition();
        assertNotNull(pos);
        assertEquals("two equal anchors pull the estimate to the middle venue",
                "M", pos.nodeId);
        assertEquals(2, pos.anchorsUsed);
        assertTrue("confidence in (0, 1)", pos.confidence > 0.0 && pos.confidence < 1.0);
        assertEquals(1.13, pos.bestDistanceMeters, 0.1);
    }

    @Test
    public void closerAnchorDominatesTheWeightedEstimate() {
        PositionEstimator estimator = new PositionEstimator(corridorGraph());
        List<Peer> peers = new ArrayList<>();
        peers.add(beacon("A", -60)); // ≈1.1 m
        peers.add(beacon("B", -90)); // ≈23 m
        estimator.feed(peers);

        PositionEstimator.Position pos = estimator.estimatePosition();
        assertNotNull(pos);
        assertEquals("inverse-square weighting keeps the estimate near the close anchor",
                "A", pos.nodeId);
    }

    @Test
    public void confidenceGrowsWithAnchorCount() {
        PositionEstimator estimator = new PositionEstimator(corridorGraph());
        List<Peer> one = new ArrayList<>();
        one.add(beacon("A", -60));
        estimator.feed(one);
        PositionEstimator.Position single = estimator.estimatePosition();
        assertNotNull(single);
        assertEquals("single anchor counts at half weight", 1, single.anchorsUsed);

        List<Peer> three = new ArrayList<>();
        three.add(beacon("A", -60));
        three.add(beacon("B", -60));
        three.add(beacon("M", -60));
        estimator.feed(three);
        PositionEstimator.Position fused = estimator.estimatePosition();
        assertNotNull(fused);
        assertEquals(3, fused.anchorsUsed);
        assertTrue("more agreeing anchors = more confidence",
                fused.confidence > single.confidence);
        assertTrue("3 strong anchors cap the agreement bonus",
                fused.confidence <= 1.0);
    }

    @Test
    public void weightedEstimateCoversMeshAdvertAnchorsToo() {
        PositionEstimator estimator = new PositionEstimator(corridorGraph());
        assertTrue(estimator.observeVenueAdvert("M", 1));
        List<Peer> peers = new ArrayList<>();
        peers.add(beacon("A", -60));
        estimator.feed(peers);

        PositionEstimator.Position pos = estimator.estimatePosition();
        assertNotNull("venue-advert anchors participate in the fusion", pos);
        assertTrue(pos.anchorsUsed >= 2);

        assertNull(PositionEstimator.class.getSimpleName() + " reports nothing with no anchors",
                new PositionEstimator(corridorGraph()).estimatePosition());
    }

    @Test
    public void describeWeightedEstimateShowsMetersAndConfidence() {
        PositionEstimator estimator = new PositionEstimator(corridorGraph());
        assertNull(estimator.describeWeightedEstimate());

        List<Peer> peers = new ArrayList<>();
        peers.add(beacon("A", -60));
        estimator.feed(peers);
        String description = estimator.describeWeightedEstimate();
        assertNotNull(description);
        assertTrue(description, description.startsWith("A (≈1.1 m, 1 anchor, "));
        assertTrue(description, description.endsWith("%)"));
    }
}
