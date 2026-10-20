package com.epics.bitchat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.epics.bitchat.R;

/**
 * Compass arrow. {@code setBearingDeg} should receive the angle between the
 * device heading and the target waypoint (0 = pointing straight ahead).
 */
public class CompassView extends View {

    private float bearingDeg = 0f;
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CompassView(Context context) {
        super(context);
        init(context);
    }

    public CompassView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(dp(2));
        circlePaint.setColor(ContextCompat.getColor(context, R.color.relay_color));

        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(ContextCompat.getColor(context, R.color.primary));

        northPaint.setStyle(Paint.Style.FILL);
        northPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        northPaint.setTextSize(sp(10));
    }

    public void setBearingDeg(float bearingDeg) {
        this.bearingDeg = bearingDeg;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(8);

        canvas.drawCircle(cx, cy, radius, circlePaint);

        canvas.save();
        canvas.rotate(bearingDeg, cx, cy);
        Path arrow = new Path();
        arrow.moveTo(cx, cy - radius * 0.8f);
        arrow.lineTo(cx - radius * 0.25f, cy + radius * 0.4f);
        arrow.lineTo(cx + radius * 0.25f, cy + radius * 0.4f);
        arrow.close();
        canvas.drawPath(arrow, arrowPaint);
        canvas.restore();

        canvas.drawText("N", cx - northPaint.measureText("N") / 2f, dp(14), northPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
