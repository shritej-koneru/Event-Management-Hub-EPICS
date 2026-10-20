package com.epics.bitchat.mesh;

import java.util.Objects;
import java.util.UUID;

/**
 * A short message carried over the mesh. Payloads are kept small to fit the
 * ~20 byte advertisement budget of the BLE transport.
 */
public class MeshMessage {
    private final String msgId;
    private final String srcId;
    private final String dstId;
    private final MeshKind kind;
    private final String payload;
    private final int ttl;
    private final int hops;
    private final long createdAtEpochMs;

    MeshMessage(String msgId, String srcId, String dstId, MeshKind kind,
                String payload, int ttl, int hops, long createdAtEpochMs) {
        this.msgId = msgId;
        this.srcId = srcId;
        this.dstId = dstId;
        this.kind = kind;
        this.payload = payload;
        this.ttl = ttl;
        this.hops = hops;
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public static MeshMessage create(String srcId, String dstId, MeshKind kind, String payload) {
        return new MeshMessage(
                UUID.randomUUID().toString(),
                srcId, dstId, kind, payload,
                Router.DEFAULT_TTL, 0, System.currentTimeMillis());
    }

    public static MeshMessage create(String srcId, String dstId, MeshKind kind, String payload, int ttl) {
        return new MeshMessage(
                UUID.randomUUID().toString(),
                srcId, dstId, kind, payload,
                ttl, 0, System.currentTimeMillis());
    }

    /**
     * Create a message with a DETERMINISTIC id — the internet-ingest path:
     * every phone ingesting the same server event must derive the identical
     * wire id so the flood-router dedup ignores the copies heard from
     * neighbors (and does not re-flood the mesh with the same event).
     */
    public static MeshMessage createWithId(String msgId, String srcId, String dstId,
                                           MeshKind kind, String payload, int ttl) {
        return new MeshMessage(msgId, srcId, dstId, kind, payload, ttl, 0,
                System.currentTimeMillis());
    }

    /** Create a copy with decremented TTL and incremented hop count for relaying. */
    public MeshMessage relayed() {
        return new MeshMessage(msgId, srcId, dstId, kind, payload, ttl - 1, hops + 1, createdAtEpochMs);
    }

    public String getMsgId() {
        return msgId;
    }

    public String getSrcId() {
        return srcId;
    }

    public String getDstId() {
        return dstId;
    }

    public MeshKind getKind() {
        return kind;
    }

    public String getPayload() {
        return payload;
    }

    public int getTtl() {
        return ttl;
    }

    public int getHops() {
        return hops;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeshMessage)) return false;
        MeshMessage that = (MeshMessage) o;
        return Objects.equals(msgId, that.msgId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(msgId);
    }

    @Override
    public String toString() {
        return "#" + msgId.substring(0, Math.min(8, msgId.length()))
                + " \"" + payload + "\" " + srcId + "->" + dstId + " hops=" + hops + " ttl=" + ttl;
    }
}
