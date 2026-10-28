package com.epics.bitchat.mesh;

/**
 * A discovered device in the mesh. Peers are either real phones running the
 * app or dedicated BLE beacons used for indoor positioning.
 */
public class Peer {
    private final String nodeId;
    private final String displayName;
    private final String deviceAddress;
    private final boolean isBeacon;
    private volatile long lastSeenEpochMs;
    private volatile int rssi;

    public Peer(String nodeId, String displayName, String deviceAddress, boolean isBeacon) {
        this.nodeId = nodeId;
        this.displayName = displayName;
        this.deviceAddress = deviceAddress;
        this.isBeacon = isBeacon;
        this.lastSeenEpochMs = System.currentTimeMillis();
        this.rssi = -100;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    public boolean isBeacon() {
        return isBeacon;
    }

    public long getLastSeenEpochMs() {
        return lastSeenEpochMs;
    }

    public void setLastSeenEpochMs(long lastSeenEpochMs) {
        this.lastSeenEpochMs = lastSeenEpochMs;
    }

    public int getRssi() {
        return rssi;
    }

    public void setRssi(int rssi) {
        this.rssi = rssi;
    }

    @Override
    public String toString() {
        return displayName + " (" + nodeId + ") rssi=" + rssi;
    }
}
