package com.epics.bitchat.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neighbor table of the local node. Keeps peers seen in range, applies RSSI
 * smoothing per peer and expires stale entries.
 */
public class MeshNetwork {
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, RssiFilter> rssiFilters = new ConcurrentHashMap<>();
    private final long peerTimeoutMs;

    public MeshNetwork() {
        this(30_000L);
    }

    public MeshNetwork(long peerTimeoutMs) {
        this.peerTimeoutMs = peerTimeoutMs;
    }

    /** Record a sighting of a peer. Returns true if the peer is newly discovered. */
    public boolean upsert(Peer peer) {
        peer.setLastSeenEpochMs(System.currentTimeMillis());
        Peer existing = peers.putIfAbsent(peer.getNodeId(), peer);
        if (existing != null) {
            existing.setLastSeenEpochMs(peer.getLastSeenEpochMs());
            if (peer.getRssi() > RssiFilter.NO_SIGNAL) {
                existing.setRssi(smooth(peer.getNodeId(), peer.getRssi()));
            }
            return false;
        }
        return true;
    }

    public int smooth(String nodeId, int rawRssi) {
        return rssiFilters.computeIfAbsent(nodeId, k -> new RssiFilter()).update(rawRssi);
    }

    /** Remove peers not seen within the timeout. Returns the evicted peers. */
    public List<Peer> expire() {
        List<Peer> lost = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - peerTimeoutMs;
        for (Map.Entry<String, Peer> entry : peers.entrySet()) {
            if (entry.getValue().getLastSeenEpochMs() < cutoff) {
                lost.add(entry.getValue());
                peers.remove(entry.getKey());
                rssiFilters.remove(entry.getKey());
            }
        }
        return lost;
    }

    public Peer get(String nodeId) {
        return peers.get(nodeId);
    }

    public List<Peer> getAll() {
        return new ArrayList<>(peers.values());
    }

    public boolean contains(String nodeId) {
        return peers.containsKey(nodeId);
    }

    public int size() {
        return peers.size();
    }

    public void clear() {
        peers.clear();
        rssiFilters.clear();
    }
}
