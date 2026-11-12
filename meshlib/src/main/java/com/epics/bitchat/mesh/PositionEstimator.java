package com.epics.bitchat.mesh;

import com.epics.bitchat.nav.Graph;
import com.epics.bitchat.nav.GraphNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns two kinds of mesh input into an estimated graph position:
 *
 * <ul>
 *   <li><b>Hardware beacons</b> — dedicated BLE beacons (or phones acting as
 *   beacons) whose advert carries a graph node id; the direct RSSI is
 *   meaningful.</li>
 *   <li><b>Mesh venue adverts</b> — ordinary phones broadcasting "I am at
 *   {@code <venue>}" ({@code V:<venue>} BEACON messages). Venue knowledge
 *   floods the mesh and is relayed by other users, so an advert heard at N
 *   hops anchors you near that venue even though the phone is out of direct
 *   range.</li>
 * </ul>
 *
 * <p>Both inputs are normalized onto one RSSI-like scale: a direct advert is
 * worth its real RSSI, while each relay leg of a venue advert "costs" a fixed
 * attenuation. The strongest anchor wins (nearest-beacon heuristic); on an
 * exact tie a hardware beacon beats a phone advert because its signal is
 * physically measured. A future refinement can do weighted triangulation
 * over several anchors instead.
 */
public class PositionEstimator {

    /** Synthetic RSSI penalty per relay hop for mesh venue adverts (dB). */
    public static final int HOP_ATTENUATION_DB = 7;

    /** Synthetic RSSI assigned to an advert heard directly (hops = 0). */
    public static final int DIRECT_ADVERT_RSSI = -55;

    /** Reference RSSI at 1 m for the ranging model (Apple/AltBeacon convention). */
    public static final int TX_POWER_AT_1M_DB = -59;

    private static final double SIGNAL_FLOOR = -100;

    private final Graph graph;
    private final Map<String, Anchor> anchors = new ConcurrentHashMap<>();
    private final long readingTtlMs;

    /** One fused positioning anchor: where it points and how strong it is. */
    public static final class Anchor {
        public final String nodeId;
        public final int signal;
        public final boolean fromBeacon;

        Anchor(String nodeId, int signal, boolean fromBeacon) {
            this.nodeId = nodeId;
            this.signal = signal;
            this.fromBeacon = fromBeacon;
        }

        /** Estimated distance in meters from this anchor's (smoothed) RSSI. */
        public double distanceMeters() {
            return PositionEstimator.distanceMeters(signal);
        }
    }

    /**
     * Fused position over EVERY fresh anchor (not just the strongest one):
     * the nearest graph node to the weighted centroid, plus a confidence.
     */
    public static final class Position {
        public final String nodeId;
        /** 0..1 — signal quality of the best anchor × anchor agreement. */
        public final double confidence;
        public final int anchorsUsed;
        /** Estimated meters to the strongest anchor. */
        public final double bestDistanceMeters;

        Position(String nodeId, double confidence, int anchorsUsed,
                 double bestDistanceMeters) {
            this.nodeId = nodeId;
            this.confidence = confidence;
            this.anchorsUsed = anchorsUsed;
            this.bestDistanceMeters = bestDistanceMeters;
        }
    }

    public PositionEstimator(Graph graph) {
        this(graph, 10_000L);
    }

    public PositionEstimator(Graph graph, long readingTtlMs) {
        this.graph = graph;
        this.readingTtlMs = readingTtlMs;
    }

