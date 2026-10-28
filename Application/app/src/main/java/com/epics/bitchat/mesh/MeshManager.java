package com.epics.bitchat.mesh;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Graph;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Process-scoped owner of the mesh stack. Created lazily, started by the
 * foreground {@code MeshTransportService}, and observed by the ViewModels.
 * Settings changes are persisted to SharedPreferences and applied by
 * restarting the stack.
 */
public class MeshManager implements MeshEventListener {

    public static final String MODE_SIM = "sim";
    public static final String MODE_BLE = "ble";

    private static final String PREFS = "bitchat_mesh";
    private static final String KEY_NODE_NAME = "node_name";
    /** Marks node_name as user-chosen (never auto-replaced by the model name). */
    private static final String KEY_NODE_NAME_USER_SET = "node_name_user_set";
    /** Legacy default names that must not survive once real model names exist. */
    private static final String[] LEGACY_DEFAULT_NAMES = {"Phone A", "Phone"};
    private static final String KEY_NODE_COUNT = "node_count";
    private static final String KEY_LOSS = "loss_pct";
    private static final String KEY_MODE = "transport_mode";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_KNOWN_DEVICES = "known_devices";
    private static final String KEY_RELAY = "relay_enabled";
    private static final String KEY_AUTO_ANNOUNCE = "auto_announce";
    private static final String KEY_VENUE_DIRECTORY = "venue_directory";
    private static final String KEY_SERVER_URL = "server_base_url";

    private static final String DEVICE_ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** Wire id length — matches PacketCodec's on-mesh src/dst width. */
    private static final int DEVICE_ID_LEN = 3;

    private static volatile MeshManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final List<MeshEventListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, KnownDevice> knownDevices = new ConcurrentHashMap<>();

    private volatile MeshNode meshNode;
    private volatile SimulatedTransport simTransport;
    private volatile Graph graph;
    private volatile String deviceId;

