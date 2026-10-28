package com.epics.bitchat.mesh;

/**
 * RSSI smoothing: a short moving average layered over exponential smoothing.
 * BLE RSSI is noisy (multi-path, body absorption), so we never feed a raw
 * sample into positioning.
 */
public class RssiFilter {

    public static final int NO_SIGNAL = -128;

    private static final int WINDOW = 5;
    private static final double ALPHA = 0.3;

    private final int[] window = new int[WINDOW];
    private int index = 0;
    private int count = 0;
    private double smoothed = NO_SIGNAL;

    public int update(int sample) {
        window[index] = sample;
        index = (index + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }
        double mean = 0;
        for (int i = 0; i < count; i++) {
            mean += window[i];
        }
        mean /= count;
        if (smoothed == NO_SIGNAL) {
            smoothed = mean;
        } else {
            smoothed = ALPHA * mean + (1 - ALPHA) * smoothed;
        }
        return (int) Math.round(smoothed);
    }

    public int get() {
        return (int) Math.round(smoothed);
    }

    public void reset() {
        index = 0;
        count = 0;
        smoothed = NO_SIGNAL;
    }
}
