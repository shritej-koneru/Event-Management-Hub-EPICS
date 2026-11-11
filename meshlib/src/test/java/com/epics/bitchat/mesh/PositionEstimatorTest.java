package com.epics.bitchat.mesh;

import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Graph;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PositionEstimatorTest {

    @Test
    public void strongestBeaconWins() {
        Graph graph = DemoGraphFactory.buildCampusGraph();
        PositionEstimator estimator = new PositionEstimator(graph);

        List<Peer> peers = new ArrayList<>();
        Peer hall1 = new Peer("Hall 1", "Hall 1 Beacon", "beacon:1", true);
        hall1.setRssi(-55);
        Peer cafeteria = new Peer("Cafeteria", "Cafeteria Beacon", "beacon:2", true);
        cafeteria.setRssi(-80);
        Peer phone = new Peer("node_7", "Node 7", "sim:7", false);
        phone.setRssi(-40);
        peers.add(hall1);
        peers.add(cafeteria);
        peers.add(phone);

        estimator.feed(peers);
        assertEquals("Hall 1", estimator.estimate());
    }

    @Test
    public void noBeaconsMeansNoEstimate() {
        Graph graph = DemoGraphFactory.buildCampusGraph();
        PositionEstimator estimator = new PositionEstimator(graph);

        List<Peer> peers = new ArrayList<>();
        Peer phone = new Peer("node_7", "Node 7", "sim:7", false);
        phone.setRssi(-40);
        peers.add(phone);

        estimator.feed(peers);
        assertEquals(null, estimator.estimate());
    }
}
