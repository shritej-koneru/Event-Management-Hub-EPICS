package com.epics.bitchat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.epics.bitchat.R;
import com.epics.bitchat.mesh.MeshKind;
import com.epics.bitchat.mesh.PacketCodec;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** Compose and send a mesh message to a selected node. */
public class SendFragment extends Fragment {

    private MeshViewModel viewModel;
    private Spinner destination;
    private EditText messageInput;
    private TextView statusText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_send, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MeshViewModel.class);

        destination = view.findViewById(R.id.frag_send_dest);
        messageInput = view.findViewById(R.id.frag_send_message);
        statusText = view.findViewById(R.id.frag_send_status);
        MaterialButton sendButton = view.findViewById(R.id.send_button);

        refreshDestinations();

        sendButton.setOnClickListener(v -> send());

        // Long-announcement UX: the keyboard's Send/Enter action sends
        // directly — the mesh splits long texts into packets automatically.
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });

        viewModel.getDeliveryStatus().observe(getViewLifecycleOwner(), statusText::setText);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDestinations();
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
        destination.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, labels));
        if (previous >= 0 && previous < labels.size()) {
            destination.setSelection(previous);
        }
    }

    private void send() {
        List<MeshViewModel.Destination> targets = viewModel.getKnownDestinations();
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "No devices known yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Type a message first", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = destination.getSelectedItemPosition();
        if (index < 0 || index >= targets.size()) {
            Toast.makeText(requireContext(), "Pick a destination", Toast.LENGTH_SHORT).show();
            return;
        }
        MeshViewModel.Destination dst = targets.get(index);
        viewModel.setLastDestinationId(dst.nodeId);
        if (viewModel.send(dst.nodeId, MeshKind.CHAT, text)) {
            messageInput.setText("");
            statusText.setText("Waiting for delivery to " + dst.label + "...");
        } else {
            // The only remaining rejection: beyond the fragmentation cap —
            // never fail silently again (the old 14-char reject was invisible).
            statusText.setText("Too long — the mesh carries at most "
                    + PacketCodec.MAX_FRAGMENTED_TEXT + " characters");
        }
    }
}
