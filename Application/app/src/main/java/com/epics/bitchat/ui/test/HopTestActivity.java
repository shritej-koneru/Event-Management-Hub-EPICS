package com.epics.bitchat.ui.test;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.epics.bitchat.R;
import com.epics.bitchat.mesh.MeshManager;
import com.epics.bitchat.mesh.MeshNode;
import com.epics.bitchat.mesh.RelayStats;
import com.epics.bitchat.mesh.SimulatedTransport;
import com.epics.bitchat.mesh.VenueDirectory;
import com.epics.bitchat.ui.MeshViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import androidx.lifecycle.ViewModelProvider;

import java.util.List;

/**
 * TEST SCREEN — "users as nodes": hop/relay behavior plus mesh navigation.
 * Shows live RelayStats (relayed/delivered/drops/max hops), the relay toggle,
 * venue announcement, and the venue directory heard over the mesh
 * (multi-hop). Simulated test floods the local/sim mesh; Real test is the
 * same flood over live BLE with 2+ phones (the middle phone must show
 * relays — proof that users relay data).
 */
public class HopTestActivity extends AppCompatActivity {

    private MeshViewModel viewModel;
    private TextView stats;
    private TextView directory;
    private TextView output;
    private MaterialSwitch relaySwitch;
    private LinearLayout linkToggles;

