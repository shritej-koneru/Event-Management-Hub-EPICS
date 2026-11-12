package com.epics.bitchat.mesh;

/**
 * Pure decision logic for BLE scan self-healing (found on a Xiaomi/MIUI
 * phone: the scan silently stops delivering ANY results while the stack
 * still reports it running — a stop/start of the scanner did not cure it,
 * but rebuilding the app's scanner/advertiser objects did, which is what an
 * app restart effectively does).
 *
 * <p>The transport feeds this class observations and periodically asks for a
 * verdict:
 * <ul>
 *   <li>{@code onScanCallback(now)} — ANY scan result arrived (ambient BLE
 *       traffic counts: the radio link itself is alive);</li>
 *   <li>{@code onMeshPacket(now)} — a result carried OUR mesh protocol
 *       (identity or message packet);</li>
 *   <li>{@code onRearmed(now)} — the transport just (re-)started its scan;</li>
 *   <li>{@code evaluate(now)} — periodic verdict.</li>
 * </ul>
 *
 * <p>Verdicts, in escalation order:
 * <ol>
 *   <li>{@link Action#REARM_SCAN} — stop/start the scanner (cheap, cures
 *       most stalls);</li>
 *   <li>{@link Action#FULL_RECOVERY} — after {@code maxConsecutiveRearms}
 *       re-arms failed to revive traffic, rebuild the scanner/advertiser
 *       objects entirely (the app-restart cure, without the restart).</li>
 * </ol>
 *
 * <p>Two independent stall signals:
 * <ul>
 *   <li><b>raw stall</b> — no scan callback of any kind for
 *       {@value #RAW_STALL_MS} (scanner fully dead);</li>
 *   <li><b>mesh stall</b> — callbacks still flowing (ambient traffic) but no
 *       mesh packet for {@value #MESH_STALL_MS} while we SAW mesh traffic in
 *       the last {@value #PEER_MEMORY_MS} (scanner alive but our traffic is
 *       being filtered/dropped — re-arming usually re-registers the scan
 *       callback with the stack).</li>
 * </ul>
 *
 * <p>Thread-safe and Android-free, so it is directly unit-testable.
 */
public class ScanWatchdog {

    public enum Action {
        /** Everything healthy — or a re-arm is already probing. */
        NONE,
        /** Stop + start the scanner. */
        REARM_SCAN,
        /** Rebuild the scanner/advertiser objects (last resort). */
        FULL_RECOVERY
    }

    /** No scan callback of any kind for this long → the scanner is dead. */
    public static final long RAW_STALL_MS = 60_000L;
    /** Callbacks flow but no mesh packet for this long → our traffic is filtered. */
    public static final long MESH_STALL_MS = 45_000L;
    /** After a re-arm, give the radio this long to prove itself. */
    public static final long REARM_PROBE_MS = 20_000L;
    /** Mesh traffic seen within this window keeps the mesh-stall signal armed. */
    public static final long PEER_MEMORY_MS = 600_000L;

    private final int maxConsecutiveRearms;

    private long lastCallbackAt = Long.MIN_VALUE;
    private long lastMeshAt = Long.MIN_VALUE;
    private long lastRearmAt = Long.MIN_VALUE;
    private int consecutiveRearms;

    public ScanWatchdog(int maxConsecutiveRearms) {
        this.maxConsecutiveRearms = maxConsecutiveRearms;
    }

    /** ANY scan result arrived (ambient traffic included). */
    public synchronized void onScanCallback(long nowMs) {
        lastCallbackAt = nowMs;
    }

    /** A result carrying our mesh protocol (identity or message packet). */
    public synchronized void onMeshPacket(long nowMs) {
        lastMeshAt = nowMs;
        lastCallbackAt = nowMs;
    }

    /** The transport (re-)started its scan — begin a fresh probe window. */
    public synchronized void onRearmed(long nowMs) {
        lastRearmAt = nowMs;
    }

    /**
     * Periodic verdict. Callers act on {@link Action#REARM_SCAN} /
     * {@link Action#FULL_RECOVERY} and report back via {@link #onRearmed}.
     */
    public synchronized Action evaluate(long nowMs) {
        boolean meshRecently = lastMeshAt != Long.MIN_VALUE
                && nowMs - lastMeshAt <= PEER_MEMORY_MS;
        boolean meshDead = meshRecently && nowMs - lastMeshAt >= MESH_STALL_MS;
        boolean rawDead = lastCallbackAt != Long.MIN_VALUE
                && nowMs - lastCallbackAt >= RAW_STALL_MS;

        if (!meshDead && !rawDead) {
            consecutiveRearms = 0; // healthy — reset the escalation ladder
            return Action.NONE;
        }
        if (lastRearmAt == Long.MIN_VALUE || nowMs - lastRearmAt >= REARM_PROBE_MS) {
            consecutiveRearms++;
            lastRearmAt = nowMs;
            return consecutiveRearms > maxConsecutiveRearms
                    ? Action.FULL_RECOVERY
                    : Action.REARM_SCAN;
        }
        return Action.NONE; // a re-arm is inside its probe window
    }
}