    private MeshManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadKnownDevices();
        registerBtStateReceiver();
    }

    private static final String TAG = "BitChatMesh";

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                if (isRunning() && simTransport != null) {
                    String pinnedMode = prefs.getString(KEY_MODE, null);
                    if (pinnedMode == null || MODE_BLE.equals(pinnedMode)) {
                        // STATE_ON is the authoritative signal that Bluetooth is
                        // up — do NOT re-consult adapter.isEnabled() here: on
                        // some stacks (emulator netsim, some OEM builds) the
                        // broadcast arrives while isEnabled() still returns
                        // false, and the auto mode pick would wrongly fall
                        // back to the simulator.
                        Log.i(TAG, "Bluetooth turned on; switching from simulator to BLE");
                        restartWithMode(MODE_BLE);
                    }
                }
            } else if (state == BluetoothAdapter.STATE_OFF) {
                if (isRunning() && simTransport == null) {
                    Log.i(TAG, "Bluetooth turned off; falling back to simulator");
                    // Force SIM like the STATE_ON branch forces BLE: a plain
                    // restart() would re-read a pinned "ble" setting and
                    // rebuild a dead mesh (no radio → empty peer screens).
                    // STATE_ON switches back when the radio returns.
                    restartWithMode(MODE_SIM);
                }
            }
        }
    };

    private void registerBtStateReceiver() {
        try {
            appContext.registerReceiver(btStateReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "could not register Bluetooth state receiver", e);
        }
    }

    public static MeshManager get(Context context) {
        if (instance == null) {
            synchronized (MeshManager.class) {
                if (instance == null) {
                    instance = new MeshManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized void start() {
        start(null);
    }

    private synchronized void start(String forcedMode) {
        if (meshNode != null && meshNode.isRunning()) {
            return;
        }
        graph = DemoGraphFactory.buildCampusGraph();

        String nodeName = resolveNodeName();
        String mode = forcedMode != null
                ? forcedMode
                : prefs.getString(KEY_MODE, getDefaultMode());

        // A pinned BLE mode with the radio off would create a dead mesh
        // (no advertising/scanning → "No nodes in range yet" everywhere).
        // Run the simulator instead; the STATE_ON receiver switches back to
        // BLE when the radio comes up. Forced mode is STATE_ON's
        // authoritative signal, so it never consults the adapter (some
        // stacks broadcast STATE_ON while isEnabled() is still false).
        if (forcedMode == null && MODE_BLE.equals(mode) && !isBluetoothEnabled()) {
            Log.i(TAG, "Bluetooth is off; starting in SIM mode "
                    + "(the STATE_ON receiver switches back to BLE)");
            mode = MODE_SIM;
        }
        int nodeCount = prefs.getInt(KEY_NODE_COUNT, 6);
        float loss = prefs.getFloat(KEY_LOSS, 0.10f);

        if (MODE_BLE.equals(mode)) {
            Log.i(TAG, "starting mesh in BLE mode: id=" + getDeviceId() + " name=" + nodeName);
            String id = getDeviceId();
            BleAdvertiserTransport bleTransport = new BleAdvertiserTransport(appContext, id, nodeName);
            meshNode = new MeshNode(id, nodeName, bleTransport, new MessageLog());
            meshNode.setPositionEstimator(new PositionEstimator(graph));
            meshNode.setRelayEnabled(isRelayEnabled());
            meshNode.setAutoAnnounceEnabled(isAutoAnnounceEnabled());
            meshNode.addListener(this);
            meshNode.start();
            restoreCachedVenueDirectory();
            if (!bleTransport.isAvailable()) {
                meshNode.getLog().addEntry(new MeshLogEntry(
                        Direction.TX, id, "",
                        "BLE unavailable: " + bleTransport.getLastError() + blePermissionHint(),
                        MeshKind.PING, 0, 0, id, MeshStatus.DROPPED_TTL, "-"));
            }
        } else {
            Log.i(TAG, "starting mesh in SIM mode (Bluetooth unavailable, permissions, or user override)");
            SimulatedTransport.Config config = new SimulatedTransport.Config();
            config.nodeCount = Math.max(2, nodeCount);
            config.packetLossPct = loss;
            config.demoTraffic = true;
            config.beaconNames = Arrays.asList("Hall 1", "Hall 2", "Cafeteria");
            simTransport = new SimulatedTransport(config);
            meshNode = new MeshNode(simTransport.getLocalNodeId(), nodeName,
                    simTransport, simTransport.getLog());
            meshNode.setPositionEstimator(new PositionEstimator(graph));
            meshNode.setRelayEnabled(isRelayEnabled());
            meshNode.setAutoAnnounceEnabled(isAutoAnnounceEnabled());
            meshNode.addListener(this);
            meshNode.start();
            restoreCachedVenueDirectory();
        }
    }

    private String blePermissionHint() {
        if (hasBlePermissions()) {
            return "";
        }
        return " (grant Bluetooth + location permissions to use real devices)";
    }

    /** True when this device has an enabled Bluetooth radio. */
    private boolean isBluetoothEnabled() {
        BluetoothManager btManager =
                (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager == null ? null : btManager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /**
     * Preferred transport: real BLE when this device has an enabled Bluetooth
     * radio, otherwise the in-process simulator. Users can still override via
     * Settings.
     */
    public String getDefaultMode() {
        return isBluetoothEnabled() ? MODE_BLE : MODE_SIM;
    }

    /** Stable, unique 3-char wire id for this device (differs from the name).
     *  3 chars of the 32-char alphabet = 32k ids, and it must fit the
     *  compressed mesh packet header (PacketCodec v3, 14-char payloads). */
    public String getDeviceId() {
        if (deviceId == null) {
            synchronized (this) {
                if (deviceId == null) {
                    String id = prefs.getString(KEY_DEVICE_ID, null);
                    if (id == null || id.length() != DEVICE_ID_LEN) {
                        id = generateDeviceId();
                        prefs.edit().putString(KEY_DEVICE_ID, id).apply();
                    }
                    deviceId = id;
                }
            }
        }
        return deviceId;
    }

    private static String generateDeviceId() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(DEVICE_ID_LEN);
        for (int i = 0; i < DEVICE_ID_LEN; i++) {
            sb.append(DEVICE_ID_ALPHABET.charAt(random.nextInt(DEVICE_ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * True when the runtime permissions needed to scan/advertise are granted.
     * On Android 12+ the OS also requires location for BLE scan results (we do
     * not use neverForLocation), so that is part of the check here too.
     */
    public boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return has(Manifest.permission.BLUETOOTH_SCAN)
                    && has(Manifest.permission.BLUETOOTH_ADVERTISE)
                    && has(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return has(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean has(String permission) {
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public synchronized void restart() {
        stop();
        start();
    }

    /** Restart forcing the transport mode (used by the BT STATE_ON path to
     *  avoid the adapter.isEnabled() init race). */
    private synchronized void restartWithMode(String mode) {
        stop();
        start(mode);
    }

    public synchronized void stop() {
        if (meshNode != null) {
            meshNode.stop();
            meshNode = null;
        }
        simTransport = null;
        // Flush the venue directory so the throttle never loses the final
        // state before a restart (offline cache correctness > write count).
        writeVenueDirectoryToPrefs();
    }

    public boolean isRunning() {
        return meshNode != null && meshNode.isRunning();
    }

    public MeshNode getMeshNode() {
        return meshNode;
    }

    public Graph getGraph() {
        return graph;
    }

    public List<String> getReachableNodeIds() {
        if (simTransport != null) {
            return simTransport.getReachableNodeIds();
        }
        MeshNode node = meshNode;
        if (node != null) {
            return node.getPeers().isEmpty()
                    ? java.util.Collections.emptyList()
                    : idsFromPeers(node.getPeers());
        }
        return java.util.Collections.emptyList();
    }

    public String displayNameFor(String nodeId) {
        if (simTransport != null) {
            return simTransport.displayNameFor(nodeId);
        }
        MeshNode node = meshNode;
        if (node != null && node.getNetwork() != null && node.getNetwork().get(nodeId) != null) {
            return node.getNetwork().get(nodeId).getDisplayName();
        }
        KnownDevice known = knownDevices.get(nodeId);
        if (known != null) {
            return known.getDisplayName();
        }
        return nodeId;
    }

    private List<String> idsFromPeers(List<Peer> peers) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Peer peer : peers) {
            ids.add(peer.getNodeId());
        }
        return ids;
    }

    private void loadKnownDevices() {
        String json = prefs.getString(KEY_KNOWN_DEVICES, "");
        if (json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                knownDevices.put(o.getString("id"),
                        new KnownDevice(o.getString("id"), o.getString("name"), o.getLong("seen")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "could not parse known devices", e);
        }
    }

    private void persistKnownDevices() {
        JSONArray arr = new JSONArray();
        for (KnownDevice d : knownDevices.values()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", d.getNodeId());
                o.put("name", d.getDisplayName());
                o.put("seen", d.getLastSeenEpochMs());
                arr.put(o);
            } catch (JSONException ignored) {
                // skip malformed entry
            }
        }
        prefs.edit().putString(KEY_KNOWN_DEVICES, arr.toString()).apply();
    }

    /** Devices seen before, newest first; out-of-range ones are kept. */
    public List<KnownDevice> getKnownDevices() {
        List<KnownDevice> list = new ArrayList<>(knownDevices.values());
        list.sort((a, b) -> Long.compare(b.getLastSeenEpochMs(), a.getLastSeenEpochMs()));
        return list;
    }

    public boolean isReachable(String nodeId) {
        return getReachableNodeIds().contains(nodeId);
    }

    public void addListener(MeshEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MeshEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * The mesh display name for this phone: the hardware model (e.g.
     * "SM-E066B", "I2302") — automatically derived, no manual naming. A
     * name the user explicitly typed in Settings always wins; the legacy
     * "Phone A"-style defaults are migrated to the model name on first run
     * of this build (those names came from the old manual-install flow and
     * collide across a fleet of identical handsets).
     */
    public String resolveNodeName() {
        String model = defaultNodeName();
        if (prefs.getBoolean(KEY_NODE_NAME_USER_SET, false)) {
            String saved = prefs.getString(KEY_NODE_NAME, model);
            return saved == null || saved.isEmpty() ? model : saved;
        }
        String saved = prefs.getString(KEY_NODE_NAME, null);
        if (saved == null || saved.isEmpty() || isLegacyDefaultName(saved)) {
            prefs.edit().putString(KEY_NODE_NAME, model).apply();
            return model;
        }
        return saved;
    }

    /** Hardware model as the mesh identity (e.g. "SM-E066B" → "SM-E066B"). */
    public static String defaultNodeName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.isEmpty()) {
            return "Android";
        }
        return model;
    }

    private static boolean isLegacyDefaultName(String name) {
        for (String legacy : LEGACY_DEFAULT_NAMES) {
            if (legacy.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public int getNodeCount() {
        return prefs.getInt(KEY_NODE_COUNT, 6);
    }

    public float getLossPct() {
        return prefs.getFloat(KEY_LOSS, 0.10f);
    }

    public String getMode() {
        return prefs.getString(KEY_MODE, getDefaultMode());
    }

    /**
     * Base URL of the ai-service for server-side location (POST /position)
     * and the other real-test buttons. "" = not configured: server features
     * stay off instead of guessing the emulator alias on a real phone.
     */
    public String getServerBaseUrl() {
        return prefs.getString(KEY_SERVER_URL, "");
    }

    public void saveServerBaseUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url == null ? "" : url.trim()).apply();
    }

    /**
     * True when any network is up — gates server calls so an offline demo
     * (Wi-Fi off) never waits on a 4 s connect timeout.
     */
    public boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    /** True when this device acts as a relay hop for other users' messages. */
    public boolean isRelayEnabled() {
        return prefs.getBoolean(KEY_RELAY, true);
    }

    /** Toggle relay participation and apply it to the running stack immediately. */
    public void setRelayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RELAY, enabled).apply();
        MeshNode node = meshNode;
        if (node != null) {
            node.setRelayEnabled(enabled);
        }
    }

    /**
     * True when the phone auto-announces its measured venue, turning every
     * phone into a navigation beacon (on by default; only beacon-backed
     * positions are ever announced — see {@link MeshNode#autoAnnounceTick}).
     */
    public boolean isAutoAnnounceEnabled() {
        return prefs.getBoolean(KEY_AUTO_ANNOUNCE, true);
    }

    /** Toggle auto-announcing and apply it to the running stack immediately. */
    public void setAutoAnnounceEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_ANNOUNCE, enabled).apply();
        MeshNode node = meshNode;
        if (node != null) {
            node.setAutoAnnounceEnabled(enabled);
        }
    }

    /** Venue directory built from mesh venue adverts (multi-hop). */
    public VenueDirectory getVenueDirectory() {
        MeshNode node = meshNode;
        return node == null ? new VenueDirectory() : node.getVenueDirectory();
    }

    // --- venue-directory cache (offline navigation) -----------------------------

    /** Minimum spacing between venue-directory preference writes (#15):
     *  adverts re-flood every ~15 s per phone, so unthrottled persistence
     *  rewrote the prefs on every single heard advert. */
    private static final long VENUE_PERSIST_MIN_INTERVAL_MS = 30_000;

    private volatile long lastVenuePersistAtMs;
    private final Object venuePersistLock = new Object();

    /**
     * Every venue advert heard (including multi-hop) is persisted, so venue
     * knowledge survives restarts: on the next start the cached entries are
     * restored into the fresh directory and navigation keeps working with no
     * mesh in range until real adverts refresh or supersede them.
     *
     * <p>Writes are throttled to one per {@value #VENUE_PERSIST_MIN_INTERVAL_MS}
     * ms (frequent JSON rewrites of the same small directory achieved
     * nothing). {@link #stop()} flushes the final state so the throttle can
     * never lose the last change before a restart.
     */
    @Override
    public void onVenueDirectoryChanged() {
        persistVenueDirectory(false);
    }

    private void persistVenueDirectory(boolean force) {
        synchronized (venuePersistLock) {
            long now = System.currentTimeMillis();
            if (!force && now - lastVenuePersistAtMs < VENUE_PERSIST_MIN_INTERVAL_MS) {
                return;
            }
            lastVenuePersistAtMs = now;
        }
        writeVenueDirectoryToPrefs();
    }

    private void writeVenueDirectoryToPrefs() {
        try {
            VenueDirectory.Live live = getVenueDirectory().liveSnapshot();
            JSONArray arr = new JSONArray();
            for (VenueDirectory.Live.Item item : live.items()) {
                JSONObject o = new JSONObject();
                o.put("venue", item.venue);
                o.put("device", item.deviceId);
                o.put("hops", item.hops);
                o.put("heard", item.heardAtEpochMs);
                arr.put(o);
            }
            prefs.edit().putString(KEY_VENUE_DIRECTORY, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "could not persist venue directory", e);
        }
    }

    /**
     * Seed a freshly started node with the cached venue adverts (full fresh
     * TTL — each entry keeps navigation alive for 2 min, refreshed by the
     * 15 s advert cycle from any phone still at the venue, or simply used
     * as last-known-good data when the mesh is empty/offline).
     */
    public void restoreCachedVenueDirectory() {
        MeshNode node = meshNode;
        String json = prefs.getString(KEY_VENUE_DIRECTORY, "");
        if (node == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            List<VenueDirectory.Live.Item> items = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                items.add(new VenueDirectory.Live.Item(
                        o.getString("venue"), o.getString("device"),
                        o.getInt("hops"), o.getLong("heard")));
            }
            int restored = node.getVenueDirectory().restoreLive(
                    new VenueDirectory.Live(items), true);
            if (restored > 0) {
                Log.i(TAG, "restored " + restored + " cached venue adverts for offline navigation");
            }
        } catch (JSONException e) {
            Log.w(TAG, "could not restore venue directory", e);
        }
    }

    /**
     * Announce (or clear, with "") the venue this user is at over the mesh.
     * A manual announcement is also pinned as the auto-announce override so
     * the automatic estimate cannot displace the user's explicit claim.
     */
    /**
     * Push a server-originated update (arrived over the internet) into the
     * BLE mesh: flooded to every nearby phone with the SAME id for 5 s, and
     * phones that got the same event from their own internet feed dedup it.
     * No-op when the mesh is not running.
     */
    public boolean ingestFromInternet(String externalId, String payload) {
        MeshNode node = meshNode;
        return node != null && node.isRunning()
                && node.ingestFromInternet(externalId, payload);
    }

    /** The 3-char wire id an external event id maps to (test/log visibility). */
    public String wireMsgIdForExternal(String externalId) {
        return MeshNode.wireMsgIdFor(externalId);
    }

    /** True when this external event id already flowed through this node
     *  (sent or received) — i.e. a new ingest of it would be deduped. */
    public boolean hasSeenExternal(String externalId) {
        MeshNode node = meshNode;
        return node != null && node.hasSeenWireId(MeshNode.wireMsgIdFor(externalId));
    }

    public void setVenueAdvertised(String venue) {
        MeshNode node = meshNode;
        if (node != null) {
            node.setAutoAnnounceOverride(venue == null ? "" : venue.trim());
            node.announceVenue(venue == null ? "" : venue.trim());
        }
    }

    /** The current mesh display name (model-derived unless renamed in Settings). */
    public String getNodeName() {
        return resolveNodeName();
    }

    public void saveSettings(String nodeName, int nodeCount, float loss, String mode) {
        prefs.edit()
                .putString(KEY_NODE_NAME, nodeName)
                // Any explicit save (including restoring the model default from
                // Settings) counts as a user decision and stops auto-migration.
                .putBoolean(KEY_NODE_NAME_USER_SET, true)
                .putInt(KEY_NODE_COUNT, nodeCount)
                .putFloat(KEY_LOSS, loss)
                .putString(KEY_MODE, mode)
                .apply();
    }

    // --- forward mesh events to app listeners ---------------------------------

    @Override
    public void onLogEntry(MeshLogEntry entry) {
        for (MeshEventListener listener : listeners) {
            listener.onLogEntry(entry);
        }
    }

    @Override
    public void onPeerDiscovered(Peer peer) {
        knownDevices.put(peer.getNodeId(),
                new KnownDevice(peer.getNodeId(), peer.getDisplayName(), System.currentTimeMillis()));
        persistKnownDevices();
        for (MeshEventListener listener : listeners) {
            listener.onPeerDiscovered(peer);
        }
    }

    @Override
    public void onPeerLost(Peer peer) {
        for (MeshEventListener listener : listeners) {
            listener.onPeerLost(peer);
        }
    }

    @Override
    public void onMessageDelivered(MeshMessage message) {
        for (MeshEventListener listener : listeners) {
            listener.onMessageDelivered(message);
        }
    }

    @Override
    public void onDeliveryStatus(String token, String dstId, boolean delivered) {
        for (MeshEventListener listener : listeners) {
            listener.onDeliveryStatus(token, dstId, delivered);
        }
    }

    @Override
    public void onPositionUpdated(String graphNodeId) {
        for (MeshEventListener listener : listeners) {
            listener.onPositionUpdated(graphNodeId);
        }
    }
}
