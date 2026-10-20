package com.epics.bitchat.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.epics.bitchat.R;
import com.epics.bitchat.mesh.Direction;
import com.epics.bitchat.mesh.MeshLogEntry;
import com.epics.bitchat.mesh.MeshStatus;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Renders one row of the live traffic console. Colour coding:
 * TX blue, RX green, dropped/relay states grey.
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private List<MeshLogEntry> entries;

    public LogAdapter(List<MeshLogEntry> entries) {
        this.entries = entries;
    }

    public void setEntries(List<MeshLogEntry> entries) {
        this.entries = entries;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MeshLogEntry entry = entries.get(position);
        holder.time.setText(timeFormat.format(new Date(entry.getTimestamp())));
        holder.body.setText(format(entry));

        int color;
        if (entry.getStatus() == MeshStatus.DELIVERED || entry.getStatus() == MeshStatus.IN_FLIGHT) {
            color = entry.getDirection() == Direction.TX
                    ? ContextCompat.getColor(holder.itemView.getContext(), R.color.tx_color)
                    : ContextCompat.getColor(holder.itemView.getContext(), R.color.rx_color);
        } else {
            color = ContextCompat.getColor(holder.itemView.getContext(), R.color.relay_color);
        }
        holder.body.setTextColor(color);
    }

    private String format(MeshLogEntry entry) {
        String id = entry.getMsgId().length() > 8 ? entry.getMsgId().substring(0, 8) : entry.getMsgId();
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(id).append(' ');
        sb.append('"').append(entry.getPayload()).append('"').append(' ');
        sb.append(entry.getFrom()).append(" -> ").append(entry.getTo());
        sb.append("  ").append(entry.getHops()).append(" hop").append(entry.getHops() == 1 ? "" : "s");
        if (entry.getRssi() < 0) {
            sb.append("  ").append(entry.getRssi()).append(" dBm");
        }
        sb.append("  [").append(entry.getStatus()).append(']');
        return sb.toString();
    }

    @Override
    public int getItemCount() {
        return entries == null ? 0 : entries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView body;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.log_time);
            body = itemView.findViewById(R.id.log_body);
        }
    }
}
