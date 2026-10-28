package com.epics.bitchat.ui.test;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.epics.bitchat.R;
import com.epics.bitchat.ai.AiServiceClient;
import com.epics.bitchat.nav.AStar;
import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Dijkstra;
import com.epics.bitchat.nav.Graph;
import com.epics.bitchat.nav.GraphNode;
import com.epics.bitchat.nav.Route;
import com.epics.bitchat.nav.RouteBuilder;
import com.epics.bitchat.ui.GraphView;
import com.epics.bitchat.ui.StepAdapter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * TEST SCREEN — routing engine. "Simulated test" runs the on-device
 * Dijkstra/A* over the local graph; "Real test" calls the live FastAPI
 * /route endpoint and renders the server's result on the same map, so the
 * two engines can be compared directly.
 */
public class RoutingTestActivity extends AppCompatActivity {

    private Graph graph;
    private GraphView graphView;
    private Spinner fromSpinner;
    private Spinner toSpinner;
    private Spinner solverSpinner;
    private MaterialSwitch accessibleSwitch;
    private EditText hostInput;
    private TextView summary;
    private StepAdapter stepAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_routing_test);

        graph = DemoGraphFactory.buildCampusGraph();
        graphView = findViewById(R.id.test_graph_view);
        fromSpinner = findViewById(R.id.test_from_spinner);
        toSpinner = findViewById(R.id.test_to_spinner);
        solverSpinner = findViewById(R.id.test_solver_spinner);
        accessibleSwitch = findViewById(R.id.test_accessible_switch);
        hostInput = findViewById(R.id.test_host_input);
        summary = findViewById(R.id.test_route_summary);
        MaterialButton simulate = findViewById(R.id.test_solve_button);
        MaterialButton real = findViewById(R.id.test_real_button);
        RecyclerView steps = findViewById(R.id.test_step_list);

        graphView.setGraph(graph);

        List<String> venues = new ArrayList<>();
        for (GraphNode node : graph.getNodes()) {
            if (!DemoGraphFactory.isTransitionNode(node.getId())) {
                venues.add(node.getName());
            }
        }
        ArrayAdapter<String> venueAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, venues);
        fromSpinner.setAdapter(venueAdapter);
        toSpinner.setAdapter(venueAdapter);
        toSpinner.setSelection(Math.min(7, venues.size() - 1));

        ArrayAdapter<String> solverAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                List.of("Dijkstra", "A*"));
        solverSpinner.setAdapter(solverAdapter);

        stepAdapter = new StepAdapter(new ArrayList<>());
        steps.setLayoutManager(new LinearLayoutManager(this));
        steps.setAdapter(stepAdapter);

        simulate.setOnClickListener(v -> solveSimulated());
        real.setOnClickListener(v -> solveReal());
    }

    private void solveSimulated() {
        String from = (String) fromSpinner.getSelectedItem();
        String to = (String) toSpinner.getSelectedItem();
        if (from == null || to == null || from.equals(to)) {
            Toast.makeText(this, "Pick two different venues", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean accessible = accessibleSwitch.isChecked();
        boolean useAStar = solverSpinner.getSelectedItemPosition() == 1;

        long startedNs = System.nanoTime();
        List<GraphNode> path = useAStar
                ? AStar.findShortestPath(graph, from, to, accessible)
                : Dijkstra.findShortestPath(graph, from, to, accessible);
        double elapsedMs = (System.nanoTime() - startedNs) / 1_000_000.0;

        if (path.isEmpty()) {
            summary.setText("No " + (accessible ? "step-free " : "") + "route from "
                    + from + " to " + to);
            graphView.setRoute(null);
            stepAdapter.setSteps(new ArrayList<>());
            return;
        }

        Route route = RouteBuilder.build(graph, path, accessible);
        stepAdapter.setSteps(new ArrayList<>(route.getSteps()));
        graphView.setRoute(path);
        graphView.setCurrentNode(from);
        graphView.setCurrentStepIndex(0);

        summary.setText("(on-device) " + (useAStar ? "A*" : "Dijkstra")
                + (accessible ? " · accessible" : " · standard")
                + String.format(" · %.1f ms · %d m · %.0f s (%d steps)",
                elapsedMs, (int) route.getTotalMeters(),
                route.getTotalSeconds(), route.getSteps().size()));
    }

    private void solveReal() {
        String from = (String) fromSpinner.getSelectedItem();
        String to = (String) toSpinner.getSelectedItem();
        if (from == null || to == null || from.equals(to)) {
            Toast.makeText(this, "Pick two different venues", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean accessible = accessibleSwitch.isChecked();
        boolean useAStar = solverSpinner.getSelectedItemPosition() == 1;
        String baseUrl = AiServiceClient.normalizeBaseUrl(hostInput.getText().toString());

        summary.setText("Calling " + baseUrl + " …");
        // Callbacks arrive on a worker thread — marshal to the UI thread
        // (setText/Toast would crash the app otherwise).
        AiServiceClient.getRoute(baseUrl, from, to, accessible,
                useAStar ? "astar" : "dijkstra",
                result -> runOnUiThread(() -> {
                    summary.setText("(server) " + result.algorithm
                            + (result.accessible ? " · accessible" : " · standard")
                            + String.format(" · %d m · %.0f s · %s",
                            (int) result.totalMeters, result.totalSeconds, result.path));
                    // Render the server's path on the local map for comparison.
                    renderServerPath(result.path);
                }),
                error -> runOnUiThread(() -> {
                    summary.setText("Real test failed: " + error);
                    Toast.makeText(this,
                            "Start the ai-service (uvicorn) or fix the host", Toast.LENGTH_LONG).show();
                }));
    }

    private void renderServerPath(String pathString) {
        // pathString looks like "[Main Gate, stairwell_1, Hall 2]"
        String inner = pathString.replace("[", "").replace("]", "");
        List<GraphNode> nodes = new ArrayList<>();
        if (!inner.trim().isEmpty()) {
            for (String id : inner.split(",")) {
                GraphNode node = graph.getNode(id.trim());
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        if (nodes.isEmpty()) {
            summary.append("\n(unable to render server path on map)");
        }
        graphView.setRoute(nodes);
        graphView.setCurrentNode(nodes.isEmpty() ? null : nodes.get(0).getId());
        graphView.setCurrentStepIndex(-1);
    }
}
