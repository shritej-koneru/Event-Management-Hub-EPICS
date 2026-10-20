package com.epics.bitchat.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* shortest path with an admissible Euclidean heuristic. Faster than
 * Dijkstra on large venue graphs while returning the same optimal route.
 */
public final class AStar {

    private AStar() {
    }

    /** Shortest path from {@code fromId} to {@code toId}; empty if unreachable. */
    public static List<GraphNode> findShortestPath(Graph graph, String fromId, String toId) {
        return findShortestPath(graph, fromId, toId, false);
    }

    /**
     * Shortest path from {@code fromId} to {@code toId}; empty if unreachable.
     * With {@code accessible} set, only edges flagged accessible (ramps,
     * elevators, step-free corridors) are traversable.
     */
    public static List<GraphNode> findShortestPath(Graph graph, String fromId, String toId,
                                                   boolean accessible) {
        if (!graph.contains(fromId) || !graph.contains(toId)) {
            return Collections.emptyList();
        }
        GraphNode goal = graph.getNode(toId);

        Map<String, Double> gScore = new HashMap<>();
        Map<String, Double> fScore = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> settled = new HashSet<>();
        PriorityQueue<NodeCost> open = new PriorityQueue<>();

        gScore.put(fromId, 0.0);
        fScore.put(fromId, heuristic(graph.getNode(fromId), goal));
        open.add(new NodeCost(fromId, fScore.get(fromId)));

        while (!open.isEmpty()) {
            NodeCost current = open.poll();
            if (current.nodeId.equals(toId)) {
                return Dijkstra.reconstruct(graph, prev, toId);
            }
            if (settled.contains(current.nodeId)) {
                continue;
            }
            settled.add(current.nodeId);

            double gCurrent = gScore.get(current.nodeId);
            for (GraphEdge edge : graph.getEdges(current.nodeId)) {
                if (settled.contains(edge.getTo())) {
                    continue;
                }
                if (accessible && !edge.isAccessible()) {
                    continue;
                }
                double tentativeG = gCurrent + edge.getWeightMeters();
                if (tentativeG < gScore.getOrDefault(edge.getTo(), Double.POSITIVE_INFINITY)) {
                    prev.put(edge.getTo(), current.nodeId);
                    gScore.put(edge.getTo(), tentativeG);
                    double f = tentativeG + heuristic(graph.getNode(edge.getTo()), goal);
                    fScore.put(edge.getTo(), f);
                    open.add(new NodeCost(edge.getTo(), f));
                }
            }
        }
        return Collections.emptyList();
    }

    private static double heuristic(GraphNode a, GraphNode b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    private static final class NodeCost implements Comparable<NodeCost> {
        final String nodeId;
        final double cost;

        NodeCost(String nodeId, double cost) {
            this.nodeId = nodeId;
            this.cost = cost;
        }

        @Override
        public int compareTo(NodeCost other) {
            return Double.compare(cost, other.cost);
        }
    }
}
