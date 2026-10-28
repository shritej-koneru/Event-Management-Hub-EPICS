package com.epics.bitchat.ui.test;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.epics.bitchat.R;
import com.epics.bitchat.ai.AiServiceClient;
import com.epics.bitchat.mesh.MeshManager;
import com.epics.bitchat.mesh.MeshNode;
import com.epics.bitchat.mesh.Peer;
import com.epics.bitchat.mesh.PositionEstimator;
import com.epics.bitchat.mesh.RssiFilter;
import com.epics.bitchat.mesh.VenueDirectory;
import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Graph;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * TEST SCREEN — positioning. Simulated: synthetic beacon peers fed through
 * {@link RssiFilter} into {@link PositionEstimator} (strongest-anchor-wins),
 * with auto-flicker noise. Real: the same estimator chain is driven by the
 * live mesh — BLE peers in range plus venue adverts relayed over the mesh
 * (phones acting as navigation anchors, no hardware beacons required) —
 * carry the phone around the venue and watch the estimate follow.
 * Server: the same anchors POSTed to ai-service /position, the on-device
 * estimator's server-side twin.
 */
public class PositioningTestActivity extends AppCompatActivity {

    private Graph graph;
    private PositionEstimator estimator;
    private final Map<String, Peer> beacons = new HashMap<>();
    private final Map<String, RssiFilter> filters = new HashMap<>();
    private final Random random = new Random();

    private boolean watchingLive = false;
    private String lastLiveEstimate = null;

    private Spinner beaconSpinner;
    private EditText rssiInput;
    private TextView positionOutput;
    private TextView serverOutput;
    private TextView beaconTable;
    private MaterialButton watchButton;
    private MaterialButton serverButton;
    private EditText hostInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean flickering = false;

    private final Runnable flickerStep = new Runnable() {
        @Override
        public void run() {
            if (!flickering) {
                return;
            }
            for (Peer beacon : beacons.values()) {
                int base = beacon.getRssi() == 0 ? -70 : beacon.getRssi();
                feed(beacon.getNodeId(), base + random.nextInt(9) - 4);
            }
            render();
            handler.postDelayed(this, 700);
        }
    };

