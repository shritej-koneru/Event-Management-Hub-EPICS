package com.epics.bitchat.ui;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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
import com.epics.bitchat.nav.AStar;
import com.epics.bitchat.nav.DemoGraphFactory;
import com.epics.bitchat.nav.Dijkstra;
import com.epics.bitchat.nav.Graph;
import com.epics.bitchat.nav.GraphNode;
import com.epics.bitchat.nav.Route;
import com.epics.bitchat.nav.RouteBuilder;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigation: pick a start (defaults to the beacon-estimated position) and a
 * destination, then see the route on the map, the step list and a live
 * compass arrow that always points at the next waypoint.
 */
public class NavigationFragment extends Fragment implements SensorEventListener {

    private MeshViewModel viewModel;
    private GraphView graphView;
    private CompassView compassView;
    private Spinner fromSpinner;
    private Spinner toSpinner;
    private TextView compassLabel;
    private TextView meshVenues;
    private TextView serverPosition;
    private StepAdapter stepAdapter;

    private Route route;
    private int currentStep = -1;

    private SensorManager sensorManager;
    private float[] rotation = new float[9];
    private float[] orientation = new float[3];
    private float azimuthDeg = 0f;
    private boolean useRealCompass = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_navigation, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MeshViewModel.class);

        graphView = view.findViewById(R.id.graph_view);
        compassView = view.findViewById(R.id.compass_view);
        compassLabel = view.findViewById(R.id.compass_label);
        meshVenues = view.findViewById(R.id.mesh_venues);
        serverPosition = view.findViewById(R.id.server_position);
        fromSpinner = view.findViewById(R.id.from_spinner);
        toSpinner = view.findViewById(R.id.to_spinner);
        MaterialButton navigate = view.findViewById(R.id.navigate_button);
        MaterialButton nextStep = view.findViewById(R.id.next_step_button);
        MaterialButton clearTrail = view.findViewById(R.id.clear_trail_button);

        stepAdapter = new StepAdapter(new ArrayList<>());
        RecyclerView steps = view.findViewById(R.id.step_list);
        steps.setLayoutManager(new LinearLayoutManager(requireContext()));
        steps.setAdapter(stepAdapter);

        graphView.setGraph(viewModel.getGraph());
        populateSpinners();

        navigate.setOnClickListener(v -> computeRoute());
        nextStep.setOnClickListener(v -> advanceStep());
        clearTrail.setOnClickListener(v -> viewModel.clearServerTrail());

        viewModel.getPosition().observe(getViewLifecycleOwner(), pos -> {
            if (pos != null && !pos.isEmpty()) {
                graphView.setCurrentNode(pos);
                selectSpinnerValue(fromSpinner, pos);
            } else {
                // All anchors expired: hide the position marker.
                graphView.setCurrentNode(null);
            }
        });

        // Color-codes the position marker by anchor provenance: measured
        // beacon (green) vs venue advert relayed over the mesh (blue).
        viewModel.getPositionSource().observe(getViewLifecycleOwner(), src ->
                graphView.setPositionSource("mesh".equals(src)));

        // Server-side weighted position (POST /position) — same anchors as
        // the on-device estimator, computed by the ai-service twin. Hidden
        // unless a URL is configured in Settings.
        viewModel.getServerPosition().observe(getViewLifecycleOwner(), line ->
                serverPosition.setText(line == null ? "" : line));

        // Walked path: every 10 s server fix drawn as a dashed amber trail
        // on the map, so a demo walk leaves a visible breadcrumb line.
        viewModel.getServerTrail().observe(getViewLifecycleOwner(),
                trail -> graphView.setServerTrail(trail));

        // Mesh navigation: venues announced by other phones (possibly relayed
        // multi-hop through users acting as nodes).
        viewModel.getVenueDirectory().observe(getViewLifecycleOwner(), entries -> {
            if (entries == null || entries.isEmpty()) {
                meshVenues.setText("No venues heard over the mesh yet — Settings ▸ Announce my venue");
            } else {
                // Staleness (#15): entries expire after 2 min, so say how old
                // each one is — "2 min ago" honestly reads as last-heard.
                StringBuilder sb = new StringBuilder("Mesh venues: ");
                for (int i = 0; i < entries.size() && i < 6; i++) {
                    if (i > 0) {
                        sb.append(" · ");
                    }
                    long ageMin = Math.max(0,
                            (System.currentTimeMillis() - entries.get(i).heardAtEpochMs) / 60_000L);
                    sb.append(entries.get(i).venue)
                            .append(" (").append(entries.get(i).hops).append(" hops, ")
                            .append(ageMin == 0 ? "just now" : ageMin + " min ago").append(")");
                }
                meshVenues.setText(sb.toString());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor rotationSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        useRealCompass = rotationSensor != null;
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
        refreshCompass();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values);
            SensorManager.getOrientation(rotation, orientation);
            azimuthDeg = (float) Math.toDegrees(orientation[0]);
            if (azimuthDeg < 0) {
                azimuthDeg += 360f;
            }
            refreshCompass();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // ignore
    }

    private void populateSpinners() {
        Graph graph = viewModel.getGraph();
        List<String> names = new ArrayList<>();
        for (GraphNode node : graph.getNodes()) {
            if (DemoGraphFactory.isTransitionNode(node.getId())) {
                continue; // stairs/elevator/ramp are solver internals, not destinations
            }
            names.add(node.getName());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, names);
        fromSpinner.setAdapter(adapter);
        toSpinner.setAdapter(adapter);
        toSpinner.setSelection(Math.min(4, names.size() - 1));
    }

    private void selectSpinnerValue(Spinner spinner, String nodeId) {
        Graph graph = viewModel.getGraph();
        GraphNode node = graph.getNode(nodeId);
        if (node == null) {
            return;
        }
        for (int i = 0; i < spinner.getCount(); i++) {
            if (spinner.getItemAtPosition(i).toString().equals(node.getName())) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private void computeRoute() {
        Graph graph = viewModel.getGraph();
        String fromId = nodeIdAt(fromSpinner);
        String toId = nodeIdAt(toSpinner);
        if (fromId == null || toId == null || fromId.equals(toId)) {
            Toast.makeText(requireContext(), "Pick two different venues", Toast.LENGTH_SHORT).show();
            return;
        }
        List<GraphNode> path = AStar.findShortestPath(graph, fromId, toId);
        if (path.isEmpty()) {
            path = Dijkstra.findShortestPath(graph, fromId, toId);
        }
        if (path.isEmpty()) {
            Toast.makeText(requireContext(), "No route found", Toast.LENGTH_SHORT).show();
            return;
        }
        route = RouteBuilder.build(graph, path);
        currentStep = 0;
        stepAdapter.setSteps(new ArrayList<>(route.getSteps()));
        stepAdapter.setCurrentStep(0);
        graphView.setRoute(path);
        graphView.setCurrentNode(fromId);
        graphView.setCurrentStepIndex(0);
        refreshCompass();
        int meshHops = viewModel.getMeshManager().getVenueDirectory().hopsTo(toId);
        String meshNote = meshHops >= 0
                ? " · someone is at " + toId + " (" + meshHops + " hops away)"
                : "";
        Toast.makeText(requireContext(), "Route: " + (int) route.getTotalMeters() + " m, "
                        + (int) Math.ceil(route.getTotalSeconds()) + " s" + meshNote,
                Toast.LENGTH_LONG).show();
    }

    private void advanceStep() {
        if (route == null || route.getSteps().isEmpty()) {
            return;
        }
        if (currentStep >= route.getSteps().size() - 1) {
            Toast.makeText(requireContext(), "You reached your destination!", Toast.LENGTH_SHORT).show();
            return;
        }
        currentStep++;
        stepAdapter.setCurrentStep(currentStep);
        graphView.setCurrentNode(route.getPath().get(currentStep + 1).getId());
        graphView.setCurrentStepIndex(currentStep);
        refreshCompass();
    }

    private void refreshCompass() {
        if (route == null || route.getSteps().isEmpty() || currentStep < 0) {
            compassView.setBearingDeg(0f);
            compassLabel.setText("No active route");
            return;
        }
        float waypointBearing = (float) route.getSteps().get(currentStep).getBearingDeg();
        float relative;
        if (useRealCompass) {
            relative = waypointBearing - azimuthDeg;
        } else {
            relative = 0f; // emulator: no rotation sensor, point straight ahead
        }
        while (relative > 180f) relative -= 360f;
        while (relative < -180f) relative += 360f;
        compassView.setBearingDeg(relative);
        compassLabel.setText("Next: " + route.getSteps().get(currentStep).getText()
                + "\nTurn " + describe(relative));
    }

    private String describe(float relative) {
        if (Math.abs(relative) < 8) {
            return "straight ahead";
        }
        if (relative > 0) {
            return "right " + Math.round(relative) + "\u00B0";
        }
        return "left " + Math.round(-relative) + "\u00B0";
    }

    private String nodeIdAt(Spinner spinner) {
        String name = (String) spinner.getSelectedItem();
        Graph graph = viewModel.getGraph();
        for (GraphNode node : graph.getNodes()) {
            if (node.getName().equals(name)) {
                return node.getId();
            }
        }
        return null;
    }
}
