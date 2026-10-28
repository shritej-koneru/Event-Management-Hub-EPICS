package com.epics.bitchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.epics.bitchat.R;
import com.epics.bitchat.ui.test.BlePairTestActivity;
import com.epics.bitchat.ui.test.HopTestActivity;
import com.epics.bitchat.ui.test.PositioningTestActivity;
import com.epics.bitchat.ui.test.RoutingTestActivity;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * TEST HUB — one row per testable section. Each row opens a dedicated
 * activity with a "Simulated test" button (runs entirely on this device, no
 * second phone or server needed) and, where a real counterpart exists, a
 * "Real test" button (live BLE phones / live ai-service). Rows are labelled
 * with how many devices the real test needs, so the tests can be run one
 * after the other in order.
 */
public class TestHubActivity extends AppCompatActivity {

    private static final class Entry {
        final String title;
        final String subtitle;
        final String deviceRequirement;
        final boolean hasRealTest;
        final Class<?> activity;

        Entry(String title, String subtitle, String deviceRequirement,
              boolean hasRealTest, Class<?> activity) {
            this.title = title;
            this.subtitle = subtitle;
            this.deviceRequirement = deviceRequirement;
            this.hasRealTest = hasRealTest;
            this.activity = activity;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_hub);

        if (entries.isEmpty()) {
            // 1–2: engine tests (the AI screens were cut — offline-first pivot).
            entries.add(new Entry("1 · Routing engine (Dijkstra / A*)",
                    "Map + solver. Sim: on-device route over the campus graph. "
                            + "Real: live /route from the ai-service.",
                    "1 device (sim) · + server (real)", true, RoutingTestActivity.class));
            entries.add(new Entry("2 · Positioning (beacons → node)",
                    "Strongest-anchor estimator. Sim: synthetic beacons + flicker. "
                            + "Real: live BLE peers and mesh venue adverts.",
                    "1 device (sim) · 2+ phones (real)", true, PositioningTestActivity.class));
            // 3–4: mesh tests. The 2- and 3-phone cases have simulated
            // equivalents so everything can be verified on one device first.
            entries.add(new Entry("3 · BLE pair — direct message",
                    "Send → deliver → ACK chain. Sim: in-process mesh on this "
                            + "device. Real: 2 phones, direct CHAT + delivery status.",
                    "1 device (sim) · 2 phones (real)", true, BlePairTestActivity.class));
            entries.add(new Entry("4 · Hop / relay — users as nodes",
                    "Flood + relay stats + mesh venue navigation. Sim: flood the "
                            + "local/sim mesh. Real: A and C out of range, B relays.",
                    "1 device (sim) · 3 phones (real)", true, HopTestActivity.class));
        }

        RecyclerView list = findViewById(R.id.test_hub_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new RecyclerView.Adapter<Holder>() {
            @NonNull
            @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_test_entry, parent, false);
                return new Holder(row);
            }

            @Override
            public void onBindViewHolder(@NonNull Holder holder, int position) {
                Entry entry = entries.get(position);
                holder.title.setText(entry.title);
                holder.subtitle.setText(entry.subtitle);
                holder.devices.setText(entry.deviceRequirement);
                holder.simBadge.setVisibility(View.VISIBLE);
                holder.realBadge.setVisibility(entry.hasRealTest ? View.VISIBLE : View.GONE);
                holder.itemView.setOnClickListener(v ->
                        startActivity(new Intent(TestHubActivity.this, entry.activity)));
            }

            @Override
            public int getItemCount() {
                return entries.size();
            }
        });

        MaterialButton back = findViewById(R.id.test_hub_back);
        back.setOnClickListener(v -> finish());
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView devices;
        final TextView simBadge;
        final TextView realBadge;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.test_title);
            subtitle = itemView.findViewById(R.id.test_subtitle);
            devices = itemView.findViewById(R.id.test_devices);
            simBadge = itemView.findViewById(R.id.test_badge_sim);
            realBadge = itemView.findViewById(R.id.test_badge_real);
        }
    }
}
