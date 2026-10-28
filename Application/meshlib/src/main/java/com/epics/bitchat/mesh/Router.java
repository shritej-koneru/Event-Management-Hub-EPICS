package com.epics.bitchat.mesh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure flood-relay routing logic. Every node runs one of these:
 * <ul>
 *   <li>if the message id was already seen locally, drop it (loop prevention);</li>
 *   <li>if this node is the destination, deliver to the app;</li>
 *   <li>otherwise, if TTL remains, relay with TTL-1 and hops+1;</li>
 *   <li>otherwise drop with {@link MeshStatus#DROPPED_TTL}.</li>
 * </ul>
 *
 * <p>Thread-safe and free of Android dependencies, so it is directly
 * unit-testable.
 */
public class Router {

    public static final int DEFAULT_TTL = 8;

    public interface Sink {
        /** Called when a message destined for this node arrives. */
        void onDelivered(MeshMessage message, int rssi);

        /** Called to re-broadcast a message that must be relayed. */
        void onRelay(MeshMessage relayedMessage, int rssi);
    }

    private final String nodeId;
    private final MessageLog log;
    private final Sink sink;
    private final int maxSeen;
    private final Map<String, Boolean> seen;

    /** "Users as nodes": when false, this device delivers but never relays. */
    private volatile boolean relayEnabled = true;

    /** Hop accounting surfaced in the UI (relayed/delivered/drops/max hops). */
    private final RelayStats stats = new RelayStats();

    public Router(String nodeId, MessageLog log, Sink sink) {
        this(nodeId, log, sink, 4096);
    }

    public Router(String nodeId, MessageLog log, Sink sink, int maxSeen) {
        this.nodeId = nodeId;
        this.log = log;
        this.sink = sink;
        this.maxSeen = maxSeen;
        this.seen = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > Router.this.maxSeen;
            }
        };
    }

    /** Toggle this node's participation as a relay hop for other users' data. */
    public void setRelayEnabled(boolean enabled) {
        this.relayEnabled = enabled;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public RelayStats getStats() {
        return stats;
    }

    /**
     * Handle a message received from the transport.
     *
     * @param message the received message
     * @param rssi    signal strength the packet arrived with
     * @param from    neighbor node id that transmitted it
     */
    public synchronized void onReceive(MeshMessage message, int rssi, String from) {
        if (seen.containsKey(message.getMsgId())) {
            if (seen.get(message.getMsgId())) {
                return;
            }
            seen.put(message.getMsgId(), Boolean.TRUE);
            stats.onDroppedDup();
            log.addEntry(new MeshLogEntry(
                    Direction.RX, from, nodeId, message.getPayload(), message.getKind(),
                    message.getHops(), rssi, nodeId, MeshStatus.DROPPED_DUP, message.getMsgId()));
            return;
        }
        seen.put(message.getMsgId(), Boolean.FALSE);

        log.addEntry(new MeshLogEntry(
                Direction.RX, from, nodeId, message.getPayload(), message.getKind(),
                message.getHops(), rssi, from, MeshStatus.IN_FLIGHT, message.getMsgId()));

        boolean broadcast = message.getDstId() == null || message.getDstId().isEmpty();
        if (broadcast || isForMe(message)) {
            stats.onDelivered(message.getHops());
            stats.notePeerHops(from, message.getHops());
            sink.onDelivered(message, rssi);
            log.addEntry(new MeshLogEntry(
                    Direction.RX, from, nodeId, message.getPayload(), message.getKind(),
                    message.getHops(), rssi, nodeId, MeshStatus.DELIVERED, message.getMsgId()));
            if (!broadcast) {
                return; // unicast delivered: flood stops here
            }
            if (!relayEnabled) {
                // Broadcast consumed locally, but this user opted out of
                // acting as a relay: the onward flood simply ends here. Not a
                // drop — counting/logging it made "relayed 0" look like a
                // receive failure (#9).
                return;
            }
            // broadcasts are consumed locally AND flooded onward (fall through)
        }

        if (!relayEnabled) {
            // User opted out of acting as a relay node: the flood stops here.
            stats.onDroppedNoRelay();
            log.addEntry(new MeshLogEntry(
                    Direction.RX, from, nodeId, message.getPayload(), message.getKind(),
                    message.getHops(), rssi, nodeId, MeshStatus.DROPPED_NO_RELAY, message.getMsgId()));
            return;
        }

        if (message.getTtl() > 0) {
            MeshMessage relayed = message.relayed();
            stats.onRelayed(relayed.getHops());
            stats.notePeerHops(from, message.getHops());
            sink.onRelay(relayed, rssi);
            log.addEntry(new MeshLogEntry(
                    Direction.TX, nodeId, message.getDstId(), message.getPayload(),
                    message.getKind(), relayed.getHops(), rssi, nodeId,
                    MeshStatus.RELAYED, message.getMsgId()));
        } else {
            stats.onDroppedTtl();
            log.addEntry(new MeshLogEntry(
                    Direction.RX, from, nodeId, message.getPayload(), message.getKind(),
                    message.getHops(), rssi, nodeId, MeshStatus.DROPPED_TTL, message.getMsgId()));
        }
    }

    /**
     * Record a locally initiated send (TX) in the traffic log and remember
     * the message id as seen: a flooded copy of OUR OWN send that echoes
     * back from a neighbor (they relay it, we hear it again) must be
     * recognized as a duplicate, not re-relayed into a loop.
     */
    public void onLocalSend(MeshMessage message) {
        seen.put(message.getMsgId(), Boolean.TRUE);
        stats.onSent();
        log.addEntry(new MeshLogEntry(
                Direction.TX, nodeId, message.getDstId(), message.getPayload(),
                message.getKind(), message.getHops(), 0, nodeId,
                MeshStatus.IN_FLIGHT, message.getMsgId()));
    }

    public boolean isForMe(MeshMessage message) {
        return nodeId.equals(message.getDstId());
    }

    /** True when this message id was already sent or received locally. */
    public synchronized boolean hasSeen(String msgId) {
        return seen.containsKey(msgId);
    }

    public synchronized void reset() {
        seen.clear();
        stats.reset();
    }
}
