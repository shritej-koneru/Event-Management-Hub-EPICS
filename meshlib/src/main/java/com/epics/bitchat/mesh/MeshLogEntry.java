package com.epics.bitchat.mesh;

/**
 * A single row in the live traffic console. Records who sent what to whom,
 * how many hops it survived, the RSSI it arrived with and its final status.
 */
public class MeshLogEntry {
    private final long timestamp;
    private final Direction direction;
    private final String from;
    private final String to;
    private final String payload;
    private final MeshKind kind;
    private final int hops;
    private final int rssi;
    private final String relayBy;
    private final MeshStatus status;
    private final String msgId;

    public MeshLogEntry(Direction direction, String from, String to, String payload,
                        MeshKind kind, int hops, int rssi, String relayBy,
                        MeshStatus status, String msgId) {
        this.timestamp = System.currentTimeMillis();
        this.direction = direction;
        this.from = from;
        this.to = to;
        this.payload = payload;
        this.kind = kind;
        this.hops = hops;
        this.rssi = rssi;
        this.relayBy = relayBy;
        this.status = status;
        this.msgId = msgId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public String getPayload() {
        return payload;
    }

    public MeshKind getKind() {
        return kind;
    }

    public int getHops() {
        return hops;
    }

    public int getRssi() {
        return rssi;
    }

    public String getRelayBy() {
        return relayBy;
    }

    public MeshStatus getStatus() {
        return status;
    }

    public String getMsgId() {
        return msgId;
    }

    @Override
    public String toString() {
        return timestamp + " " + direction + " " + from + " -> " + to
                + " \"" + payload + "\" kind=" + kind + " hops=" + hops
                + " rssi=" + rssi + " status=" + status;
    }
}
