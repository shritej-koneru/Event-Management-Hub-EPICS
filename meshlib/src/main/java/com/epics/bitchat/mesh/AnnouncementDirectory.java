package com.epics.bitchat.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Feed of host announcements heard over the BLE mesh. The event host
 * broadcasts short announcements (MeshKind.ANNOUNCE); like every mesh
 * message they flood through the other users' phones (users acting as
 * relay nodes), so the whole event crowd receives them without any
 * Internet, Wi-Fi or cellular infrastructure.
 *
 * <p>Dedup: the router already drops duplicate re-transmissions of one
 * message (same msgId). This directory additionally suppresses the same
 * host re-announcing the same text within a short window, so a host can
 * safely repeat "DOORS" every minute without flooding the feed.
 */
public class AnnouncementDirectory {

    /** One announcement, as shown in the feed. */
    public static final class Entry {
        public final String text;
        public final String srcId;
        public final int hops;
        public final long atEpochMs;

        Entry(String text, String srcId, int hops, long atEpochMs) {
            this.text = text;
            this.srcId = srcId;
            this.hops = hops;
            this.atEpochMs = atEpochMs;
        }
    }

    /** Max payload of one BLE advert packet (see {@link PacketCodec}). */
    public static final int MAX_TEXT = 6;

    /** Feed capacity: the last {@value} announcements are kept. */
    private static final int CAPACITY = 50;

    /** Same host + same text within this window is treated as a repeat. */
    private static final long REPEAT_WINDOW_MS = 45_000L;

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Record an announcement. Returns the new entry, or null when it is a
     * suppressed repeat (same host, same text, inside the window).
     */
    public synchronized Entry observe(String text, String srcId, int hops) {
        String trimmed = sanitize(text);
        if (trimmed.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (!isRepeat(trimmed, srcId, now)) {
            Entry entry = new Entry(trimmed, srcId, hops, now);
            entries.add(entry);
            if (entries.size() > CAPACITY) {
                entries.remove(0);
            }
            return entry;
        }
        return null;
    }

    /** All announcements, newest first. */
    public synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>(entries);
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private boolean isRepeat(String text, String srcId, long now) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (now - entry.atEpochMs > REPEAT_WINDOW_MS) {
                return false; // everything older is outside the window
            }
            if (entry.srcId.equals(srcId) && entry.text.equals(text)) {
                return true;
            }
        }
        return false;
    }

    /** Trim to the BLE payload limit and drop non-printable noise. */
    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= ' ' && c < 127) {
                sb.append(c);
            }
            if (sb.length() == MAX_TEXT) {
                break;
            }
        }
        return sb.toString();
    }
}
