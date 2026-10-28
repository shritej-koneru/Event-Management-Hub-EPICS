package com.epics.bitchat.mesh;

/** A device this phone has seen, kept even after it leaves range. */
public class KnownDevice {

    private final String nodeId;
    private final String displayName;
    private final long lastSeenEpochMs;

    public KnownDevice(String nodeId, String displayName, long lastSeenEpochMs) {
        this.nodeId = nodeId;
        this.displayName = displayName;
        this.lastSeenEpochMs = lastSeenEpochMs;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getLastSeenEpochMs() {
        return lastSeenEpochMs;
    }
}
