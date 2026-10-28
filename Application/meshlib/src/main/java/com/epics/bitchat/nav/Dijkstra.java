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
 * Classic Dijkstra shortest path using a priority queue.
 */
public final class Dijkstra {

    private Dijkstra() {
    }

    /** Shortest path from {@code fromId} to {@code toId}; empty if unreachable. */
    public static List<GraphNode> findShortestPath(Graph graph, String fromId, String toId) {
        return findShortestPath(graph, fromId, toId, false);
    }

    /**
     * Shortest path from {@code fromId} to {@code toId}; empty if unreachable.
     * With {@code accessible} set, only edges flagged accessible (ramps,
     * elevators, step-free corridors) are traversable — the Accessibility
     * Route Workflow guarantee.
     */
    public static List<GraphNode> findShortestPath(Graph graph, String fromId, String toId,
                                                   boolean accessible) {
        if (!graph.contains(fromId) || !graph.contains(toId)) {
            return Collections.emptyList();
        }
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        Set<String> settled = new HashSet<>();
        PriorityQueue<NodeCost> queue = new PriorityQueue<>();

        dist.put(fromId, 0.0);
        queue.add(new NodeCost(fromId, 0.0));

        while (!queue.isEmpty()) {
            NodeCost current = queue.poll();
            if (settled.contains(current.nodeId)) {
                continue;
            }
            settled.add(current.nodeId);
            if (current.nodeId.equals(toId)) {
                return reconstruct(graph, prev, toId);
            }
            for (GraphEdge edge : graph.getEdges(current.nodeId)) {
                if (settled.contains(edge.getTo())) {
                    continue;
                }
                if (accessible && !edge.isAccessible()) {
                    continue;
                }
                double newDist = dist.get(current.nodeId) + edge.getWeightMeters();
                if (newDist < dist.getOrDefault(edge.getTo(), Double.POSITIVE_INFINITY)) {
                    dist.put(edge.getTo(), newDist);
                    prev.put(edge.getTo(), current.nodeId);
                    queue.add(new NodeCost(edge.getTo(), newDist));
                }
            }
        }
        return Collections.emptyList();
    }

    static List<GraphNode> reconstruct(Graph graph, Map<String, String> prev, String toId) {
        List<String> ids = new ArrayList<>();
        String cur = toId;
        while (cur != null) {
            ids.add(cur);
            cur = prev.get(cur);
        }
        Collections.reverse(ids);
        List<GraphNode> path = new ArrayList<>();
        for (String id : ids) {
            GraphNode node = graph.getNode(id);
            if (node == null) {
                return Collections.emptyList();
            }
            path.add(node);
        }
        return path;
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
