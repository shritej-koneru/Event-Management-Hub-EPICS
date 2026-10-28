package com.epics.bitchat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.epics.bitchat.R;
import com.epics.bitchat.nav.Graph;
import com.epics.bitchat.nav.GraphEdge;
import com.epics.bitchat.nav.GraphNode;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal indoor map: draws the venue graph, overlays the computed route,
 * marks beacon nodes and animates the current position.
 */
public class GraphView extends View {

    private Graph graph;
    private List<GraphNode> route = new ArrayList<>();
    private String currentNodeId;
    private int currentStepIndex = -1;
    /** True when the position marker should render in mesh-advert blue. */
    private boolean positionFromMesh = false;
    /** Walked path: sampled server /position fixes (raw graph coords). */
    private List<MeshViewModel.TrailPoint> serverTrail = new ArrayList<>();

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beaconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint positionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint positionHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double minX, maxX, minY, maxY;
    private boolean boundsValid;

    public GraphView(Context context) {
        super(context);
        init(context);
    }

    public GraphView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        edgePaint.setColor(ContextCompat.getColor(context, R.color.relay_color));
        edgePaint.setStrokeWidth(dp(1.5f));
        edgePaint.setStyle(Paint.Style.STROKE);

        routePaint.setColor(ContextCompat.getColor(context, R.color.primary));
        routePaint.setStrokeWidth(dp(4f));
        routePaint.setStyle(Paint.Style.STROKE);