    private final Runnable liveStep = new Runnable() {
        @Override
        public void run() {
            if (!watchingLive) {
                return;
            }
            renderLive();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_positioning_test);

        graph = DemoGraphFactory.buildCampusGraph();
        estimator = new PositionEstimator(graph);

        beaconSpinner = findViewById(R.id.test_beacon_spinner);
        rssiInput = findViewById(R.id.test_rssi_input);
        positionOutput = findViewById(R.id.test_position_output);
        beaconTable = findViewById(R.id.test_beacon_table);
        MaterialButton update = findViewById(R.id.test_update_button);
        MaterialButton flicker = findViewById(R.id.test_flicker_button);
        MaterialButton realOnce = findViewById(R.id.test_real_position_button);
        watchButton = findViewById(R.id.test_real_watch_button);
        hostInput = findViewById(R.id.test_host_input);
        serverButton = findViewById(R.id.test_position_server_button);
        serverOutput = findViewById(R.id.test_position_server_output);
        // Shared with Settings ▸ ai-service URL (also used by the Navigate
        // tab's server position) — set it once, every screen reuses it.
        String savedHost = MeshManager.get(getApplicationContext()).getServerBaseUrl();
        if (!savedHost.isEmpty()) {
            hostInput.setText(savedHost);
        }

        List<String> beaconNames = new ArrayList<>();
        for (String id : DemoGraphFactory.beaconNodeIds()) {
            beacons.put(id, new Peer(id, id + " Beacon", "beacon:" + id, true));
            filters.put(id, new RssiFilter());
            beaconNames.add(id);
        }
        beaconSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, beaconNames));

        update.setOnClickListener(v -> {
            String text = rssiInput.getText().toString();
            int rssi = text.isEmpty() ? -60 : Integer.parseInt(text);
            String id = (String) beaconSpinner.getSelectedItem();
            feed(id, rssi);
            render();
        });

        flicker.setOnClickListener(v -> {
            flickering = !flickering;
            flicker.setText(flickering ? "Stop flicker" : "Auto-flicker 5 s");
            if (flickering) {
                flickerStep.run();
            } else {
                handler.removeCallbacks(flickerStep);
            }
        });

        // Real mode: one-shot estimate + a 1 Hz live watcher, both fed by the
        // actual mesh peer table (BLE adverts in range). Sim mode and real
        // mode are mutually exclusive.
        realOnce.setOnClickListener(v -> {
            stopFlicker();
            renderLive();
        });
        watchButton.setOnClickListener(v -> {
            if (watchingLive) {
                stopLive();
            } else {
                stopFlicker();
                watchingLive = true;
                watchButton.setText("Stop watching");
                liveStep.run();
            }
        });

        serverButton.setOnClickListener(v -> runServerTest());

        // A sensible starting scene: Hall 1 strongest.
        feed("Hall 1", -55);
        feed("Cafeteria", -72);
        feed("Admin Block", -84);
        render();
    }

    @Override
    protected void onDestroy() {
        stopFlicker();
        stopLive();
        super.onDestroy();
    }

    private void stopFlicker() {
        if (flickering) {
            flickering = false;
            handler.removeCallbacks(flickerStep);
        }
    }

    private void stopLive() {
        watchingLive = false;
        handler.removeCallbacks(liveStep);
        watchButton.setText("Watch live");
    }

    private void feed(String beaconId, int rawRssi) {
        int smoothed = filters.get(beaconId).update(rawRssi);
        Peer beacon = beacons.get(beaconId);
        beacon.setRssi(smoothed);
        beacon.setLastSeenEpochMs(System.currentTimeMillis());
        estimator.feed(new ArrayList<>(beacons.values()));
    }

    /**
     * Server test: POST the current anchors to ai-service /position and
     * show its weighted-centroid answer next to the on-device one. Live
     * mesh venue adverts (hops) are preferred; when the mesh has none the
     * on-screen synthetic beacon table (rssi) is sent instead.
     */
    private void runServerTest() {
        JSONArray anchors = new JSONArray();
        try {
            MeshManager manager = MeshManager.get(getApplicationContext());
            MeshNode node = manager.getMeshNode();
            int live = 0;
            if (node != null) {
                for (VenueDirectory.Entry entry : node.getVenueDirectory().entries()) {
                    JSONObject anchor = new JSONObject();
                    anchor.put("beacon", entry.venue);
                    anchor.put("hops", entry.hops);
                    anchors.put(anchor);
                    live++;
                }
            }
            if (live == 0) {
                for (Peer beacon : beacons.values()) {
                    if (beacon.getRssi() == 0) {
                        continue;
                    }
                    JSONObject anchor = new JSONObject();
                    anchor.put("beacon", beacon.getNodeId());
                    anchor.put("rssi", beacon.getRssi());
                    anchors.put(anchor);
                }
            }
        } catch (JSONException e) {
            serverOutput.setText("Server error: " + e.getMessage());
            return;
        }
        if (anchors.length() == 0) {
            serverOutput.setText("Server: no anchors to send — feed a beacon first");
            return;
        }
        String typed = hostInput.getText().toString().trim();
        if (!typed.isEmpty()) {
            // Persist so the Navigate tab and future opens reuse this URL.
            MeshManager.get(getApplicationContext()).saveServerBaseUrl(typed);
        }
        String baseUrl = AiServiceClient.normalizeBaseUrl(typed);
        serverButton.setEnabled(false);
        serverOutput.setText("Server: querying…");
        AiServiceClient.postPosition(baseUrl, anchors, null,
                result -> runOnUiThread(() -> {
                    serverButton.setEnabled(true);
                    serverOutput.setText("Server: " + describeServer(result));
                }),
                error -> runOnUiThread(() -> {
                    serverButton.setEnabled(true);
                    serverOutput.setText("Server error: " + error
                            + "\nStart the ai-service (uvicorn) or fix the host");
                }));
    }

    /** Same "Hall 1 (≈0.5 m, 2 anchors, 67%)" shape as describeWeightedEstimate. */
    private String describeServer(AiServiceClient.PositionResult result) {
        return String.format(java.util.Locale.US, "%s (≈%.1f m, %d anchors, %d%%%s)",
                result.nodeId, result.distanceMeters, result.anchorsUsed,
                Math.round(result.confidence * 100),
                result.anchorsSkipped > 0 ? ", " + result.anchorsSkipped + " skipped" : "");
    }

    /**
     * Real mode: build the estimate from the live mesh peer table plus mesh
     * venue adverts (phones announcing "I am at <venue>", possibly relayed
     * multi-hop) — both feed the same strongest-anchor estimator.
     */
    private void renderLive() {
        MeshManager manager = MeshManager.get(getApplicationContext());
        MeshNode node = manager.getMeshNode();
        List<Peer> peers = node == null ? new ArrayList<>() : node.getPeers();

        if (estimator == null || graph == null) {
            return;
        }
        PositionEstimator liveEstimator = new PositionEstimator(graph);
        liveEstimator.feed(peers);

        StringBuilder venueLines = new StringBuilder();
        java.util.Set<String> liveVenues = new java.util.HashSet<>();
        if (node != null) {
            for (VenueDirectory.Entry entry : node.getVenueDirectory().entries()) {
                liveVenues.add(entry.venue);
                liveEstimator.observeVenueAdvert(entry.venue, entry.hops);
                venueLines.append(String.format("%-16s %8d %10s%n",
                        entry.venue, entry.hops, entry.deviceId));
            }
        }
        liveEstimator.retainVenueAnchors(liveVenues);

        String described = liveEstimator.describeEstimate();
        String estimate = liveEstimator.estimate();
        lastLiveEstimate = estimate;

        StringBuilder table = new StringBuilder();
        table.append("LIVE BLE PEERS (from the running mesh stack)\n");
        table.append(String.format("%-16s %8s %10s%n", "device", "rssi", "beacon"));
        int beaconCount = 0;
        for (Peer peer : peers) {
            if (peer.isBeacon()) {
                beaconCount++;
            }
            table.append(String.format("%-16s %8d %10s%n",
                    peer.getDisplayName(), peer.getRssi(),
                    peer.isBeacon() ? "yes" : "phone"));
        }
        if (peers.isEmpty()) {
            table.append("\n(none in range — run the app on a second phone,")
                    .append(" or check Bluetooth + permissions)");
        }
        if (venueLines.length() > 0) {
            table.append("\nMESH VENUE ADVERTS (positioning anchors)\n");
            table.append(String.format("%-16s %8s %10s%n", "venue", "hops", "from"));
            table.append(venueLines);
        }
        table.append("\nEstimate: ").append(described == null
                ? "no anchors in range" : described);
        positionOutput.setText(estimate == null
                ? "Real test: no estimate" : "Real estimate: " + described);
        beaconTable.setText(table.toString());
    }

    private void render() {
        String estimated = estimator.estimate();
        positionOutput.setText(estimated == null
                ? "No estimate (no fresh beacon readings)"
                : "Estimated position: " + estimated);

        StringBuilder table = new StringBuilder();
        table.append(String.format("%-14s %6s %6s%n", "beacon", "raw", "smooth"));
        for (String id : beacons.keySet()) {
            table.append(String.format("%-14s %6d %6d%n",
                    id, filters.get(id).get(), beacons.get(id).getRssi()));
        }
        table.append("\nRaw → filter.update() → Peer.rssi → estimator.feed()\n");
        table.append("estimate() = strongest smoothed beacon (ties → first)");
        beaconTable.setText(table.toString());
    }
}
