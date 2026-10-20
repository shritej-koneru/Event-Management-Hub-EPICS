package com.epics.bitchat.nav;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DijkstraTest {

    private Graph graph;

    @Before
    public void setUp() {
        graph = new Graph();
        graph.addNode(new GraphNode("A", "A", 0, 0, 0, false));
        graph.addNode(new GraphNode("B", "B", 1, 0, 0, false));
        graph.addNode(new GraphNode("C", "C", 2, 0, 0, false));
        graph.addNode(new GraphNode("D", "D", 10, 10, 0, false));
        graph.addEdge(new GraphEdge("A", "B", 1, true));
        graph.addEdge(new GraphEdge("B", "C", 1, true));
        graph.addEdge(new GraphEdge("A", "C", 5, true));
    }

    @Test
    public void findsShortestPathOverMultipleHops() {
        List<GraphNode> path = Dijkstra.findShortestPath(graph, "A", "C");
        assertEquals(3, path.size());
        assertEquals("A", path.get(0).getId());
        assertEquals("B", path.get(1).getId());
        assertEquals("C", path.get(2).getId());
    }

    @Test
    public void directEdgeIsOneHop() {
        List<GraphNode> path = Dijkstra.findShortestPath(graph, "B", "C");
        assertEquals(2, path.size());
        assertEquals("B", path.get(0).getId());
        assertEquals("C", path.get(1).getId());
    }

    @Test
    public void unreachableNodeReturnsEmptyPath() {
        List<GraphNode> path = Dijkstra.findShortestPath(graph, "A", "D");
        assertTrue(path.isEmpty());
    }

    @Test
    public void missingNodesReturnEmptyPath() {
        assertTrue(Dijkstra.findShortestPath(graph, "A", "ZZZ").isEmpty());
        assertTrue(Dijkstra.findShortestPath(graph, "ZZZ", "A").isEmpty());
    }

    @Test
    public void demoGraphRoutesAreValidAndAStarMatches() {
        Graph demo = DemoGraphFactory.buildCampusGraph();
        List<GraphNode> nodes = demo.getNodes();
        for (GraphNode from : nodes) {
            for (GraphNode to : nodes) {
                if (from.equals(to)) {
                    continue;
                }
                List<GraphNode> d = Dijkstra.findShortestPath(demo, from.getId(), to.getId());
                List<GraphNode> a = AStar.findShortestPath(demo, from.getId(), to.getId());
                assertFalse("route must exist: " + from.getId() + " -> " + to.getId(), d.isEmpty());
                assertEquals(d.size(), a.size());
                assertEquals(d.get(0).getId(), a.get(0).getId());
                assertEquals(d.get(d.size() - 1).getId(), a.get(a.size() - 1).getId());
            }
        }
    }

    @Test
    public void demoGraphConnectsEveryPair() {
        Graph demo = DemoGraphFactory.buildCampusGraph();
        List<GraphNode> nodes = demo.getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                assertFalse("unreachable: " + nodes.get(i).getId() + " -> " + nodes.get(j).getId(),
                        Dijkstra.findShortestPath(demo, nodes.get(i).getId(), nodes.get(j).getId()).isEmpty());
            }
        }
    }

    @Test
    public void accessibleRoutesConnectEveryPair() {
        // Accessibility Route Workflow: step-free route for every venue pair.
        Graph demo = DemoGraphFactory.buildCampusGraph();
        List<GraphNode> nodes = demo.getNodes();
        for (GraphNode from : nodes) {
            for (GraphNode to : nodes) {
                if (from.equals(to)) {
                    continue;
                }
                List<GraphNode> path =
                        Dijkstra.findShortestPath(demo, from.getId(), to.getId(), true);
                assertFalse("no accessible route: " + from.getId() + " -> " + to.getId(),
                        path.isEmpty());
            }
        }
    }

    @Test
    public void accessibleRouteExcludesStairs() {
        Graph demo = DemoGraphFactory.buildCampusGraph();
        List<GraphNode> path = Dijkstra.findShortestPath(demo, "Main Gate", "Hall 2", true);
        for (GraphNode node : path) {
            assertFalse("stairs must be excluded: " + node.getId(),
                    node.getId().startsWith("stairwell_"));
        }
        assertEquals("Hall 2", path.get(path.size() - 1).getId());
    }

    @Test
    public void floor1VenueRoutePassesTransitionNode() {
        // Hall 2 lives on floor 1: every route to it must cross a transition.
        Graph demo = DemoGraphFactory.buildCampusGraph();
        List<GraphNode> path = Dijkstra.findShortestPath(demo, "Main Gate", "Hall 2");
        boolean hasTransition = false;
        for (GraphNode node : path) {
            hasTransition |= DemoGraphFactory.isTransitionNode(node.getId());
        }
        assertTrue("route to floor 1 must pass a stairwell/elevator/ramp", hasTransition);
        assertEquals("Hall 2", path.get(path.size() - 1).getId());
    }

    @Test
    public void demoGraphVenueCountExcludesTransitions() {
        Graph demo = DemoGraphFactory.buildCampusGraph();
        assertEquals(13, demo.size());
        assertEquals(10, DemoGraphFactory.venueNames().length);
    }
}
