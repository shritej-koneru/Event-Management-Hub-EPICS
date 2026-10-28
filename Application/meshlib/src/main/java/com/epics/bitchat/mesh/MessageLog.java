package com.epics.bitchat.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe ring buffer of {@link MeshLogEntry} rows shown in the traffic
 * console. Listeners are notified on the thread that produced the entry.
 */
public class MessageLog {
    public interface Listener {
        void onNewEntry(MeshLogEntry entry);
    }

    private final int maxSize;
    private final List<MeshLogEntry> entries = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    public MessageLog() {
        this(500);
    }

    public MessageLog(int maxSize) {
        this.maxSize = maxSize;
    }

    public synchronized void addEntry(MeshLogEntry entry) {
        entries.add(entry);
        if (entries.size() > maxSize) {
            entries.remove(0);
        }
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onNewEntry(entry);
        }
    }

    public synchronized List<MeshLogEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
