package com.epics.bitchat.nav;

/**
 * Weighted, walkable connection between two {@link GraphNode}s.
 */
public class GraphEdge {

    private final String from;
    private final String to;
    private final double weightMeters;
    private final boolean accessible;

    public GraphEdge(String from, String to, double weightMeters, boolean accessible) {
        this.from = from;
        this.to = to;
        this.weightMeters = weightMeters;
        this.accessible = accessible;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public double getWeightMeters() {
        return weightMeters;
    }

    public boolean isAccessible() {
        return accessible;
    }

    @Override
    public String toString() {
        return from + " --" + weightMeters + "m--> " + to;
    }
}