    private int baselineRelayed = -1;
    private int baselineDelivered = -1;
    private int baselineMaxHops = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hop_test);

        viewModel = new ViewModelProvider(this).get(MeshViewModel.class);
        stats = findViewById(R.id.hop_stats);
        directory = findViewById(R.id.hop_directory);
        output = findViewById(R.id.hop_output);
        relaySwitch = findViewById(R.id.hop_relay_switch);
        linkToggles = findViewById(R.id.hop_link_toggles);
        MaterialButton sim = findViewById(R.id.hop_sim_button);
        MaterialButton real = findViewById(R.id.hop_real_button);
        EditText venueInput = findViewById(R.id.hop_venue_input);
        MaterialButton announce = findViewById(R.id.hop_announce_button);

        relaySwitch.setChecked(viewModel.isRelayEnabled());
        relaySwitch.setOnCheckedChangeListener((v, checked) -> {
            viewModel.setRelayEnabled(checked);
            Toast.makeText(this, checked
                    ? "Relay ON — this phone forwards others' messages"
                    : "Relay OFF — floods stop at this phone",
                    Toast.LENGTH_SHORT).show();
        });

        announce.setOnClickListener(v -> {
            String venue = venueInput.getText().toString().trim();
            viewModel.setAdvertisedVenue(venue);
            output.setText(venue.isEmpty()
                    ? "Venue announcement cleared."
                    : "Announcing \"" + venue + "\" — floods with TTL 8; nearby phones "
                    + "relay it and their Navigation tab will list it with its hop count.");
            render();
        });

        sim.setOnClickListener(v -> runFlood("SIMULATED flood"));
        real.setOnClickListener(v -> runFlood("REAL flood (2+ phones)"));

        buildLinkToggles();
        handler.postDelayed(refresher, 500);
    }

    /**
     * TEST 6 knob: per-peer "simulate link loss". With the simulator, every
     * sim node gets an in-range toggle — cut the local↔X link to stage "X
     * walked out of range", or cut X↔Y to force a true multi-hop path. With
     * real BLE the block hides (use the per-peer RSSI in the stats instead).
     */
    private void buildLinkToggles() {
        MeshManager manager = viewModel.getMeshManager();
        MeshNode node = manager.getMeshNode();
        if (node == null || !(node.getTransport() instanceof SimulatedTransport)) {
            linkToggles.setVisibility(View.GONE);
            return;
        }
        SimulatedTransport sim = (SimulatedTransport) node.getTransport();
        linkToggles.removeAllViews();
        for (String id : sim.getSimNodeIds()) {
            MaterialSwitch toggle = new MaterialSwitch(this);
            toggle.setText("in range of " + manager.displayNameFor(id));
            toggle.setChecked(!sim.isLinkSevered(node.getNodeId(), id));
            toggle.setOnCheckedChangeListener((v, checked) -> {
                sim.setLinkSevered(node.getNodeId(), id, !checked);
                Toast.makeText(this, checked
                        ? "Link restored — " + manager.displayNameFor(id) + " is in range again"
                        : "Link cut — " + manager.displayNameFor(id) + " is staged out of range",
                        Toast.LENGTH_SHORT).show();
            });
            linkToggles.addView(toggle);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresher);
        super.onDestroy();
    }

    private void runFlood(String label) {
        MeshManager manager = viewModel.getMeshManager();
        MeshNode node = manager.getMeshNode();
        if (node == null || !node.isRunning()) {
            output.setText("Mesh not running — open the app once more or check Settings.");
            return;
        }
        RelayStats s = node.getRelayStats();
        baselineRelayed = s.getRelayed();
        baselineDelivered = s.getDelivered();
        baselineMaxHops = s.getMaxHopsSeen();

        boolean ok = node.broadcast("hop" + (System.currentTimeMillis() / 1000 % 100),
                com.epics.bitchat.mesh.Router.DEFAULT_TTL);
        output.setText(label + (ok
                ? " sent. Watching stats for 6 s…\n"
                : " failed to send.\n"));
        if (ok) {
            handler.postDelayed(this::render, 1000);
            handler.postDelayed(this::render, 3000);
            handler.postDelayed(() -> {
                render();
                RelayStats statsNow = node.getRelayStats();
                int newRelays = statsNow.getRelayed() - baselineRelayed;
                int newDeliveries = statsNow.getDelivered() - baselineDelivered;
                output.append("\nAfter 6 s: +" + newRelays + " relayed by this phone, "
                        + "+" + newDeliveries + " broadcast deliveries heard, max hops "
                        + statsNow.getMaxHopsSeen()
                        + (newRelays > 0
                        ? "\n→ this user acted as a node for other users' data."
                        : "\n→ no relay traffic yet (need 2+ phones in range)."));
            }, 6000);
        }
    }

    private void render() {
        MeshManager manager = viewModel.getMeshManager();
        MeshNode node = manager.getMeshNode();
        if (node == null) {
            stats.setText("Mesh not running.");
            return;
        }
        RelayStats s = node.getRelayStats();
        StringBuilder sb = new StringBuilder("HOP STATS (this phone)\n").append(s.summary());
        // Per-peer lines with live RSSI (#13): with real phones, "relayed 0"
        // is only meaningful next to the signal it arrived (or didn't) with.
        boolean anyPeer = false;
        for (com.epics.bitchat.mesh.Peer peer : node.getPeers()) {
            anyPeer = true;
            int hops = s.getMaxHops(peer.getNodeId());
            sb.append("\n").append(peer.getDisplayName())
                    .append(" (").append(peer.getNodeId()).append(") ")
                    .append(peer.getRssi()).append(" dBm · heard at ≤ ")
                    .append(hops).append(" hops");
        }
        if (!anyPeer) {
            sb.append("\n(no peers in range)");
        }
        stats.setText(sb.toString());

        List<VenueDirectory.Entry> venues = node.getVenueDirectory().entries();
        StringBuilder dir = new StringBuilder("VENUE DIRECTORY (from mesh adverts)\n");
        if (venues.isEmpty()) {
            dir.append("(none — announce a venue on this or another phone)");
        } else {
            // Staleness (#15): directory entries expire after 2 min, so show
            // how old each heard-at is, not just the hop count.
            for (VenueDirectory.Entry entry : venues) {
                long ageMin = Math.max(0,
                        (System.currentTimeMillis() - entry.heardAtEpochMs) / 60_000L);
                dir.append(entry.venue).append(" via ").append(entry.deviceId)
                        .append(" · ").append(entry.hops).append(" hops · ")
                        .append(ageMin == 0 ? "just now" : ageMin + " min ago")
                        .append("\n");
            }
        }
        String mine = node.getAdvertisedVenue();
        dir.append("announcing: ").append(mine == null || mine.isEmpty() ? "—" : mine);
        directory.setText(dir.toString());
    }
}
