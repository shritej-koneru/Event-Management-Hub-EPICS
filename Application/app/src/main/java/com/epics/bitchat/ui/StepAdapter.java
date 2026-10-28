package com.epics.bitchat.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.epics.bitchat.R;
import com.epics.bitchat.nav.RouteStep;

import java.util.List;

/** Turn-by-turn navigation steps with a highlighted current step. */
public class StepAdapter extends RecyclerView.Adapter<StepAdapter.ViewHolder> {

    private final List<RouteStep> steps;
    private int currentStep = -1;

    public StepAdapter(List<RouteStep> steps) {
        this.steps = steps;
    }

    public void setSteps(List<RouteStep> steps) {
        this.steps.clear();
        this.steps.addAll(steps);
        this.currentStep = -1;
        notifyDataSetChanged();
    }

    public void setCurrentStep(int index) {
        if (index == currentStep) {
            return;
        }
        currentStep = index;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_step, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RouteStep step = steps.get(position);
        holder.text.setText((position + 1) + ". " + step.getText());
        holder.distance.setText((int) step.getDistanceMeters() + " m");

        boolean active = position == currentStep;
        holder.text.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                active ? R.color.tx_color : R.color.relay_color));
        holder.distance.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                active ? R.color.tx_color : R.color.relay_color));
    }

    @Override
    public int getItemCount() {
        return steps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final TextView distance;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.step_text);
            distance = itemView.findViewById(R.id.step_distance);
        }
    }
}
