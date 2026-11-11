package com.epics.bitchat.nav;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an ordered node path into a {@link Route} with per-leg distances,
 * bearings ("Head north ..."), floor-change flags and totals.
 */
public final class RouteBuilder {

    private RouteBuilder() {
    }

    public static Route build(Graph graph, List<GraphNode> path) {
        return build(graph, path, false);
    }

    /** Builds a route, marking it accessible when requested (Accessibility Route Workflow). */
    public static Route build(Graph graph, List<GraphNode> path, boolean accessible) {
        List<RouteStep> steps = new ArrayList<>();
        double total = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            GraphNode from = path.get(i);
            GraphNode to = path.get(i + 1);

            double weight = edgeWeight(graph, from, to);
            double bearing = bearingDeg(from, to);
            boolean floorChange = from.getFloor() != to.getFloor();

            steps.add(new RouteStep(
                    from.getId(), to.getId(),
                    stepText(from, to, bearing, floorChange),
                    weight, bearing, floorChange));
            total += weight;
        }
        return new Route(path, steps, total, accessible);
    }

    private static double edgeWeight(Graph graph, GraphNode from, GraphNode to) {
        for (GraphEdge edge : graph.getEdges(from.getId())) {
            if (edge.getTo().equals(to.getId())) {
                return edge.getWeightMeters();
            }
        }
        return from.distanceTo(to);
    }

    /** Compass heading from a to b, degrees 0-360 (0 = north). */
    public static double bearingDeg(GraphNode a, GraphNode b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double deg = Math.toDegrees(Math.atan2(dx, dy));
        if (deg < 0) {
            deg += 360.0;
        }
        return deg;
    }

    private static String stepText(GraphNode from, GraphNode to, double bearing, boolean floorChange) {
        String dir;
        if (bearing < 22.5 || bearing >= 337.5) {
            dir = "north";
        } else if (bearing < 67.5) {
            dir = "north-east";
        } else if (bearing < 112.5) {
            dir = "east";
        } else if (bearing < 157.5) {
            dir = "south-east";
        } else if (bearing < 202.5) {
            dir = "south";
        } else if (bearing < 247.5) {
            dir = "south-west";
        } else if (bearing < 292.5) {
            dir = "west";
        } else {
            dir = "north-west";
        }
        if (floorChange) {
            return "Change floor at " + from.getName() + " then head " + dir + " to " + to.getName();
        }
        return "Head " + dir + " to " + to.getName();
    }
}
