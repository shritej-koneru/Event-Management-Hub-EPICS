package com.epics.bitchat.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.epics.bitchat.R;
import com.epics.bitchat.mesh.Peer;

import java.util.List;

/** Lists discovered mesh nodes and beacons with signal and freshness. */
public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.ViewHolder> {

    private final List<Peer> peers;

    public PeerAdapter(List<Peer> peers) {
        this.peers = peers;
    }

    public void setPeers(List<Peer> peers) {
        this.peers.clear();
        this.peers.addAll(peers);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Peer peer = peers.get(position);
        holder.name.setText(peer.getDisplayName());
        holder.detail.setText((peer.isBeacon() ? "BEACON · " : "NODE · ")
                + peer.getNodeId() + "\n" + peer.getDeviceAddress());
        holder.rssi.setText(peer.getRssi() + " dBm");
        holder.name.setCompoundDrawablesRelativeWithIntrinsicBounds(
                peer.isBeacon() ? R.drawable.ic_beacon : 0, 0, 0, 0);
    }

    @Override
    public int getItemCount() {
        return peers.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView detail;
        final TextView rssi;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.peer_name);
            detail = itemView.findViewById(R.id.peer_detail);
            rssi = itemView.findViewById(R.id.peer_rssi);
        }
    }
}
