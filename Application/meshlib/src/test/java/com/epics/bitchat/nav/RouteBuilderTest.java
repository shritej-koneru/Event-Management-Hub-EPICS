package com.epics.bitchat.nav;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteBuilderTest {

    @Test
    public void buildsOneStepPerSegment() {
        Graph graph = new Graph();
        graph.addNode(new GraphNode("A", "A", 0, 0, 0, false));
        graph.addNode(new GraphNode("B", "B", 0, 10, 0, false));
        graph.addNode(new GraphNode("C", "C", 0, 20, 0, false));
        graph.addEdge(new GraphEdge("A", "B", 10, true));
        graph.addEdge(new GraphEdge("B", "C", 10, true));

        List<GraphNode> path = Dijkstra.findShortestPath(graph, "A", "C");
        Route route = RouteBuilder.build(graph, path);

        assertEquals(2, route.getSteps().size());
        assertEquals(20.0, route.getTotalMeters(), 0.001);
        assertTrue(route.getTotalSeconds() > 0);
        // A->B points north, so bearing is 0 degrees
        assertEquals(0.0, route.getSteps().get(0).getBearingDeg(), 0.001);
        assertTrue(route.getSteps().get(0).getText().toLowerCase().contains("north"));
    }

    @Test
    public void floorChangeIsFlagged() {
        Graph graph = new Graph();
        graph.addNode(new GraphNode("G", "Ground", 0, 0, 0, false));
        graph.addNode(new GraphNode("F", "First", 0, 5, 1, false));
        graph.addEdge(new GraphEdge("G", "F", 5, true));

        List<GraphNode> path = Dijkstra.findShortestPath(graph, "G", "F");
        Route route = RouteBuilder.build(graph, path);

        assertTrue(route.getSteps().get(0).isFloorChange());
        assertTrue(route.getSteps().get(0).getText().contains("Change floor"));
    }

    @Test
    public void totalSecondsIncludesFloorPenalty() {
        Graph graph = new Graph();
        graph.addNode(new GraphNode("G", "Ground", 0, 0, 0, false));
        graph.addNode(new GraphNode("F", "First", 0, 5, 1, false));
        graph.addEdge(new GraphEdge("G", "F", 5, true));

        List<GraphNode> path = Dijkstra.findShortestPath(graph, "G", "F");
        Route route = RouteBuilder.build(graph, path);

        double walkOnly = 5.0 / Route.WALK_SPEED_MPS;
        assertEquals(walkOnly + Route.FLOOR_CHANGE_PENALTY_S,
                route.getTotalSeconds(), 0.001);
    }

    @Test
    public void accessibleFlagIsCarriedOnRoute() {
        Graph graph = new Graph();
        graph.addNode(new GraphNode("A", "A", 0, 0, 0, false));
        graph.addNode(new GraphNode("B", "B", 0, 10, 0, false));
        graph.addEdge(new GraphEdge("A", "B", 10, true));

        List<GraphNode> path = Dijkstra.findShortestPath(graph, "A", "B", true);
        Route route = RouteBuilder.build(graph, path, true);

        assertTrue(route.isAccessible());
        assertFalse(RouteBuilder.build(graph, path).isAccessible());
    }
}