    /** Feed the current peer table; only fresh beacon peers contribute. */
    public void feed(Iterable<Peer> peers) {
        Map<String, Anchor> fresh = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();
        long cutoff = now - readingTtlMs;
        for (Peer peer : peers) {
            if (!peer.isBeacon()) {
                continue;
            }
            if (peer.getLastSeenEpochMs() >= cutoff && peer.getRssi() > RssiFilter.NO_SIGNAL) {
                fresh.put(peer.getNodeId(), new Anchor(peer.getNodeId(), peer.getRssi(), true));
            }
        }
        // Venue anchors stay valid for the full TTL (mesh adverts arrive at
        // the slower 15 s re-announce cadence, unlike 1 Hz beacon RSSI).
        for (Map.Entry<String, Anchor> e : anchors.entrySet()) {
            if (!e.getValue().fromBeacon) {
                fresh.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        anchors.clear();
        anchors.putAll(fresh);
    }

    /**
     * Turn a venue advert heard over the mesh into a positioning anchor. The
     * venue name must resolve to a graph node (cross-team contract). Each hop
     * of attenuation moves the synthetic RSSI down from
     * {@link #DIRECT_ADVERT_RSSI}; beyond the signal floor the anchor is
     * ignored — too many relay legs to be trusted.
     *
     * @return true if the advert was accepted as an anchor
     */
    public boolean observeVenueAdvert(String venue, int hops, long heardAtEpochMs) {
        if (venue == null || venue.isEmpty() || hops < 0 || graph == null) {
            return false;
        }
        String nodeId = graph.findNodeByName(venue);
        if (nodeId == null) {
            return false;
        }
        int signal = DIRECT_ADVERT_RSSI - hops * HOP_ATTENUATION_DB;
        if (signal <= SIGNAL_FLOOR) {
            return false;
        }
        anchors.put(nodeId, new Anchor(nodeId, signal, false));
        return true;
    }

    /** Convenience overload that timestamps the advert now. */
    public boolean observeVenueAdvert(String venue, int hops) {
        return observeVenueAdvert(venue, hops, System.currentTimeMillis());
    }

    // --- ranging (AltBeacon-library path-loss model) -------------------------

    /**
     * RSSI → meters, using the Android Beacon Library's best-fit curve:
     * {@code d = A·(r/t)^B + C} with A=0.89976, B=7.7095, C=0.111, and
     * {@code ratio^10} below the 1 m reference (see
     * https://altbeacon.github.io/android-beacon-library/distance-calculations.html).
     * Returns -1 for a zero/absent measurement.
     */
    public static double distanceMeters(int rssi, int txPowerAt1m) {
        if (rssi == 0) {
            return -1.0;
        }
        double ratio = rssi * 1.0 / txPowerAt1m;
        if (ratio < 1.0) {
            return Math.pow(ratio, 10.0);
        }
        return 0.89976 * Math.pow(ratio, 7.7095) + 0.111;
    }

    /** Same model at the default 1 m reference ({@link #TX_POWER_AT_1M_DB}). */
    public static double distanceMeters(int rssi) {
        return distanceMeters(rssi, TX_POWER_AT_1M_DB);
    }

    // --- weighted multi-anchor estimate --------------------------------------

    /**
     * Weighted-centroid position over ALL fresh anchors — the standard BLE
     * indoor-positioning refinement of the nearest-beacon heuristic. Each
     * anchor pulls the centroid with the inverse square of its estimated
     * distance ({@link #distanceMeters(int)} turns RSSI into meters first);
     * the centroid is then snapped to the nearest graph node. Confidence
     * combines the strongest anchor's signal quality with how many anchors
     * agreed (1 anchor = half weight, 3+ = full). Returns null when no
     * anchor resolves to a graph node.
     */
    public Position estimatePosition() {
        double sumW = 0;
        double cx = 0;
        double cy = 0;
        int n = 0;
        int bestSignal = Integer.MIN_VALUE;
        double bestDistance = -1;
        for (Anchor anchor : anchors.values()) {
            GraphNode node = graph == null ? null : graph.getNode(anchor.nodeId);
            if (node == null) {
                continue;
            }
            double d = distanceMeters(anchor.signal);
            double w = 1.0 / ((d + 0.5) * (d + 0.5));
            sumW += w;
            cx += w * node.getX();
            cy += w * node.getY();
            n++;
            if (anchor.signal > bestSignal) {
                bestSignal = anchor.signal;
                bestDistance = d;
            }
        }
        if (n == 0 || sumW <= 0 || graph == null) {
            return null;
        }
        cx /= sumW;
        cy /= sumW;
        GraphNode snapped = null;
        double bestSnap = Double.MAX_VALUE;
        for (GraphNode node : graph.getNodes()) {
            double dist = Math.hypot(node.getX() - cx, node.getY() - cy);
            if (dist < bestSnap) {
                bestSnap = dist;
                snapped = node;
            }
        }
        if (snapped == null) {
            return null;
        }
        double quality = (bestSignal - SIGNAL_FLOOR)
                / (double) (DIRECT_ADVERT_RSSI - SIGNAL_FLOOR);
        quality = Math.max(0.0, Math.min(1.0, quality));
        double agreement = Math.min(1.0, 0.5 + 0.25 * (n - 1));
        return new Position(snapped.getId(), quality * agreement, n, bestDistance);
    }

    /**
     * Human-readable weighted estimate — "Hall 1 (≈1.1 m, 2 anchors, 67%)"
     * — or null when nothing is in range.
     */
    public String describeWeightedEstimate() {
        Position p = estimatePosition();
        if (p == null) {
            return null;
        }
        return p.nodeId + " (≈" + String.format(java.util.Locale.US, "%.1f",
                p.bestDistanceMeters) + " m, " + p.anchorsUsed
                + (p.anchorsUsed == 1 ? " anchor, " : " anchors, ")
                + Math.round(p.confidence * 100) + "%)";
    }

    /**
     * Keep only the venue-advert anchors for the given venue names — anchors
     * whose directory entry expired (or whose venue changed) are dropped.
     */
    public void retainVenueAnchors(java.util.Set<String> venues) {
        java.util.Set<String> nodeIds = new java.util.HashSet<>();
        if (graph != null) {
            for (String venue : venues) {
                String id = graph.findNodeByName(venue);
                if (id != null) {
                    nodeIds.add(id);
                }
            }
        }
        anchors.values().removeIf(anchor -> !anchor.fromBeacon && !nodeIds.contains(anchor.nodeId));
    }

    /** Drop all venue-advert anchors (e.g. the mesh stopped or cleared). */
    public void clearVenueAnchors() {
        anchors.values().removeIf(anchor -> !anchor.fromBeacon);
    }

    /**
     * Estimated graph node id (strongest anchor), or null if no usable
     * anchor exists.
     */
    public String estimate() {
        Anchor best = bestAnchor();
        return best == null ? null : best.nodeId;
    }

    /**
     * Strongest anchor plus how it was sourced — "Hall 1 via beacon RSSI
     * -55" vs "Hall 2 via mesh advert (2 hops)". Null when nothing is in
     * range.
     */
    public String describeEstimate() {
        Anchor best = bestAnchor();
        if (best == null) {
            return null;
        }
        return best.fromBeacon
                ? best.nodeId + " via beacon RSSI " + best.signal
                : best.nodeId + " via mesh venue advert (" + hopsOf(best.signal) + " hops)";
    }

    private static int hopsOf(int syntheticRssi) {
        return (DIRECT_ADVERT_RSSI - syntheticRssi) / HOP_ATTENUATION_DB;
    }

    /**
     * The strongest anchor with its source — hardware beacon (physically
     * measured) or mesh advert (synthetic) — or null when nothing is in
     * range. Lets callers distinguish "I can measure where I am" from "the
     * mesh told me where I am", e.g. so auto-announce only ever broadcasts
     * beacon-backed positions and never re-announces mesh hearsay.
     */
    public Anchor strongestAnchor() {
        return bestAnchor();
    }

    /**
     * Strongest <b>physically measured</b> (beacon-backed) anchor, ignoring
     * mesh advert anchors — or null when nothing measurable is in range.
     * Auto-announce uses this so it can never rebroadcast mesh hearsay, and
     * so loud hearsay cannot silence a phone that knows where it is.
     */
    public Anchor strongestBeaconAnchor() {
        Anchor best = null;
        for (Anchor anchor : anchors.values()) {
            if (!anchor.fromBeacon) {
                continue;
            }
            if (best == null || anchor.signal > best.signal) {
                best = anchor;
            }
        }
        if (best == null || graph == null || graph.getNode(best.nodeId) == null) {
            return null;
        }
        return best;
    }

    private Anchor bestAnchor() {
        Anchor best = null;
        for (Anchor anchor : anchors.values()) {
            if (best == null
                    || anchor.signal > best.signal
                    || (anchor.signal == best.signal && anchor.fromBeacon && !best.fromBeacon)) {
                best = anchor;
            }
        }
        if (best == null || graph == null || graph.getNode(best.nodeId) == null) {
            return null;
        }
        return best;
    }
}