        // walked path: dashed amber so it never reads as a computed route
        trailPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        trailPaint.setStrokeWidth(dp(2.5f));
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(5)}, 0));
        trailDotPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        trailDotPaint.setStyle(Paint.Style.FILL);

        nodePaint.setColor(MaterialColors.getColor(context,
                android.R.attr.textColorPrimary, Color.BLACK));
        nodePaint.setStyle(Paint.Style.FILL);

        beaconPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        beaconPaint.setStyle(Paint.Style.FILL);

        positionPaint.setColor(ContextCompat.getColor(context, R.color.rx_color));
        positionPaint.setStyle(Paint.Style.FILL);

        positionHaloPaint.setColor(ContextCompat.getColor(context, R.color.rx_color));
        positionHaloPaint.setAlpha(60);
        positionHaloPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(MaterialColors.getColor(context,
                android.R.attr.textColorPrimary, Color.BLACK));
        textPaint.setTextSize(sp(10));
        textPaint.setStyle(Paint.Style.FILL);
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
        if (graph != null && graph.getNodes().size() > 0) {
            minX = minY = Double.MAX_VALUE;
            maxX = maxY = -Double.MAX_VALUE;
            for (GraphNode node : graph.getNodes()) {
                minX = Math.min(minX, node.getX());
                maxX = Math.max(maxX, node.getX());
                minY = Math.min(minY, node.getY());
                maxY = Math.max(maxY, node.getY());
            }
            boundsValid = true;
        }
        invalidate();
    }

    public void setRoute(List<GraphNode> route) {
        this.route = route == null ? new ArrayList<>() : new ArrayList<>(route);
        invalidate();
    }

    public void setCurrentNode(String nodeId) {
        this.currentNodeId = nodeId;
        invalidate();
    }

    /** Replace the walked-path overlay (immutable snapshot from the ViewModel). */
    public void setServerTrail(List<MeshViewModel.TrailPoint> trail) {
        this.serverTrail = trail == null ? new ArrayList<>() : new ArrayList<>(trail);
        invalidate();
    }

    /**
     * Color-codes the position marker by anchor source: measured beacon
     * (green) vs venue advert heard over the mesh (blue).
     */
    public void setPositionSource(boolean fromMesh) {
        this.positionFromMesh = fromMesh;
        invalidate();
    }

    public void setCurrentStepIndex(int index) {
        this.currentStepIndex = index;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (graph == null || !boundsValid) {
            return;
        }
        float pad = dp(40);
        float scale = Math.min(
                (getWidth() - 2 * pad) / (float) (maxX - minX),
                (getHeight() - 2 * pad) / (float) (maxY - minY));
        float ox = (getWidth() - (float) (maxX - minX) * scale) / 2;
        float oy = (getHeight() - (float) (maxY - minY) * scale) / 2;

        Set<String> routeNodes = new HashSet<>();
        for (GraphNode node : route) {
            routeNodes.add(node.getId());
        }

        // all edges
        for (GraphEdge edge : graph.getAllEdges()) {
            GraphNode a = graph.getNode(edge.getFrom());
            GraphNode b = graph.getNode(edge.getTo());
            if (a == null || b == null) {
                continue;
            }
            float ax = (float) a.getX() * scale + ox;
            float ay = (float) a.getY() * scale + oy;
            float bx = (float) b.getX() * scale + ox;
            float by = (float) b.getY() * scale + oy;
            boolean onRoute = routeNodes.contains(edge.getFrom()) && routeNodes.contains(edge.getTo())
                    && isAdjacentOnRoute(edge.getFrom(), edge.getTo());
            canvas.drawLine(ax, ay, bx, by, onRoute ? routePaint : edgePaint);
        }

        // nodes
        for (GraphNode node : graph.getNodes()) {
            float x = (float) node.getX() * scale + ox;
            float y = (float) node.getY() * scale + oy;
            Paint paint = node.isBeacon() ? beaconPaint : nodePaint;
            canvas.drawCircle(x, y, dp(node.isBeacon() ? 5 : 4), paint);
            canvas.drawText(node.getName(), x + dp(6), y - dp(3), textPaint);
        }

        // walked path — sampled server position fixes (dashed amber, small
        // dots per sample), drawn under the live marker and the legend.
        if (serverTrail.size() > 1) {
            Path trailPath = new Path();
            for (int i = 0; i < serverTrail.size(); i++) {
                float tx = (float) serverTrail.get(i).x * scale + ox;
                float ty = (float) serverTrail.get(i).y * scale + oy;
                if (i == 0) {
                    trailPath.moveTo(tx, ty);
                } else {
                    trailPath.lineTo(tx, ty);
                }
            }
            canvas.drawPath(trailPath, trailPaint);
        }
        for (MeshViewModel.TrailPoint point : serverTrail) {
            float tx = (float) point.x * scale + ox;
            float ty = (float) point.y * scale + oy;
            canvas.drawCircle(tx, ty, dp(2.5f), trailDotPaint);
        }

        // position — green when measured from a beacon, blue when derived
        // from a venue advert heard over the mesh (legend top-right).
        if (currentNodeId != null && graph.getNode(currentNodeId) != null) {
            GraphNode node = graph.getNode(currentNodeId);
            float x = (float) node.getX() * scale + ox;
            float y = (float) node.getY() * scale + oy;
            int sourceColor = ContextCompat.getColor(getContext(),
                    positionFromMesh ? R.color.tx_color : R.color.rx_color);
            positionHaloPaint.setColor(sourceColor);
            positionHaloPaint.setAlpha(60);
            positionPaint.setColor(sourceColor);
            canvas.drawCircle(x, y, dp(14), positionHaloPaint);
            canvas.drawCircle(x, y, dp(7), positionPaint);
        }

        // legend
        float legendX = getWidth() - dp(14);
        float legendY = dp(12);
        textPaint.setTextSize(sp(10));
        positionPaint.setColor(ContextCompat.getColor(getContext(), R.color.rx_color));
        canvas.drawCircle(legendX - dp(46), legendY, dp(4), positionPaint);
        canvas.drawText("beacon", legendX - dp(38), legendY + dp(3.5f), textPaint);
        positionPaint.setColor(ContextCompat.getColor(getContext(), R.color.tx_color));
        canvas.drawCircle(legendX - dp(46), legendY + dp(14), dp(4), positionPaint);
        canvas.drawText("mesh", legendX - dp(38), legendY + dp(17.5f), textPaint);
        canvas.drawLine(legendX - dp(50), legendY + dp(28),
                legendX - dp(42), legendY + dp(28), trailPaint);
        canvas.drawText("walked", legendX - dp(38), legendY + dp(31.5f), textPaint);
    }

    private boolean isAdjacentOnRoute(String from, String to) {
        for (int i = 0; i < route.size() - 1; i++) {
            if (route.get(i).getId().equals(from) && route.get(i + 1).getId().equals(to)) {
                return true;
            }
            if (route.get(i).getId().equals(to) && route.get(i + 1).getId().equals(from)) {
                return true;
            }
        }
        return false;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
