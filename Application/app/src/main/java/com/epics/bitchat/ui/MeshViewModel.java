package com.epics.bitchat.ui;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.epics.bitchat.ai.AiServiceClient;
import com.epics.bitchat.mesh.MeshEventListener;
import com.epics.bitchat.mesh.MeshKind;
import com.epics.bitchat.mesh.KnownDevice;
import com.epics.bitchat.mesh.MeshLogEntry;
import com.epics.bitchat.mesh.MeshManager;
import com.epics.bitchat.mesh.MeshMessage;
import com.epics.bitchat.mesh.MeshNode;
import com.epics.bitchat.mesh.Peer;
import com.epics.bitchat.mesh.VenueDirectory;
import com.epics.bitchat.nav.Graph;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridges the mesh stack (worker threads) to the UI (main thread) via
 * LiveData. Owned by the activity so it survives fragment re-creation.
 */
public class MeshViewModel extends AndroidViewModel implements MeshEventListener {

    private static final long PEER_REFRESH_MS = 2000;
    /** Min gap between POST /position calls from the nav screen. */
    private static final long SERVER_POSITION_REFRESH_MS = 10_000;
    /** Cap on the walked-path trail (≈40 min of 10 s samples, oldest dropped). */
    private static final int MAX_TRAIL_POINTS = 240;
    /** Fixes closer than this collapse into one dot (an unmoving phone). */
    private static final double TRAIL_MIN_STEP_M = 0.5;

    /** A send target; out-of-range devices are still reachable via relay hops. */
    public static final class Destination {
        public final String nodeId;
        public final String label;
        public final boolean inRange;

        Destination(String nodeId, String label, boolean inRange) {
            this.nodeId = nodeId;
            this.label = label;
            this.inRange = inRange;
        }
    }

    /** One sampled server position fix (graph coords) in the walked path. */
    public static final class TrailPoint {
        public final double x;
        public final double y;

        TrailPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private final MeshManager meshManager;
    private final MutableLiveData<List<MeshLogEntry>> logs = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<List<Peer>> peers = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> position = new MutableLiveData<>("");
    /** Provenance of the position estimate: "beacon", "mesh" or "none". */
    private final MutableLiveData<String> positionSource = new MutableLiveData<>("none");
    /**
     * Server-side weighted position from POST /position — a one-line status
     * ("Server: Hall 1 (≈0.5 m, 3 anchors, 67%)") or null when the server
     * feature is off (no URL in Settings), offline, or nothing to send.
     */
    private final MutableLiveData<String> serverPosition = new MutableLiveData<>(null);
    private volatile boolean serverPositionInFlight = false;
    private long lastServerPositionMs = 0;
    /**
     * Walked path: sampled POST /position fixes, oldest → newest, drawn as
     * a dashed trail on the Navigate map. Survives tab switches (activity-
     * scoped ViewModel); cleared only via {@link #clearServerTrail()} or an
     * app restart.
     */
    private final MutableLiveData<List<TrailPoint>> serverTrail =
            new MutableLiveData<>(new ArrayList<>());
    private final Object trailLock = new Object();
    private List<TrailPoint> trailPoints = new ArrayList<>();
    private final MutableLiveData<String> eventMessage = new MutableLiveData<>("");
    private final MutableLiveData<String> deliveryStatus = new MutableLiveData<>("");
    private final MutableLiveData<List<VenueDirectory.Entry>> venueDirectory =
            new MutableLiveData<>(Collections.emptyList());

    /**
     * Last destination picked on the Send tab (node id or "") — quick-send
     * chips honor it when it is still reachable (#10).
     */
    private final MutableLiveData<String> lastDestinationId = new MutableLiveData<>("");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable peerRefresher = new Runnable() {
        @Override
        public void run() {
            refreshPeers();
            refreshServerPosition();
            handler.postDelayed(this, PEER_REFRESH_MS);
        }
    };

    public MeshViewModel(@NonNull Application application) {
        super(application);
        meshManager = MeshManager.get(application);
        meshManager.addListener(this);
        meshManager.start();
        refreshLogs();
        refreshPeers();
        refreshVenues();
        handler.postDelayed(peerRefresher, PEER_REFRESH_MS);
    }

    /**
     * Push the current directory into the LiveData (constructor seeding +
     * restore-from-cache). Without it the Navigate tab stays empty after a
     * restart because onVenueDirectoryChanged only fires for NEW adverts.
     */
    private void refreshVenues() {
        venueDirectory.postValue(meshManager.getVenueDirectory().entries());
    }

