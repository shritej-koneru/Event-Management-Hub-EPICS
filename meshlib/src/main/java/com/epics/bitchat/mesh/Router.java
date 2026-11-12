package com.epics.bitchat.mesh;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Pure flood-relay routing logic. Every node runs one of these:
 * <ul>
 *   <li>if the message was already seen locally, drop it (loop prevention);</li>
 *   <li>if this node is the destination, deliver to the app;</li>
 *   <li>otherwise, if TTL remains, relay with TTL-1 and hops+1;</li>
 *   <li>otherwise drop with {@link MeshStatus#DROPPED_TTL}.</li>
 * </ul>
 *
 * <h3>What "seen" means (dedup identity)</h3>
 * The wire message id is only 3 hex chars (4,096 values) and the source id only 3 chars
 * (32,768 values), so neither can identify a message on its own. A message is identified by
 * <b>source + wire id + kind + destination + payload</b> (hops and ttl are excluded: they change on
 * every relay hop). Entries <b>expire</b>: an entry lives {@link #DEFAULT_SEEN_TTL_MS} after its LAST
 * sighting (every repeat refreshes it, so the echoes of a flood keep it alive) but never longer than
 * {@link #DEFAULT_SEEN_MAX_AGE_MS} after its first sighting, and the table is capped at {@code maxSeen}
 * entries (LRU). Before this change the key was the wire id alone with no expiry, which saturated after
 * 4,096 distinct messages and then dropped every new message (see RouterIdentityLossTest and
 * docs/ROUTER_DEDUP_AND_MESSAGE_IDENTITY.md).
 *
 * <p><b>Exception, by design:</b> {@link MeshKind#ANNOUNCE} messages (internet-ingested server events)
 * are keyed WITHOUT the source. Every phone that ingests the same server event derives the same wire id
 * but floods it under its own source id; the router must still collapse those copies
 * ({@code MeshNode.ingestFromInternet}, InternetIngestTest).
 *
 * <p>Known limit, not addressed here: two installs that share a 3-char source id are classified as
 * "our own packet" by BlePacketInterpreter and never reach this class. That is a protocol-level issue.
 *
 * <p>Thread-safe and free of Android dependencies, so it is directly
 * unit-testable.
 */
public class Router {

    public static final int DEFAULT_TTL = 8;

    /** Sliding expiry: an entry is forgotten this long after its last sighting. */
    public static final long DEFAULT_SEEN_TTL_MS = 120_000L;
    /** Upper bound: an entry is forgotten this long after its FIRST sighting however often it repeats. */
    public static final long DEFAULT_SEEN_MAX_AGE_MS = 600_000L;

    private static final char SEP = '\u001f';

    public interface Sink {
        /** Called when a message destined for this node arrives. */
        void onDelivered(MeshMessage message, int rssi);

        /** Called to re-broadcast a message that must be relayed. */
        void onRelay(MeshMessage relayedMessage, int rssi);
    }

    /** One remembered message. {@code counted}: its first repeat was already logged as DROPPED_DUP. */
    private static final class Seen {
        final String msgId;
        final long firstMs;
        long lastMs;
        boolean counted;

        Seen(String msgId, long nowMs, boolean counted) {
            this.msgId = msgId;
            this.firstMs = nowMs;
            this.lastMs = nowMs;
            this.counted = counted;
        }
    }

    private final String nodeId;
    private final MessageLog log;
    private final Sink sink;
    private final int maxSeen;
    private final long ttlMs;
    private final long maxAgeMs;
    private final LongSupplier clock;
    /** Access-ordered: iteration order is least-recently-sighted first, which the expiry sweep relies on. */
    private final Map<String, Seen> seen;
    /** How many live entries carry each wire id, so {@link #hasSeen(String)} stays a cheap id lookup. */
    private final Map<String, Integer> idRefs = new HashMap<>();

    /** "Users as nodes": when false, this device delivers but never relays. */
    private volatile boolean relayEnabled = true;

    /** Hop accounting surfaced in the UI (relayed/delivered/drops/max hops). */
    private final RelayStats stats = new RelayStats();

    public Router(String nodeId, MessageLog log, Sink sink) {
        this(nodeId, log, sink, 4096);
    }

    public Router(String nodeId, MessageLog log, Sink sink, int maxSeen) {
        this(nodeId, log, sink, maxSeen, DEFAULT_SEEN_TTL_MS, DEFAULT_SEEN_MAX_AGE_MS,
                System::currentTimeMillis);
    }

    /** Test seam: injectable clock and expiry. */
    Router(String nodeId, MessageLog log, Sink sink, int maxSeen, long ttlMs, long maxAgeMs,
           LongSupplier clock) {
        this.nodeId = nodeId;
        this.log = log;
        this.sink = sink;
        this.maxSeen = maxSeen;
        this.ttlMs = ttlMs;
        this.maxAgeMs = maxAgeMs;
        this.clock = clock;
        this.seen = new LinkedHashMap<String, Seen>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Seen> eldest) {
                if (size() > Router.this.maxSeen) {
                    unindex(eldest.getValue().msgId);
                    return true;
                }
                return false;
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Dedup identity of a message; see the class comment. Hops/ttl are deliberately not part of it. */
    static String keyOf(MeshMessage m) {
        String src = m.getKind() == MeshKind.ANNOUNCE ? "" : nz(m.getSrcId());
        return src + SEP + nz(m.getMsgId()) + SEP + m.getKind() + SEP + nz(m.getDstId())
                + SEP + nz(m.getPayload());
    }

    private void index(String msgId) {
        idRefs.merge(msgId, 1, Integer::sum);
    }

    private void unindex(String msgId) {
        idRefs.computeIfPresent(msgId, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** Drops entries not sighted within the TTL. Amortised O(1): the map is ordered by last sighting. */
    private void sweep(long now) {
        Iterator<Map.Entry<String, Seen>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Seen s = it.next().getValue();
            if (now - s.lastMs <= ttlMs) {
                break;
            }
            unindex(s.msgId);
            it.remove();
        }
    }

    /** The live entry for {@code key} (moved to most-recent), or null when absent or past its max age. */
    private Seen lookup(String key, long now) {
        sweep(now);
        Seen s = seen.get(key);
        if (s != null && now - s.firstMs > maxAgeMs) {
            seen.remove(key);
            unindex(s.msgId);
            return null;
        }
        return s;
    }

    private void remember(String key, MeshMessage message, long now, boolean counted) {
        Seen old = seen.remove(key);
        if (old != null) {
            unindex(old.msgId);
        }
        seen.put(key, new Seen(nz(message.getMsgId()), now, counted));
        index(nz(message.getMsgId()));
    }

    /**
     * Handle a message received from the transport.
     *
     * @param message the received message
     * @param rssi    signal strength the packet arrived with
     * @param from    neighbor node id that transmitted it
     */
    public synchronized void onReceive(MeshMessage message, int rssi, String from) {
        long now = clock.getAsLong();
        String key = keyOf(message);
        Seen known = lookup(key, now);
        if (known != null) {
            known.lastMs = now; // sliding expiry: every repeat refreshes the entry
            if (known.counted) {
                return;
            }
            known.counted = true;
            stats.onDroppedDup();
            log.addEntry(new MeshLogEntry(
                    Direction.RX, from, nodeId, message.getPayload(), message.getKind(),
                    message.getHops(), rssi, nodeId, MeshStatus.DROPPED_DUP, message.getMsgId()));
            return;
        }
        remember(key, message, now, false);

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
     * the message as seen: a flooded copy of OUR OWN send that echoes
     * back from a neighbor (they relay it, we hear it again) must be
     * recognized as a duplicate, not re-relayed into a loop.
     */
    public synchronized void onLocalSend(MeshMessage message) {
        remember(keyOf(message), message, clock.getAsLong(), true);
        stats.onSent();
        log.addEntry(new MeshLogEntry(
                Direction.TX, nodeId, message.getDstId(), message.getPayload(),
                message.getKind(), message.getHops(), 0, nodeId,
                MeshStatus.IN_FLIGHT, message.getMsgId()));
    }

    public boolean isForMe(MeshMessage message) {
        return nodeId.equals(message.getDstId());
    }

    /** True when a message with this wire id was sent or received locally and has not yet expired. */
    public synchronized boolean hasSeen(String msgId) {
        sweep(clock.getAsLong());
        return idRefs.containsKey(msgId);
    }

    public synchronized void reset() {
        seen.clear();
        idRefs.clear();
        stats.reset();
    }
}
