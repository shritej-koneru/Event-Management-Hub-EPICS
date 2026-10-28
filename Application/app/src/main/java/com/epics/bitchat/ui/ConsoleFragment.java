package com.epics.bitchat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.epics.bitchat.R;
import com.epics.bitchat.mesh.MeshKind;
import com.epics.bitchat.mesh.MeshLogEntry;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Live traffic console: every TX/RX/relay/drop in the mesh, a floating Send
 * button and quick-send chips.
 */
public class ConsoleFragment extends Fragment {

    private MeshViewModel viewModel;
    private LogAdapter adapter;
    private TextView statusText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_console, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MeshViewModel.class);
        statusText = view.findViewById(R.id.console_status);

        adapter = new LogAdapter(new ArrayList<>());
        RecyclerView recycler = view.findViewById(R.id.console_list);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        viewModel.getLogs().observe(getViewLifecycleOwner(), logs -> adapter.setEntries(logs));
        viewModel.getPosition().observe(getViewLifecycleOwner(), pos -> {
            if (pos == null || pos.isEmpty()) {
                statusText.setText("Position: unknown (no beacon in range)");
            } else {
                statusText.setText("Position: near " + viewModel.displayNameFor(pos));
            }
        });
        viewModel.getEventMessage().observe(getViewLifecycleOwner(), msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });

        FloatingActionButton fab = view.findViewById(R.id.fab_send);
        fab.setOnClickListener(v -> showSendDialog());

        ChipGroup chips = view.findViewById(R.id.quick_chips);
        for (String preset : new String[]{"hi", "meet at Hall 2", "ping"}) {
            Chip chip = new Chip(requireContext());
            chip.setText(preset);
            chip.setClickable(true);
            chip.setOnClickListener(v -> quickSend(preset));
            chips.addView(chip);
        }
    }

    private void quickSend(String payload) {
        List<String> targets = viewModel.getReachableIds();
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "No reachable nodes yet", Toast.LENGTH_SHORT).show();
            return;
        }
        // Honor the destination last picked on the Send tab when it is still
        // reachable — the chips used to always send to targets.get(0) (#10).
        String last = viewModel.getLastDestinationId().getValue();
        String dst = last != null && targets.contains(last) ? last : targets.get(0);
        viewModel.send(dst, MeshKind.CHAT, payload);
        Toast.makeText(requireContext(), "Sent to " + viewModel.displayNameFor(dst), Toast.LENGTH_SHORT).show();
    }

    private void showSendDialog() {
        List<String> targets = viewModel.getReachableIds();
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "No reachable nodes yet", Toast.LENGTH_SHORT).show();
            return;
        }
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_send, null);
        Spinner spinner = dialogView.findViewById(R.id.send_dest);
        EditText message = dialogView.findViewById(R.id.send_message);

        List<String> labels = new ArrayList<>();
        for (String id : targets) {
            labels.add(viewModel.displayNameFor(id) + " (" + id + ")");
        }
        spinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(0);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Send message")
                .setView(dialogView)
                .setPositiveButton("Send", (dialog, which) -> {
                    String dst = targets.get(spinner.getSelectedItemPosition());
                    String text = message.getText().toString().trim();
                    if (text.isEmpty()) {
                        Toast.makeText(requireContext(), "Message is empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (viewModel.send(dst, MeshKind.CHAT, text)) {
                        Toast.makeText(requireContext(), "Sent to " + viewModel.displayNameFor(dst),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
