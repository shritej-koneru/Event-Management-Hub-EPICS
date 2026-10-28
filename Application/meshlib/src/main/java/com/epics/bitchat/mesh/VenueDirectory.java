package com.epics.bitchat.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directory of venue claims heard over the BLE mesh. Every phone periodically
 * broadcasts the venue it is at (MeshKind.BEACON, payload "V:<venue>"); the
 * message floods like any other, so venue knowledge propagates multi-hop —
 * phones learn about venues far beyond their direct BLE range, relayed by the
 * other phones in between (users acting as navigation nodes).
 */
public class VenueDirectory {

    /** A venue announcement heard from a device (possibly multi-hop). */
    public static final class Entry {
        public final String venue;
        public final String deviceId;
        public final int hops;
        public final long heardAtEpochMs;

        Entry(String venue, String deviceId, int hops, long heardAtEpochMs) {
            this.venue = venue;
            this.deviceId = deviceId;
            this.hops = hops;
            this.heardAtEpochMs = heardAtEpochMs;
        }
    }

    /**
     * Serializable snapshot of the live directory state. The app layer
     * persists it (e.g. SharedPreferences) so venue knowledge survives
     * restarts and can be restored for offline navigation.
     */
    public static final class Live {

        /** One venue claim in a snapshot. */
        public static final class Item {
            public final String venue;
            public final String deviceId;
            public final int hops;
            public final long heardAtEpochMs;

            public Item(String venue, String deviceId, int hops, long heardAtEpochMs) {
                this.venue = venue;
                this.deviceId = deviceId;
                this.hops = hops;
                this.heardAtEpochMs = heardAtEpochMs;
            }
        }

        private final List<Item> items;

        public Live(List<Item> items) {
            this.items = new ArrayList<>(items);
        }

        public List<Item> items() {
            return new ArrayList<>(items);
        }
    }

    /** Payload prefix marking a venue advert ("V:" + venue name). */
    public static String venuePrefix() {
        return "V:";
    }

    /** True for venue-advert payloads ("V:Hall 2"); false for chat text. */
    public static boolean isVenueAdvert(String payload) {
        return payload != null && payload.length() > 2
                && payload.charAt(0) == 'V' && payload.charAt(1) == ':'
                && Character.isLetterOrDigit(payload.charAt(2));
    }

    /** Extract the venue name from a venue-advert payload. */
    public static String venueOf(String payload) {
        return isVenueAdvert(payload) ? payload.substring(2) : null;
    }

    private final long entryTtlMs;
    private final Map<String, Entry> byDevice = new ConcurrentHashMap<>();
    private final Map<String, Entry> byVenue = new ConcurrentHashMap<>();

    public VenueDirectory() {
        this(120_000L);
    }

    public VenueDirectory(long entryTtlMs) {
        this.entryTtlMs = entryTtlMs;
    }

    /**
     * Record a venue advert. Returns true if this is new information (new
     * device, new venue for the device, or a strictly shorter route to it).
     */
    public synchronized boolean observe(String venue, String deviceId, int hops) {
        prune();
        Entry existing = byDevice.get(deviceId);
        if (existing != null
                && existing.venue.equals(venue)
                && existing.hops <= hops
                && System.currentTimeMillis() - existing.heardAtEpochMs < entryTtlMs / 2) {
            return false;
        }
        Entry entry = new Entry(venue, deviceId, hops, System.currentTimeMillis());
        byDevice.put(deviceId, entry);
        byVenue.put(venue, entry);
        return true;
    }

    /** All live entries, newest first. */
    public synchronized List<Entry> entries() {
        prune();
        List<Entry> out = new ArrayList<>(byDevice.values());
        out.sort((a, b) -> Long.compare(b.heardAtEpochMs, a.heardAtEpochMs));
        return out;
    }

    /** Entries for one venue (multiple people can be at the same venue). */
    public synchronized List<Entry> entriesForVenue(String venue) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries()) {
            if (entry.venue.equals(venue)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Live venue names, nearest-hop-first (shortest relay path wins). */
    public synchronized List<String> venues() {
        prune();
        List<Entry> all = new ArrayList<>(byVenue.values());
        all.sort((a, b) -> Integer.compare(a.hops, b.hops));
        List<String> names = new ArrayList<>();
        for (Entry entry : all) {
            names.add(entry.venue);
        }
        return names;
    }

    /** Shortest known hop count to a venue, or -1 if unknown. */
    public synchronized int hopsTo(String venue) {
        prune();
        Entry entry = byVenue.get(venue);
        return entry == null ? -1 : entry.hops;
    }

    public synchronized int size() {
        prune();
        return byDevice.size();
    }

    /**
     * Copy the current live entries out (for persistence). Expired entries
     * are not included.
     */
    public synchronized Live liveSnapshot() {
        prune();
        List<Live.Item> items = new ArrayList<>();
        for (Entry e : byDevice.values()) {
            items.add(new Live.Item(e.venue, e.deviceId, e.hops, e.heardAtEpochMs));
        }
        return new Live(items);
    }

    /**
     * Replace the directory contents with a previously captured snapshot.
     * With {@code touchTimestamps} every restored entry is stamped "heard
     * now", giving it a full fresh TTL window — used when restoring cached
     * knowledge at startup so it stays navigable until real adverts refresh
     * or supersede it. Returns the number of entries restored.
     */
    public synchronized int restoreLive(Live live, boolean touchTimestamps) {
        byDevice.clear();
        byVenue.clear();
        long now = System.currentTimeMillis();
        for (Live.Item item : live.items()) {
            if (item.venue == null || item.deviceId == null) {
                continue;
            }
            long heardAt = touchTimestamps ? now : item.heardAtEpochMs;
            Entry entry = new Entry(item.venue, item.deviceId, item.hops, heardAt);
            byDevice.put(item.deviceId, entry);
            byVenue.put(item.venue, entry);
        }
        return byDevice.size();
    }

    public synchronized void clear() {
        byDevice.clear();
        byVenue.clear();
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - entryTtlMs;
        byDevice.values().removeIf(e -> e.heardAtEpochMs < cutoff);
        byVenue.values().removeIf(e -> e.heardAtEpochMs < cutoff);
    }
}
