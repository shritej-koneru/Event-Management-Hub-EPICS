package com.epics.bitchat.ui.test;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.epics.bitchat.R;
import com.epics.bitchat.mesh.MeshKind;
import com.epics.bitchat.mesh.MeshLogEntry;
import com.epics.bitchat.mesh.MeshManager;
import com.epics.bitchat.mesh.MeshNode;
import com.epics.bitchat.ui.MeshViewModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * TEST SCREEN — BLE pair: direct device-to-device messaging between TWO
 * phones (the 2-device case; for the 3-phone A→B→C relay use the Hop test).
 *
 * Simulated test sends a message to a simulated node on the in-process mesh
 * (works on one device/emulator — verifies the send → route → deliver → ACK
 * chain end to end). Real test sends a direct CHAT to a live BLE peer and
 * watches the delivery status: "Delivered to …" proves the peer received
 * it and its ACK came back; a timeout reports the peer as out of range.
 * The transcript shows every CHAT message this phone sends or receives.
 */
public class BlePairTestActivity extends AppCompatActivity {

    private MeshViewModel viewModel;
    private Spinner destination;
    private EditText messageInput;
    private TextView status;
    private TextView transcript;
    private TextView modeBanner;
    private LinearLayout statusRow;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_pair_test);

        viewModel = new ViewModelProvider(this).get(MeshViewModel.class);

        destination = findViewById(R.id.pair_dest_spinner);
        messageInput = findViewById(R.id.pair_message_input);
        status = findViewById(R.id.pair_status);
        transcript = findViewById(R.id.pair_transcript);
        modeBanner = findViewById(R.id.pair_mode_banner);
        statusRow = findViewById(R.id.pair_status_row);
        MaterialButton sim = findViewById(R.id.pair_sim_button);
        MaterialButton real = findViewById(R.id.pair_real_button);
        MaterialButton refresh = findViewById(R.id.pair_refresh_button);
        messageInput.setText("ping");

        refreshDestinations();
        renderModeBanner();
        renderTranscript();

        refresh.setOnClickListener(v -> {
            refreshDestinations();
            renderModeBanner();
        });

        sim.setOnClickListener(v -> sendTest(true));
        real.setOnClickListener(v -> sendTest(false));

        viewModel.getDeliveryStatus().observe(this, s -> {
            statusRow.setVisibility(View.VISIBLE);
            status.setText(s == null || s.isEmpty() ? "No send in flight" : s);
        });
        viewModel.getLogs().observe(this, entries -> renderTranscript());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDestinations();
        renderModeBanner();
        // Re-read the log directly, not just via LiveData: the receiving phone
        // may have gathered CHAT rows while this screen was in the background,
        // and the log listener fires before this observer re-subscribes.
        renderTranscript();
    }

    private void renderModeBanner() {
        MeshManager manager = viewModel.getMeshManager();
        boolean ble = MeshManager.MODE_BLE.equals(manager.getMode());
        MeshNode node = manager.getMeshNode();
        boolean running = node != null && node.isRunning();
        String transport = ble ? "Real BLE advertisements" : "Simulator (in-process mesh)";
        modeBanner.setText("Transport: " + transport
                + (running ? " · running" : " · NOT running")
                + (ble && !manager.hasBlePermissions()
                ? " · BLE permissions missing" : ""));
        modeBanner.setTextColor(getColor(ble
                ? android.R.color.holo_green_dark
                : android.R.color.darker_gray));
    }

    private void refreshDestinations() {
        List<MeshViewModel.Destination> targets = viewModel.getKnownDestinations();
        List<String> labels = new ArrayList<>();
        for (MeshViewModel.Destination d : targets) {
            labels.add(d.label);
        }
        if (labels.isEmpty()) {
            labels.add("(no devices known yet)");
        }
        int previous = destination.getSelectedItemPosition();
        destination.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (previous >= 0 && previous < labels.size()) {
            destination.setSelection(previous);
        }
    }

    private void sendTest(boolean simulated) {
        MeshManager manager = viewModel.getMeshManager();
        MeshNode node = manager.getMeshNode();
        if (node == null || !node.isRunning()) {
            statusRow.setVisibility(View.VISIBLE);
            status.setText("Mesh not running — open the main app first, then retry.");
            return;
        }

        List<MeshViewModel.Destination> targets = viewModel.getKnownDestinations();
        if (targets.isEmpty()) {
            statusRow.setVisibility(View.VISIBLE);
            status.setText(simulated
                    ? "No simulated devices — switch Settings → Simulator, restart mesh, retry."
                    : "No BLE peers yet — open this screen on the second phone and tap Refresh.");
            return;
        }

        int index = destination.getSelectedItemPosition();
        if (index < 0 || index >= targets.size()) {
            Toast.makeText(this, "Pick a destination", Toast.LENGTH_SHORT).show();
            return;
        }
        MeshViewModel.Destination dst = targets.get(index);

        boolean bleMode = MeshManager.MODE_BLE.equals(manager.getMode());
        if (simulated && bleMode) {
            statusRow.setVisibility(View.VISIBLE);
            status.setText("Simulated pair test needs the Simulator transport "
                    + "(Settings → Simulator → Save). The Real test uses live BLE.");
            return;
        }
        if (!simulated && !bleMode) {
            statusRow.setVisibility(View.VISIBLE);
            status.setText("Real pair test needs the BLE transport "
                    + "(Settings → Real BLE advertisements → Save, Bluetooth ON).");
            return;
        }

        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Type a message first", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean ok = viewModel.send(dst.nodeId, MeshKind.CHAT, text);
        if (ok) {
            messageInput.setText("");
            statusRow.setVisibility(View.VISIBLE);
            status.setText((simulated ? "(simulated) " : "(real BLE) ")
                    + "Sent \"" + text + "\" to " + dst.label
                    + " — waiting for delivery ACK…");
        } else {
            status.setText("Send failed (mesh transport unavailable).");
        }
    }

    /** TX/RX transcript of CHAT traffic, newest last. */
    private void renderTranscript() {
        MeshNode node = viewModel.getMeshManager().getMeshNode();
        if (node == null) {
            transcript.setText("(mesh not running)");
            return;
        }
        List<MeshLogEntry> entries = node.getLog().getEntries();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = entries.size() - 1; i >= 0 && shown < 30; i--) {
            MeshLogEntry entry = entries.get(i);
            if (entry.getKind() != MeshKind.CHAT) {
                continue;
            }
            sb.insert(0, String.format("%-3s %-18s %-18s \"%s\"  %s%n",
                    entry.getDirection(),
                    labelFor(entry.getFrom()),
                    labelFor(entry.getTo()),
                    entry.getPayload(),
                    entry.getStatus()));
            shown++;
        }
        if (shown == 0) {
            sb.append("(no CHAT messages yet — send one above; on the second "
                    + "phone, incoming messages appear here as RX DELIVERED)");
        }
        transcript.setText(sb.toString());
    }

    private String labelFor(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return "(broadcast)";
        }
        return viewModel.displayNameFor(nodeId);
    }
}