    /**
     * POST the current venue-advert anchors (beacon + hops) to the
     * ai-service /position endpoint — the server's weighted-centroid twin of
     * the on-device estimator — and publish a one-line result for the
     * Navigate tab. Cheap no-ops unless a base URL is configured in
     * Settings and a network is up, so an offline demo never waits on a
     * timeout; at most one request in flight, at most one per 10 s.
     */
    private void refreshServerPosition() {
        String raw = meshManager.getServerBaseUrl();
        if (raw.isEmpty()) {
            serverPosition.postValue(null);
            return;
        }
        if (!meshManager.hasNetwork()) {
            serverPosition.postValue(null);
            return;
        }
        long now = System.currentTimeMillis();
        if (serverPositionInFlight || now - lastServerPositionMs < SERVER_POSITION_REFRESH_MS) {
            return;
        }
        List<VenueDirectory.Entry> entries = meshManager.getVenueDirectory().entries();
        MeshNode node = meshManager.getMeshNode();
        String ownVenue = node == null ? "" : node.getAdvertisedVenue();
        if (ownVenue == null) {
            ownVenue = "";
        }
        if (entries.isEmpty() && ownVenue.isEmpty()) {
            serverPosition.postValue(null);
            return;
        }
        JSONArray anchors = new JSONArray();
        try {
            // Our own announced venue first, as a 0-hop anchor: the
            // directory only records adverts RECEIVED from other phones, so
            // a phone alone in a room would send nothing — yet the venue we
            // advertise is by definition where WE are. When other phones'
            // adverts already include it, skip the duplicate.
            boolean ownHeard = false;
            for (VenueDirectory.Entry entry : entries) {
                if (entry.venue.equals(ownVenue)) {
                    ownHeard = true;
                    break;
                }
            }
            if (!ownVenue.isEmpty() && !ownHeard) {
                JSONObject own = new JSONObject();
                own.put("beacon", ownVenue);
                own.put("hops", 0);
                anchors.put(own);
            }
            for (VenueDirectory.Entry entry : entries) {
                JSONObject anchor = new JSONObject();
                anchor.put("beacon", entry.venue);
                anchor.put("hops", entry.hops);
                anchors.put(anchor);
            }
            if (anchors.length() == 0) {
                serverPosition.postValue(null);
                return;
            }
        } catch (JSONException e) {
            return;
        }
        lastServerPositionMs = now;
        serverPositionInFlight = true;
        AiServiceClient.postPosition(AiServiceClient.normalizeBaseUrl(raw), anchors, null,
                result -> {
                    serverPositionInFlight = false;
                    serverPosition.postValue(String.format(java.util.Locale.US,
                            "Server: %s (≈%.1f m, %d anchors, %d%%%s)",
                            result.nodeId, result.distanceMeters, result.anchorsUsed,
                            Math.round(result.confidence * 100),
                            result.anchorsSkipped > 0
                                    ? ", " + result.anchorsSkipped + " skipped" : ""));
                    appendServerTrailSample(result.x, result.y);
                },
                error -> {
                    serverPositionInFlight = false;
                    serverPosition.postValue(
                            "Server: unreachable — start ai-service or fix the URL in Settings");
                });
    }

    /**
     * Record a server fix on the walked-path trail. Near-identical fixes
     * collapse into one dot (standing still leaves a single marker), the
     * list is capped oldest-first, and each change is published as the same
     * immutable snapshot the observer receives. Called from the HTTP
     * callback thread — guarded by {@link #trailLock}.
     */
    private void appendServerTrailSample(double x, double y) {
        synchronized (trailLock) {
            if (!trailPoints.isEmpty()) {
                TrailPoint last = trailPoints.get(trailPoints.size() - 1);
                double dx = x - last.x;
                double dy = y - last.y;
                if (dx * dx + dy * dy < TRAIL_MIN_STEP_M * TRAIL_MIN_STEP_M) {
                    return;
                }
            }
            List<TrailPoint> next = new ArrayList<>(trailPoints);
            next.add(new TrailPoint(x, y));
            while (next.size() > MAX_TRAIL_POINTS) {
                next.remove(0);
            }
            trailPoints = next;
            serverTrail.postValue(next);
        }
    }

    /** Walked-path trail for the Navigate map (oldest → newest). */
    public LiveData<List<TrailPoint>> getServerTrail() {
        return serverTrail;
    }

    /** Wipe the walked path — "Clear trail" on the Navigate tab. */
    public void clearServerTrail() {
        synchronized (trailLock) {
            trailPoints = new ArrayList<>();
            serverTrail.postValue(trailPoints);
        }
    }

    @Override
    protected void onCleared() {
        meshManager.removeListener(this);
        handler.removeCallbacks(peerRefresher);
        super.onCleared();
    }

