package com.epics.bitchat.nav;

/**
 * One leg of a route between two consecutive graph nodes, ready to be rendered
 * as a turn-by-turn instruction plus a compass bearing.
 */
public class RouteStep {

    private final String fromId;
    private final String toId;
    private final String text;
    private final double distanceMeters;
    private final double bearingDeg;
    private final boolean floorChange;

    public RouteStep(String fromId, String toId, String text, double distanceMeters,
                     double bearingDeg, boolean floorChange) {
        this.fromId = fromId;
        this.toId = toId;
        this.text = text;
        this.distanceMeters = distanceMeters;
        this.bearingDeg = bearingDeg;
        this.floorChange = floorChange;
    }

    public String getFromId() {
        return fromId;
    }

    public String getToId() {
        return toId;
    }

    public String getText() {
        return text;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }

    public double getBearingDeg() {
        return bearingDeg;
    }

    public boolean isFloorChange() {
        return floorChange;
    }

    @Override
    public String toString() {
        return text + " (" + (int) distanceMeters + " m)";
    }
}
