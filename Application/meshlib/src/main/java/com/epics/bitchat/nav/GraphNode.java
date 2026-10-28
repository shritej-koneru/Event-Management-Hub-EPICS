package com.epics.bitchat.nav;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named, positioned point of interest in the venue graph. Node ids are
 * chosen to match the venue names in Person 2's event schema so that
 * `GET /events` venues line up with the routing graph (cross-team contract).
 */
public class GraphNode {

    private final String id;
    private final String name;
    private final double x;
    private final double y;
    private final int floor;
    private final Set<String> tags;
    private final boolean isBeacon;

    public GraphNode(String id, String name, double x, double y, int floor, boolean isBeacon, Set<String> tags) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.floor = floor;
        this.isBeacon = isBeacon;
        this.tags = tags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(tags);
    }

    public GraphNode(String id, String name, double x, double y, int floor, boolean isBeacon) {
        this(id, name, x, y, floor, isBeacon, Collections.emptySet());
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public int getFloor() {
        return floor;
    }

    public boolean isBeacon() {
        return isBeacon;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public Set<String> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public double distanceTo(GraphNode other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GraphNode)) return false;
        return id.equals(((GraphNode) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