    private void refreshLogs() {
        MeshNode node = meshManager.getMeshNode();
        if (node == null) {
            return;
        }
        logs.postValue(node.getLog().getEntries());
    }

    private void refreshPeers() {
        MeshNode node = meshManager.getMeshNode();
        if (node == null) {
            return;
        }
        peers.postValue(node.getPeers());
        String pos = node.getLastPositionNodeId();
        if (pos != null) {
            position.postValue(pos);
        }
        pushPositionSource(node);
    }

    private void pushPositionSource(MeshNode node) {
        MeshNode.AnchorSource source = node.getLastPositionSource();
        positionSource.postValue(source == null
                ? "none" : source.name().toLowerCase());
    }

    public LiveData<List<MeshLogEntry>> getLogs() {
        return logs;
    }

    public LiveData<List<Peer>> getPeers() {
        return peers;
    }

    public LiveData<String> getPosition() {
        return position;
    }

    /** Where the current position estimate comes from: "beacon", "mesh" or "none". */
    public LiveData<String> getPositionSource() {
        return positionSource;
    }

    /** Server-side position line (POST /position), null when off/unavailable. */
    public LiveData<String> getServerPosition() {
        return serverPosition;
    }

    public LiveData<String> getEventMessage() {
        return eventMessage;
    }

    public LiveData<String> getDeliveryStatus() {
        return deliveryStatus;
    }

    /** Venues heard over the mesh (incl. multi-hop adverts), newest first. */
    public LiveData<List<VenueDirectory.Entry>> getVenueDirectory() {
        return venueDirectory;
    }

    public boolean isRelayEnabled() {
        return meshManager.isRelayEnabled();
    }

    public void setRelayEnabled(boolean enabled) {
        meshManager.setRelayEnabled(enabled);
    }

    public boolean isAutoAnnounceEnabled() {
        return meshManager.isAutoAnnounceEnabled();
    }

    public void setAutoAnnounceEnabled(boolean enabled) {
        meshManager.setAutoAnnounceEnabled(enabled);
    }

    public void setAdvertisedVenue(String venue) {
        meshManager.setVenueAdvertised(venue);
    }

    public boolean send(String dstId, MeshKind kind, String payload) {
        MeshNode node = meshManager.getMeshNode();
        return node != null && node.send(dstId, kind, payload);
    }

    public List<String> getReachableIds() {
        return meshManager.getReachableNodeIds();
    }

    /** Known devices as send targets: in-range first, then out-of-range ones. */
    public List<Destination> getKnownDestinations() {
        List<String> reachable = getReachableIds();
        List<Destination> out = new ArrayList<>();
        for (String id : reachable) {
            out.add(new Destination(id, displayNameFor(id) + " (" + id + ")", true));
        }
        for (KnownDevice d : meshManager.getKnownDevices()) {
            if (!reachable.contains(d.getNodeId())) {
                out.add(new Destination(d.getNodeId(),
                        d.getDisplayName() + " (" + d.getNodeId() + ") (out of range)", false));
            }
        }
        return out;
    }

    public String displayNameFor(String nodeId) {
        return meshManager.displayNameFor(nodeId);
    }

    public Graph getGraph() {
        return meshManager.getGraph();
    }

    public MeshManager getMeshManager() {
        return meshManager;
    }

    /** Last destination chosen on the Send tab ("" = none yet). */
    public LiveData<String> getLastDestinationId() {
        return lastDestinationId;
    }

    public void setLastDestinationId(String nodeId) {
        lastDestinationId.setValue(nodeId == null ? "" : nodeId);
    }

    // --- mesh events (worker threads) ----------------------------------------

    @Override
    public void onLogEntry(MeshLogEntry entry) {
        refreshLogs();
    }

    @Override
    public void onPeerDiscovered(Peer peer) {
        refreshPeers();
    }

    @Override
    public void onPeerLost(Peer peer) {
        refreshPeers();
    }

    @Override
    public void onMessageDelivered(MeshMessage message) {
        String text = "Delivered: \"" + message.getPayload() + "\" to " + meshManager.displayNameFor(message.getDstId());
        eventMessage.postValue(text);
    }

    @Override
    public void onDeliveryStatus(String token, String dstId, boolean delivered) {
        String name = meshManager.displayNameFor(dstId);
        String peer = name + " (" + dstId + ")";
        deliveryStatus.postValue(delivered
                ? "Delivered to " + peer
                : peer + " is out of range");
    }

    @Override
    public void onPositionUpdated(String graphNodeId) {
        position.postValue(graphNodeId);
        MeshNode node = meshManager.getMeshNode();
        if (node != null) {
            pushPositionSource(node);
        }
    }

    @Override
    public void onVenueDirectoryChanged() {
        refreshVenues();
    }
}
