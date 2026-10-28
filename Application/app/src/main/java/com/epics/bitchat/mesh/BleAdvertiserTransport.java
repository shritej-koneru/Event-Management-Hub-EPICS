package com.epics.bitchat.mesh;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real BLE transport for testing on physical devices.
 *
 * <p>Only other devices running this app are ever discovered: the scan is
 * unfiltered (the adverts carry manufacturer data, which a UUID-based filter
 * could never match) and everything that is not a manufacturer-data record
 * with id 0xFFFF is dropped in {@link #onScanResult}. Each device continuously
 * advertises its unique wire id + display name as an identity packet; outbound
 * mesh messages are advertised for a few seconds with several retransmits so
 * peer scanners reliably pick them up, after which the identity advert is
 * restored. Relays are re-advertised the same way, so multi-hop flood routing
 * works between phones.
 *
 * <p>Every radio call is guarded against {@code SecurityException} because the
 * user may start the mesh before granting the Bluetooth runtime permissions;
 * in that case {@link #isAvailable()} returns false and
 * {@link #getLastError()} explains why.
 */
public class BleAdvertiserTransport implements MeshTransport {

    public static final String MESH_UUID_STRING = "b1c0ffee-0000-1000-8000-00805f9b34fb";
    private static final String TAG = "BitChatMesh";
    private static final int MANUFACTURER_ID = 0xFFFF;
    private static final long BLAST_INTERVAL_MS = 150;
    private static final int BLAST_COUNT = 16;
    private static final long IDENTITY_REFRESH_MS = 10_000;
    private static final long PEER_TIMEOUT_MS = 30_000;
    /**
     * The scan is re-armed when no scan result has arrived for this long —
     * some radios silently stop delivering results while the stack still
     * reports running (observed as a 4-minute dead window in field tests).
     * A quiet room with zero phones also trips it, but a stop/start cycle is
     * harmless and self-heals a dead scan.
     */
    private static final long SCAN_STALL_MS = 60_000;
    /** Cap for the exponential retry after an explicit scan-start failure. */
    private static final long SCAN_RETRY_MAX_MS = 30_000;

    private final Context context;
    private final String localNodeId;
    private final String displayName;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();

    private volatile Listener listener;
    private volatile boolean running = false;
    private volatile boolean available = false;
    private volatile String lastError = "";

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;
    private volatile byte[] currentPacket;
    private volatile boolean blasting = false;
    private int blastRemaining = 0;
    private volatile long lastScanResultAtMs;
    private int scanFailures = 0;
    /**
     * Liveness state machine for the scan (see {@link ScanWatchdog}): raw
     * + mesh-traffic stalls escalate from a cheap scanner re-arm to a full
     * rebuild of the scanner/advertiser objects — the app-restart cure
     * without the restart (found needed on a MIUI phone whose scan stayed
     * silently dead across repeated re-arms).
     */
    private final ScanWatchdog scanHealth = new ScanWatchdog(2);

    public BleAdvertiserTransport(Context context, String nodeId, String displayName) {
        this.context = context.getApplicationContext();
        this.localNodeId = PacketCodec.sanitizeNodeId(nodeId).trim();
        this.displayName = PacketCodec.sanitizeNodeId(displayName == null ? nodeId : displayName).trim();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager == null ? null : btManager.getAdapter();
        if (adapter == null) {
            available = false;
            lastError = "no Bluetooth adapter on this device";
            Log.e(TAG, "start failed: " + lastError);
            return;
        }
        if (!adapter.isEnabled()) {
            available = false;
            lastError = "Bluetooth is off (turn it on in Quick Settings)";
            Log.e(TAG, "start failed: " + lastError);
            return;
        }
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            available = false;
            lastError = "Bluetooth permissions not granted";
            Log.e(TAG, "start failed: " + lastError);
            return;
        }
        if (advertiser == null || scanner == null) {
            available = false;
            lastError = "BLE advertising/scanning not supported on this device";
            Log.e(TAG, "start failed: " + lastError);
            return;
        }

        available = true;
        lastError = "";
        running = true;
        Log.i(TAG, "BLE transport started: id=" + localNodeId + " name=" + displayName);

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                // no-op
            }

            @Override
            public void onStartFailure(int errorCode) {
                handleAdvertiseFailure(errorCode);
            }
        };
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                lastScanResultAtMs = System.currentTimeMillis();
                scanFailures = 0; // scan works again — reset the backoff
                scanHealth.onScanCallback(lastScanResultAtMs);
                BleAdvertiserTransport.this.onScanResult(result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                handleScanFailed(errorCode);
            }
        };

        advertiseIdentity();
        startScan();
        lastScanResultAtMs = System.currentTimeMillis();
        handler.removeCallbacks(scanWatchdog);
        handler.postDelayed(scanWatchdog, SCAN_STALL_MS);
        handler.removeCallbacks(identityRefresh);
        handler.postDelayed(identityRefresh, IDENTITY_REFRESH_MS);

        Listener l = listener;
        if (l != null) {
            l.onTransportStarted();
        }
    }

    private final Runnable identityRefresh = new Runnable() {
        @Override
        public void run() {
            if (running) {
                if (!blasting) {
                    if (available) {
                        evaluateScanHealth(); // faster verdicts than the 60 s watchdog
                        advertiseIdentity();
                    } else {
                        attemptRecovery();
                    }
                }
                handler.postDelayed(this, IDENTITY_REFRESH_MS);
            }
        }
    };

    /**
     * Self-healing: a transient failure (scan throttling, a BT stack hiccup,
     * adapter off/on) used to flip {@code available} off permanently, after
     * which every send was silently dropped while identity adverts kept
     * flowing — the app looked alive but could not talk (found on hardware).
     * Re-check the radio, re-acquire the advertiser/scanner and come back.
     */
    private void attemptRecovery() {
        BluetoothManager btManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager == null ? null : btManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            lastError = "Bluetooth is off (turn it on in Quick Settings)";
            return; // nothing to recover onto; retry on the next refresh tick
        }
        try {
            advertiser = adapter.getBluetoothLeAdvertiser();
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            lastError = "Bluetooth permissions not granted";
            Log.w(TAG, "recovery failed: " + lastError);
            return;
        }
        if (advertiser == null || scanner == null) {
            return; // radio not ready yet; retry on the next refresh tick
        }
        available = true;
        Log.i(TAG, "BLE transport recovered (previous error: "
                + (lastError.isEmpty() ? "none" : lastError) + ")");
        lastError = "";
        advertiseIdentity();
        startScan();
        lastScanResultAtMs = System.currentTimeMillis();
        scanHealth.onRearmed(lastScanResultAtMs); // fresh probe window
    }

    private static final List<ScanFilter> NO_FILTERS = Collections.emptyList();

    private ScanSettings buildScanSettings() {
        return new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
    }

    /**
     * Stall watchdog: re-arms the scan when nothing has arrived for a while
     * (silent radio death) and reschedules itself. Runs for the transport's
     * whole lifetime; {@code stop()} clears all handler callbacks.
     */
    private final Runnable scanWatchdog = new Runnable() {
        @Override
        public void run() {
            // Always reschedule while running — a null scanner (adapter off)
            // must not kill the watchdog forever, recovery re-arms it.
            if (running) {
                evaluateScanHealth();
                handler.postDelayed(this, SCAN_STALL_MS);
            }
        }
    };

    /**
     * Watchdog verdict → action. REARM_SCAN stop/starts the scanner (cheap);
     * FULL_RECOVERY rebuilds the scanner/advertiser objects — the observed
     * cure for a MIUI scan that stayed silently dead across re-arms.
     */
    private void evaluateScanHealth() {
        if (!running || !available) {
            return; // attemptRecovery owns the down state
        }
        long now = System.currentTimeMillis();
        ScanWatchdog.Action action = scanHealth.evaluate(now);
        if (action == ScanWatchdog.Action.REARM_SCAN) {
            Log.w(TAG, "scan stall detected — re-arming BLE scan");
            try {
                scanner.stopScan(scanCallback);
            } catch (IllegalArgumentException | IllegalStateException
                    | SecurityException ignored) {
                // scan already stopped / adapter off / permission revoked
            }
            startScan();
            scanHealth.onRearmed(now);
        } else if (action == ScanWatchdog.Action.FULL_RECOVERY) {
            Log.w(TAG, "scan still dead after repeated re-arms — rebuilding radio objects");
            available = false;
            lastError = "BLE scan silent after repeated re-arms — rebuilding radio";
            scanHealth.onRearmed(now);
            attemptRecovery();
        }
    }

    /**
     * Scan-start failure (#6/#8): never swallow it. Surface the reason in
     * {@link #lastError} and retry with exponential backoff — SCANNING_TOO_
     * FREQUENTLY in particular needs a pause before re-arming.
     */
    private void handleScanFailed(int errorCode) {
        String reason;
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                reason = "scan already started";
                break;
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                reason = "scan registration failed";
                break;
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                reason = "BLE scanning unsupported on this device";
                break;
            case ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
                reason = "BLE scanner out of hardware resources";
                break;
            case ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY:
                reason = "app started/stopped scanning too often (backing off)";
                break;
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
            default:
                reason = "BLE scanner internal error (code " + errorCode + ")";
                break;
        }
        lastError = "BLE scan failed: " + reason;
        Log.e(TAG, lastError);

        if (ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED == errorCode) {
            available = false; // no amount of retrying fixes this
            return;
        }
        scheduleScanRetry();
    }

    /** Exponential backoff shared by every scan-start failure (1s..30s). */
    private void scheduleScanRetry() {
        scanFailures++;
        long delay = Math.min(SCAN_RETRY_MAX_MS, 1000L << Math.min(scanFailures - 1, 5));
        Log.i(TAG, "re-arming BLE scan in " + delay + " ms");
        handler.removeCallbacks(scanRetry);
        handler.postDelayed(scanRetry, delay);
    }

    private final Runnable scanRetry = new Runnable() {
        @Override
        public void run() {
            if (running && scanner != null) {
                startScan();
            }
        }
    };

    /** Advertising-start failure (#8): surface it — a failed send must not be
     *  indistinguishable from an out-of-range peer. */
    private void handleAdvertiseFailure(int errorCode) {
        String reason;
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                reason = "advert packet too large for this radio (DATA_TOO_LARGE)";
                break;
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                reason = "advertising already started";
                break;
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                reason = "too many advertisers on this device";
                break;
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                reason = "BLE advertising unsupported on this device";
                break;
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
            default:
                reason = "advertiser internal error (code " + errorCode + ")";
                break;
        }
        lastError = "BLE advertise failed: " + reason;
        Log.e(TAG, lastError + " (packet bytes="
                + (currentPacket == null ? 0 : currentPacket.length) + ")");
        if (AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED == errorCode) {
            available = false;
        }
    }

    private void startScan() {
        if (!running || scanner == null) {
            return;
        }
        try {
            scanner.startScan(NO_FILTERS, buildScanSettings(), scanCallback);
        } catch (SecurityException e) {
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) {
                available = false;
                lastError = "Bluetooth scan permission not granted";
                Log.e(TAG, lastError);
            } else if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                available = false;
                lastError = "Location permission not granted (needed for BLE scanning)";
                Log.e(TAG, lastError);
            } else if (!isLocationEnabled()) {
                available = false;
                lastError = "Location services are off (enable them for BLE scanning)";
                Log.e(TAG, lastError);
            } else {
                // Transient — several OEM stacks throw SecurityException here
                // when a stop/start lands inside a scan-throttling window.
                // Flipping the transport off for this silently dead-dropped
                // every later send (found on hardware): stay up, retry later.
                lastError = "BLE scan start blocked transiently: " + e.getMessage();
                Log.w(TAG, lastError + " — retrying with backoff");
                scheduleScanRetry();
            }
        } catch (IllegalStateException e) {
            // adapter off mid-operation — transient, recovery will re-check
            Log.w(TAG, "BLE scan start skipped (adapter busy/off): " + e.getMessage());
        }
    }

    private boolean has(String permission) {
        return context.checkSelfPermission(permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER));
        } catch (Exception e) {
            return false;
        }
    }

    private void advertiseIdentity() {
        byte[] packet = BeaconParser.encode(localNodeId, displayName, 0, false);
        startAdvertising(packet);
    }

    private void startAdvertising(byte[] packet) {
        if (!running || advertiser == null) {
            return;
        }
        currentPacket = packet;
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MANUFACTURER_ID, packet)
                .build();
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build();
        try {
            advertiser.stopAdvertising(advertiseCallback);
        } catch (IllegalArgumentException ignored) {
            // not currently advertising
        } catch (SecurityException e) {
            available = false;
            lastError = "Bluetooth permissions revoked";
            Log.e(TAG, lastError);
            return;
        }
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (IllegalStateException e) {
            // adapter off mid-operation — transient, recovery re-checks it
            Log.w(TAG, "advertise start skipped (adapter busy/off): " + e.getMessage());
        } catch (SecurityException e) {
            available = false;
            lastError = "Bluetooth permissions revoked";
            Log.e(TAG, lastError);
        }
    }

    @Override
    public void send(MeshMessage message) {
        if (!running || !available) {
            // A silent drop here is indistinguishable from "peer out of
            // range" on the status line and hid a dead transport for a whole
            // test session — always say why the send never left.
            Log.w(TAG, "send dropped — transport not usable (running=" + running
                    + ", available=" + available + ")"
                    + (lastError.isEmpty() ? "" : "; last error: " + lastError));
            return;
        }
        byte[] packet = PacketCodec.encode(message);
        handler.post(() -> {
            blasting = true;
            startAdvertising(packet);
            blastRemaining = BLAST_COUNT - 1;
            handler.removeCallbacks(blastRunnable);
            handler.postDelayed(blastRunnable, BLAST_INTERVAL_MS);
        });
    }

    private final Runnable blastRunnable = new Runnable() {
        @Override
        public void run() {
            if (blasting && currentPacket != null) {
                startAdvertising(currentPacket);
                if (--blastRemaining > 0) {
                    handler.postDelayed(this, BLAST_INTERVAL_MS);
                } else {
                    blasting = false;
                    advertiseIdentity();
                }
            }
        }
    };

    private void onScanResult(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        if (device == null) {
            return;
        }
        byte[] serviceData = result.getScanRecord() == null ? null
                : result.getScanRecord().getManufacturerSpecificData(MANUFACTURER_ID);
        if (serviceData == null || serviceData.length == 0) {
            return;
        }

        int rssi = result.getRssi();
        BlePacketInterpreter.Hit hit =
                BlePacketInterpreter.interpret(serviceData, rssi, localNodeId);
        switch (hit.reason) {
            case IDENTITY:
                scanHealth.onMeshPacket(System.currentTimeMillis());
                Peer peer = peers.get(hit.nodeId);
                boolean isNew = peer == null;
                if (isNew) {
                    peer = new Peer(hit.nodeId, hit.displayName, device.getAddress(), false);
                    peers.put(hit.nodeId, peer);
                    Log.d(TAG, "peer discovered: " + hit.nodeId + " (" + hit.displayName
                            + ") @ " + rssi + "dBm " + device.getAddress());
                }
                peer.setRssi(rssi);
                peer.setLastSeenEpochMs(System.currentTimeMillis());
                return;
            case MESSAGE:
                scanHealth.onMeshPacket(System.currentTimeMillis());
                Peer source = peers.get(hit.message.getSrcId());
                if (source == null) {
                    source = new Peer(hit.message.getSrcId(), hit.message.getSrcId(),
                            device.getAddress(), false);
                    peers.put(hit.message.getSrcId(), source);
                }
                source.setRssi(rssi);
                source.setLastSeenEpochMs(System.currentTimeMillis());

                Listener l = listener;
                if (l != null) {
                    l.onPacketReceived(hit.message, rssi, hit.message.getSrcId());
                }
                return;
            case SELF:
                // Our own advert echoed back — the radio link itself is alive.
                scanHealth.onMeshPacket(System.currentTimeMillis());
                return;
            case BEACON:
            case UNKNOWN:
            default:
                return;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        blasting = false;
        handler.removeCallbacksAndMessages(null);
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (IllegalStateException ignored) {
                // scanner already stopped
            } catch (SecurityException ignored) {
                // permission revoked
            }
        }
        if (advertiser != null && advertiseCallback != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (IllegalArgumentException ignored) {
                // not advertising
            } catch (SecurityException ignored) {
                // permission revoked
            }
        }
        peers.clear();
        Listener l = listener;
        if (l != null) {
            l.onTransportStopped();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getLocalNodeId() {
        return localNodeId;
    }

    @Override
    public List<Peer> getPeers() {
        long cutoff = System.currentTimeMillis() - PEER_TIMEOUT_MS;
        List<Peer> fresh = new ArrayList<>();
        for (Iterator<Map.Entry<String, Peer>> it = peers.entrySet().iterator(); it.hasNext(); ) {
            Peer peer = it.next().getValue();
            if (peer.getLastSeenEpochMs() >= cutoff) {
                fresh.add(peer);
            } else {
                it.remove();
            }
        }
        return fresh;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getLastError() {
        return lastError;
    }
}
