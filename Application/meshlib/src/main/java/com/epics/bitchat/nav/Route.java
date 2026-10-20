package com.epics.bitchat.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A computed route: the ordered node path plus human-readable turn-by-turn
 * steps and aggregate metrics.
 */
public class Route {

    public static final double WALK_SPEED_MPS = 1.4;

    /** Extra seconds charged per floor transition (stairs/elevator/ramp). */
    public static final double FLOOR_CHANGE_PENALTY_S = 30.0;

    private final List<GraphNode> path;
    private final List<RouteStep> steps;
    private final double totalMeters;
    private final boolean accessible;

    public Route(List<GraphNode> path, List<RouteStep> steps, double totalMeters) {
        this(path, steps, totalMeters, false);
    }

    public Route(List<GraphNode> path, List<RouteStep> steps, double totalMeters,
                 boolean accessible) {
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.totalMeters = totalMeters;
        this.accessible = accessible;
    }

    public List<GraphNode> getPath() {
        return path;
    }

    public List<RouteStep> getSteps() {
        return steps;
    }

    public double getTotalMeters() {
        return totalMeters;
    }

    public double getTotalSeconds() {
        long floorChanges = steps.stream().filter(RouteStep::isFloorChange).count();
        return totalMeters / WALK_SPEED_MPS + floorChanges * FLOOR_CHANGE_PENALTY_S;
    }

    /** True if this route uses only wheelchair-accessible (step-free) edges. */
    public boolean isAccessible() {
        return accessible;
    }

    public GraphNode getStart() {
        return path.isEmpty() ? null : path.get(0);
    }

    public GraphNode getDestination() {
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }
}
