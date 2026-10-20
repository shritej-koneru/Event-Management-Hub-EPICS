package com.epics.bitchat.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Undirected weighted venue graph. Mirrors the NetworkX graph in the backend
 * `ai-service` so the same route can be computed on-device and server-side.
 */
public class Graph {

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final Map<String, List<GraphEdge>> adjacency = new LinkedHashMap<>();

    public void addNode(GraphNode node) {
        nodes.put(node.getId(), node);
        adjacency.computeIfAbsent(node.getId(), k -> new ArrayList<>());
    }

    /** Add a bidirectional edge. */
    public void addEdge(GraphEdge edge) {
        addEdgeDirected(edge);
        addEdgeDirected(new GraphEdge(edge.getTo(), edge.getFrom(), edge.getWeightMeters(), edge.isAccessible()));
    }

    private void addEdgeDirected(GraphEdge edge) {
        adjacency.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
    }

    public GraphNode getNode(String id) {
        return nodes.get(id);
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    /**
     * Resolve a venue name to a node id (exact first, then case-insensitive).
     * Used by mesh venue adverts, which carry the venue's display name from
     * the cross-team contract, not necessarily its node id.
     */
    public String findNodeByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (nodes.containsKey(name)) {
            return name;
        }
        String target = name.trim().toLowerCase();
        for (GraphNode node : nodes.values()) {
            if (node.getName() != null && node.getName().trim().toLowerCase().equals(target)) {
                return node.getId();
            }
        }
        return null;
    }

    public int size() {
        return nodes.size();
    }

    public List<GraphNode> getNodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes.values()));
    }

    /** Outgoing edges from a node (both directions were added, so all edges). */
    public List<GraphEdge> getEdges(String nodeId) {
        return adjacency.getOrDefault(nodeId, Collections.emptyList());
    }

    /** Returns all edges once (no duplicates). */
    public List<GraphEdge> getAllEdges() {
        Map<String, GraphEdge> seen = new LinkedHashMap<>();
        for (GraphNode node : nodes.values()) {
            for (GraphEdge edge : getEdges(node.getId())) {
                String key = edge.getFrom().compareTo(edge.getTo()) <= 0
                        ? edge.getFrom() + "|" + edge.getTo()
                        : edge.getTo() + "|" + edge.getFrom();
                seen.putIfAbsent(key, edge);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** Neighbors reachable from a node. */
    public List<GraphNode> getNeighbors(String nodeId) {
        List<GraphNode> result = new ArrayList<>();
        for (GraphEdge edge : getEdges(nodeId)) {
            GraphNode neighbor = nodes.get(edge.getTo());
            if (neighbor != null) {
                result.add(neighbor);
            }
        }
        return result;
    }
}
