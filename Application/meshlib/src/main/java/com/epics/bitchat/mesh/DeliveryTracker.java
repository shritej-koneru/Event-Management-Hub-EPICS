package com.epics.bitchat.mesh;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks locally sent messages that are waiting for an ACK from the
 * destination. Pure Java with an injectable clock, so it is directly
 * unit-testable.
 */
public class DeliveryTracker {

    public static final long DEFAULT_TIMEOUT_MS = 12_000;

    /** An entry whose ACK never arrived. */
    public static final class Expired {
        public final String token;
        public final String dstId;

        public Expired(String token, String dstId) {
            this.token = token;
            this.dstId = dstId;
        }
    }

    private static final class Pending {
        final String token;
        final String dstId;
        final long sentAtEpochMs;

        Pending(String token, String dstId, long sentAtEpochMs) {
            this.token = token;
            this.dstId = dstId;
            this.sentAtEpochMs = sentAtEpochMs;
        }
    }

    private final long timeoutMs;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public DeliveryTracker() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public DeliveryTracker(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public synchronized void track(String token, String dstId, long nowMs) {
        pending.put(token, new Pending(token, dstId, nowMs));
    }

    /** Record the ACK for a message. Returns true if it matched a pending send. */
    public synchronized boolean ack(String token, long nowMs) {
        return pending.remove(token) != null;
    }

    public synchronized boolean isPending(String token) {
        return pending.containsKey(token);
    }

    public synchronized int size() {
        return pending.size();
    }

    /** Remove and return the sends whose timeout has elapsed. */
    public synchronized List<Expired> sweep(long nowMs) {
        List<Expired> expired = new ArrayList<>();
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending p = it.next().getValue();
            if (nowMs - p.sentAtEpochMs >= timeoutMs) {
                expired.add(new Expired(p.token, p.dstId));
                it.remove();
            }
        }
        return expired;
    }
}
