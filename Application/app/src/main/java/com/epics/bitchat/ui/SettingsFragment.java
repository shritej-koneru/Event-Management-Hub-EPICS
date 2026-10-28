package com.epics.bitchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.epics.bitchat.R;
import com.epics.bitchat.mesh.MeshManager;
import com.epics.bitchat.mesh.MeshNode;
import com.google.android.material.button.MaterialButton;

/** Configure the mesh (node name, simulator size, loss, transport) and apply. */
public class SettingsFragment extends Fragment {

    private MeshViewModel viewModel;
    private EditText nodeName;
    private EditText nodeCount;
    private EditText loss;
    private RadioGroup transportMode;
    private EditText serverUrl;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MeshViewModel.class);

        nodeName = view.findViewById(R.id.setting_node_name);
        nodeCount = view.findViewById(R.id.setting_node_count);
        loss = view.findViewById(R.id.setting_loss);
        transportMode = view.findViewById(R.id.setting_transport);
        serverUrl = view.findViewById(R.id.setting_server_url);
        MaterialSwitch relay = view.findViewById(R.id.setting_relay);
        MaterialSwitch autoAnnounce = view.findViewById(R.id.setting_auto_announce);
        EditText venueInput = view.findViewById(R.id.setting_venue);
        MaterialButton announce = view.findViewById(R.id.setting_announce);
        MaterialButton save = view.findViewById(R.id.setting_save);
        MaterialButton resetLog = view.findViewById(R.id.setting_reset_log);
        MaterialButton testHub = view.findViewById(R.id.setting_test_hub);

        MeshManager manager = viewModel.getMeshManager();
        nodeName.setText(manager.getNodeName());
        nodeCount.setText(String.valueOf(manager.getNodeCount()));
        loss.setText(String.valueOf((int) Math.round(manager.getLossPct() * 100)));
        transportMode.check(MeshManager.MODE_BLE.equals(manager.getMode())
                ? R.id.setting_transport_ble : R.id.setting_transport_sim);
        serverUrl.setText(manager.getServerBaseUrl());
        relay.setChecked(manager.isRelayEnabled());
        autoAnnounce.setChecked(manager.isAutoAnnounceEnabled());

        // Applied live: no mesh restart needed.
        relay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setRelayEnabled(isChecked);
            Toast.makeText(requireContext(), isChecked
                    ? "Relaying ON — this phone forwards other users' messages"
                    : "Relaying OFF — messages for you still arrive, floods stop here",
                    Toast.LENGTH_SHORT).show();
        });

        autoAnnounce.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setAutoAnnounceEnabled(isChecked);
            Toast.makeText(requireContext(), isChecked
                    ? "Auto-announce ON — your measured venue broadcasts every 15 s"
                    : "Auto-announce OFF — announce manually below if needed",
                    Toast.LENGTH_SHORT).show();
        });

        announce.setOnClickListener(v -> {
            String venue = venueInput.getText().toString().trim();
            viewModel.setAdvertisedVenue(venue);
            Toast.makeText(requireContext(), venue.isEmpty()
                    ? "Stopped announcing a venue"
                    : "Announcing \"" + venue + "\" over the mesh every 15 s",
                    Toast.LENGTH_SHORT).show();
        });

        save.setOnClickListener(v -> saveSettings());
        resetLog.setOnClickListener(v -> {
            MeshNode node = manager.getMeshNode();
            if (node != null) {
                node.getLog().clear();
                Toast.makeText(requireContext(), "Log cleared", Toast.LENGTH_SHORT).show();
            }
        });

        testHub.setOnClickListener(v -> startActivity(
                new Intent(requireContext(), TestHubActivity.class)));
    }

    private void saveSettings() {
        MeshManager manager = viewModel.getMeshManager();
        String name = nodeName.getText().toString().trim();
        if (name.isEmpty()) {
            name = MeshManager.defaultNodeName();
        }

        int count;
        float lossPct;
        try {
            count = Integer.parseInt(nodeCount.getText().toString().trim());
            lossPct = Float.parseFloat(loss.getText().toString().trim()) / 100f;
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid number", Toast.LENGTH_SHORT).show();
            return;
        }
        count = Math.max(2, Math.min(count, 12));
        lossPct = Math.max(0f, Math.min(lossPct, 0.9f));

        String mode = transportMode.getCheckedRadioButtonId() == R.id.setting_transport_ble
                ? MeshManager.MODE_BLE : MeshManager.MODE_SIM;

        manager.saveSettings(name, count, lossPct, mode);
        // Server location URL: independent of the mesh, but saved here so
        // there is one place to configure the ai-service address.
        manager.saveServerBaseUrl(serverUrl.getText().toString());
        manager.restart();
        Toast.makeText(requireContext(), "Mesh restarted with new settings", Toast.LENGTH_SHORT).show();
    }
}
