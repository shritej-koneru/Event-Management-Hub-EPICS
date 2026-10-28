package com.epics.bitchat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.epics.bitchat.R;

import java.util.ArrayList;

/** Discovered nodes and beacons with signal strength. */
public class PeersFragment extends Fragment {

    private MeshViewModel viewModel;
    private PeerAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_peers, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MeshViewModel.class);

        adapter = new PeerAdapter(new ArrayList<>());
        RecyclerView recycler = view.findViewById(R.id.peers_list);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        viewModel.getPeers().observe(getViewLifecycleOwner(), peers -> {
            adapter.setPeers(new ArrayList<>(peers));
            view.findViewById(R.id.peers_empty).setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }
}
