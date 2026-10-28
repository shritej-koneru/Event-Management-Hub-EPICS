package com.epics.bitchat.mesh;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hop accounting for the local node: how many messages it relayed for
 * others ("users as nodes"), how many it delivered, and where the flood
 * ends. Thread-safe; totals are read from the UI and reset from Settings.
 */
public class RelayStats {

    private final AtomicInteger relayed = new AtomicInteger();
    private final AtomicInteger delivered = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger droppedDup = new AtomicInteger();
    private final AtomicInteger droppedTtl = new AtomicInteger();
    private final AtomicInteger droppedNoRelay = new AtomicInteger();

    /** Max hops observed on any delivered or relayed message (mesh diameter signal). */
    private final AtomicInteger maxHopsSeen = new AtomicInteger();

    /** Max hops per peer, keyed by peer node id (multi-hop reach beyond direct range). */
    private final Map<String, AtomicInteger> maxHopsByPeer = new ConcurrentHashMap<>();

    public void onRelayed(int hops) {
        relayed.incrementAndGet();
        max(hops);
    }

    public void onDelivered(int hops) {
        delivered.incrementAndGet();
        max(hops);
    }

    public void onSent() {
        sent.incrementAndGet();
    }

    public void onDroppedDup() {
        droppedDup.incrementAndGet();
    }

    public void onDroppedTtl() {
        droppedTtl.incrementAndGet();
    }

    public void onDroppedNoRelay() {
        droppedNoRelay.incrementAndGet();
    }

    private void max(int hops) {
        int prev;
        do {
            prev = maxHopsSeen.get();
            if (prev >= hops) {
                return;
            }
        } while (!maxHopsSeen.compareAndSet(prev, hops));
    }

    /** Record the deepest hop count a message from a peer arrived with. */
    public void notePeerHops(String peerId, int hops) {
        maxHopsByPeer.computeIfAbsent(peerId, k -> new AtomicInteger())
                .accumulateAndGet(hops, Math::max);
    }

    public int getMaxHops(String peerId) {
        AtomicInteger v = maxHopsByPeer.get(peerId);
        return v == null ? 0 : v.get();
    }

    public int getRelayed() {
        return relayed.get();
    }

    public int getDelivered() {
        return delivered.get();
    }

    public int getSent() {
        return sent.get();
    }

    public int getDroppedDup() {
        return droppedDup.get();
    }

    public int getDroppedTtl() {
        return droppedTtl.get();
    }

    public int getDroppedNoRelay() {
        return droppedNoRelay.get();
    }

    public int getMaxHopsSeen() {
        return maxHopsSeen.get();
    }

    /** Human-readable one-line summary for the UI. */
    public String summary() {
        return "relayed " + relayed.get()
                + " · delivered " + delivered.get()
                + " · sent " + sent.get()
                + " · dups " + droppedDup.get()
                + " · ttl-drops " + droppedTtl.get()
                + " · no-relay drops " + droppedNoRelay.get()
                + " · max hops " + maxHopsSeen.get();
    }

    public void reset() {
        relayed.set(0);
        delivered.set(0);
        sent.set(0);
        droppedDup.set(0);
        droppedTtl.set(0);
        droppedNoRelay.set(0);
        maxHopsSeen.set(0);
        maxHopsByPeer.clear();
    }
}
