package com.epics.bitchat.nav;

/**
 * Sample campus venue graph. Node ids deliberately match the venue names in
 * Person 2's backend event schema ("venue" values returned by GET /events),
 * which is the contract the navigation feature depends on.
 *
 * Floor transitions (stairwell/elevator/ramp) are explicit intermediate
 * nodes: floor-1 venues (Hall 2, Library) are only reachable through one, and
 * the stairs edges are flagged not-accessible so
 * {@code Dijkstra.findShortestPath(graph, from, to, true)} always returns a
 * step-free route. Mirrors `ai-service/app/graph.py` so both engines compute
 * the same routes.
 */
public final class DemoGraphFactory {

    private static final String[] VENUE_NAMES = {
            "Main Gate", "Parking", "Admin Block", "Hall 1", "Hall 2",
            "Cafeteria", "Lecture Block A", "Lecture Block B", "Library",
            "Sports Complex"
    };

    private DemoGraphFactory() {
    }

    public static Graph buildCampusGraph() {
        Graph graph = new Graph();
        // id, name, x, y, floor, isBeacon
        add(graph, "Main Gate", 10, 20, 0, false);
        add(graph, "Parking", 5, 5, 0, false);
        add(graph, "Admin Block", 25, 35, 0, true);
        add(graph, "Hall 1", 40, 20, 0, true);
        add(graph, "Hall 2", 40, 60, 1, true);
        add(graph, "Cafeteria", 55, 40, 0, true);
        add(graph, "Lecture Block A", 70, 20, 0, false);
        add(graph, "Lecture Block B", 75, 55, 0, false);
        add(graph, "Library", 85, 35, 1, true);
        add(graph, "Sports Complex", 90, 70, 0, false);
        // Floor-transition nodes (not Person 2 venues; excluded from the
        // destination picker via isTransitionNode()).
        add(graph, "stairwell_1", "Stairwell 1", 42, 58, 0, false);
        add(graph, "elevator_1", "Elevator 1", 36, 55, 0, false);
        add(graph, "ramp_1", "Ramp 1", 47, 58, 0, false);

        // Ground-floor corridors (floor 0, all accessible)
        link(graph, "Main Gate", "Parking");
        link(graph, "Main Gate", "Admin Block");
        link(graph, "Main Gate", "Hall 1");
        link(graph, "Cafeteria", "Lecture Block A");
        link(graph, "Lecture Block A", "Lecture Block B");
        link(graph, "Lecture Block B", "Sports Complex");
        // First-floor corridor (floor 1, accessible)
        link(graph, "Hall 2", "Library");

        // Floor transitions: floor-0 node <-> stairwell/elevator/ramp <->
        // floor-1 node. Floor-1 venues are ONLY reachable through these.
        transition(graph, "Admin Block", "stairwell_1", true);
        transition(graph, "stairwell_1", "Hall 2", false);   // stairs
        transition(graph, "Cafeteria", "stairwell_1", true);
        transition(graph, "stairwell_1", "Library", false);  // stairs
        transition(graph, "Admin Block", "elevator_1", true);
        transition(graph, "elevator_1", "Hall 2", true);     // elevator
        transition(graph, "Cafeteria", "elevator_1", true);
        transition(graph, "elevator_1", "Library", true);    // elevator
        transition(graph, "Hall 1", "ramp_1", true);
        transition(graph, "ramp_1", "Hall 2", true);         // ramp
        transition(graph, "Hall 1", "stairwell_1", true);
        transition(graph, "Hall 1", "elevator_1", true);
        transition(graph, "Cafeteria", "ramp_1", true);

        return graph;
    }

    /** Beacon node ids (strongest-signal wins positioning). */
    public static String[] beaconNodeIds() {
        return new String[]{"Admin Block", "Hall 1", "Hall 2", "Cafeteria", "Library"};
    }

    public static String[] venueNames() {
        return VENUE_NAMES.clone();
    }

    /** True for floor-transition helper nodes (not real Person 2 venues). */
    public static boolean isTransitionNode(String nodeId) {
        return nodeId.startsWith("stairwell_") || nodeId.startsWith("elevator_")
                || nodeId.startsWith("ramp_");
    }

    private static void add(Graph graph, String name, double x, double y, int floor, boolean isBeacon) {
        add(graph, name, name, x, y, floor, isBeacon);
    }

    /** Transition nodes have a technical id distinct from their display name. */
    private static void add(Graph graph, String id, String name, double x, double y,
                            int floor, boolean isBeacon) {
        graph.addNode(new GraphNode(id, name, x, y, floor, isBeacon));
    }

    private static void link(Graph graph, String a, String b) {
        transition(graph, a, b, true);
    }

    private static void transition(Graph graph, String a, String b, boolean accessible) {
        double weight = graph.getNode(a).distanceTo(graph.getNode(b));
        graph.addEdge(new GraphEdge(a, b, Math.round(weight), accessible));
    }
}
