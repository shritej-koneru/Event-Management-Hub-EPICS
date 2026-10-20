package com.epics.bitchat.mesh;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reassembles fragmented broadcast/chat texts on the receiving side.
 *
 * <p>A long text arrives as several short framed packets (see
 * {@link PacketCodec#fragment}) that the flood router delivers like any other
 * message — each frame is delivered individually, possibly out of order and
 * with duplicates from the relay flood. This buffer collects the frames of a
 * group (keyed by sender + 3-char group id) and fires {@link
 * Callback#onTextAssembled} exactly once, with the frames in index order, when
 * the declared frame count is complete.
 *
 * <p>Groups are evicted when complete or after {@link #GROUP_TTL_MS}, so
 * incomplete floods (a lost frame) cannot leak memory or mix into later
 * groups. Not thread-safe on purpose: every touchpoint in {@link MeshNode}
 * runs on the transport/maintenance single threads in delivery order.
 */
public class FragmentAssembler {

    /** Walls off reassembly work from the app thread — meshlib-internal. */
    public interface Callback {
        /**
         * The complete text of one fragmented message.
         *
         * @param sender  wire id of the original sender
         * @param groupId 3-char fragment group id (usable as a message id)
         * @param kind    kind of the frames the group was built from
         * @param text    reassembled, order-correct full text
         */
        void onTextAssembled(String sender, String groupId, MeshKind kind, String text);

        /**
         * A group expired INCOMPLETE ({@code received} of {@code total}
         * frames arrived before the TTL) — the text is dropped rather than
         * shown garbled. Surfaced so the drop is visible, never silent.
         */
        default void onGroupExpired(String sender, String groupId, int received, int total) {
        }

        /**
         * A group is missing frames and the re-send window is open — the
         * receiver should ask the sender to re-flood exactly these frames
         * (Meshtastic-style explicit NACK). Fired at most
         * {@link #MAX_NACK_ROUNDS} times per group, {@link #NACK_RETRY_MS}
         * apart, so a silently dead sender cannot be NACK-stormed.
         *
         * @param missingIndices 1-based indices of the frames never received
         */
        default void onGroupIncomplete(String sender, String groupId,
                                       java.util.List<Integer> missingIndices,
                                       int received, int total) {
        }
    }

    private static final class PendingGroup {
        final String sender;
        final String groupId;
        final MeshKind kind;
        final int total;
        final Map<Integer, String> frames = new TreeMap<>();
        boolean completed;
        int nackRounds;
        long lastNackAtMs;

        PendingGroup(String sender, String groupId, MeshKind kind, int total) {
            this.sender = sender;
            this.groupId = groupId;
            this.kind = kind;
            this.total = total;
        }
    }

    /** One buffered frame, awaiting its group completion. */
    public static final class Frame {
        public final String sender;
        public final String groupId;
        public final int index;
        public final int total;
        public final String text;

        Frame(String sender, String groupId, int index, int total, String text) {
            this.sender = sender;
            this.groupId = groupId;
            this.index = index;
            this.total = total;
            this.text = text;
        }
    }

    /** How long an incomplete group may sit before being dropped. */
    static final long GROUP_TTL_MS = 30_000L;
    /** Safety cap on concurrently buffered groups. */
    private static final int MAX_GROUPS = 64;
    /** Grace before the first NACK goes out (frames arrive ~400 ms apart). */
    static final long NACK_AFTER_MS = 2_000L;
    /** Space between NACK rounds — one lost NACK must not storm the mesh. */
    static final long NACK_RETRY_MS = 6_000L;
    /** Hard cap on NACK rounds per group before the group simply expires. */
    static final int MAX_NACK_ROUNDS = 3;

    private final Callback callback;
    private final Map<String, PendingGroup> groups = new HashMap<>();
    private final Map<String, Long> groupAge = new HashMap<>();

    public FragmentAssembler(Callback callback) {
        this.callback = callback;
    }

    /**
     * Offer one received frame. Returns it for logging/dedup purposes, or
     * null when the payload is not a fragment (the caller delivers it
     * normally). Re-delivered frames of an already-completed group are
     * absorbed silently — the text never fires twice.
     */
    public Frame offer(String sender, MeshKind kind, String payload, long nowMs) {
        if (kind == MeshKind.NACK || !PacketCodec.isFragment(payload)) {
            return null;
        }
        String groupId = PacketCodec.fragmentGroup(payload);
        int index = PacketCodec.fragmentIndex(payload);
        int total = PacketCodec.fragmentCount(payload);
        String key = sender + "/" + groupId;
        evictStale(nowMs);

        PendingGroup group = groups.get(key);
        if (group == null) {
            if (groups.size() >= MAX_GROUPS) {
                return new Frame(sender, groupId, index, total, ""); // buffer full: absorb
            }
            group = new PendingGroup(sender, groupId, kind, total);
            groups.put(key, group);
            groupAge.put(key, nowMs);
        }
        if (index < 1 || index > group.total) {
            return new Frame(sender, groupId, index, total, ""); // inconsistent frame
        }
        group.frames.putIfAbsent(index, PacketCodec.fragmentText(payload));
        if (!group.completed && group.frames.size() == group.total) {
            group.completed = true;
            StringBuilder sb = new StringBuilder();
            for (String part : group.frames.values()) {
                sb.append(part);
            }
            // Keep the completed group as a tombstone: the flood's own echoes
            // re-deliver these frames later, and without the tombstone they
            // would rebuild the group and fire the text a second time. The
            // TTL sweep evicts it once the echoes have died down.
            callback.onTextAssembled(sender, groupId, group.kind, sb.toString());
        }
        return new Frame(sender, groupId, index, total, PacketCodec.fragmentText(payload));
    }

    /** Number of incomplete groups currently buffered (diagnostics/tests). */
    public int pendingGroups() {
        return groups.size();
    }

    /**
     * Periodic service (called from the mesh maintenance tick): expire stale
     * groups and — for incomplete groups past the grace window — fire one
     * NACK round listing the missing frame indices. The {@code nowMs}
     * parameter keeps this class pure and testable without sleeps.
     */
    public void nackSweep(long nowMs) {
        evictStale(nowMs);
        for (Map.Entry<String, PendingGroup> entry : new java.util.ArrayList<>(groups.entrySet())) {
            PendingGroup group = entry.getValue();
            if (group.completed || group.frames.isEmpty() || group.total < 2) {
                continue; // nothing missing, or nothing to compare against
            }
            Long createdAt = groupAge.get(entry.getKey());
            if (createdAt == null || nowMs - createdAt < NACK_AFTER_MS) {
                continue; // frames may still be in flight — wait out the grace
            }
            if (group.nackRounds >= MAX_NACK_ROUNDS) {
                continue; // asked enough times; the TTL expiry will decide
            }
            if (group.nackRounds > 0 && nowMs - group.lastNackAtMs < NACK_RETRY_MS) {
                continue; // too soon since the last round
            }
            java.util.List<Integer> missing = new java.util.ArrayList<>();
            for (int i = 1; i <= group.total; i++) {
                if (!group.frames.containsKey(i)) {
                    missing.add(i);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            group.nackRounds++;
            group.lastNackAtMs = nowMs;
            callback.onGroupIncomplete(group.sender, group.groupId, missing,
                    group.frames.size(), group.total);
        }
    }

    private void evictStale(long nowMs) {
        Iterator<Map.Entry<String, Long>> it = groupAge.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (nowMs - entry.getValue() > GROUP_TTL_MS) {
                PendingGroup evicted = groups.remove(entry.getKey());
                it.remove();
                if (evicted != null && !evicted.completed) {
                    // Incomplete = a frame was lost on air. Make the drop
                    // visible instead of silently eating the text.
                    callback.onGroupExpired(evicted.sender, evicted.groupId,
                            evicted.frames.size(), evicted.total);
                }
            }
        }
    }
}
