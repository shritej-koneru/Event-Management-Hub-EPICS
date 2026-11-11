package com.epics.bitchat.mesh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The scan self-heal state machine (the MIUI silent-scan-death fix): healthy
 * traffic never escalates; a dead scanner escalates re-arm → full recovery;
 * a quiet room with no neighbors stays calm; a recovery resets the ladder.
 */
public class ScanWatchdogTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void healthyTrafficNeverEscalates() {
        ScanWatchdog w = new ScanWatchdog(2);
        // Ambient callbacks + mesh packets, forever:
        for (long t = T0; t < T0 + 10 * 60_000L; t += 5_000L) {
            w.onScanCallback(t);
            w.onMeshPacket(t);
            assertEquals(ScanWatchdog.Action.NONE, w.evaluate(t + 1));
        }
    }

    @Test
    public void deadScannerEscalatesReArmThenFullRecovery() {
        ScanWatchdog w = new ScanWatchdog(2);
        w.onScanCallback(T0);
        w.onMeshPacket(T0);
        w.onRearmed(T0);

        long t = T0 + ScanWatchdog.RAW_STALL_MS; // scanner fully silent
        assertEquals("first verdict: re-arm", ScanWatchdog.Action.REARM_SCAN, w.evaluate(t));
        w.onRearmed(t);

        // Inside the probe window: still silent, but give the re-arm a chance.
        assertEquals(ScanWatchdog.Action.NONE, w.evaluate(t + 5_000L));

        // Probe expired, still silent: second re-arm.
        t += ScanWatchdog.REARM_PROBE_MS;
        assertEquals(ScanWatchdog.Action.REARM_SCAN, w.evaluate(t));
        w.onRearmed(t);

        // Probe expired again, STILL silent: rebuild the radio objects —
        // the app-restart cure (observed needed on MIUI).
        t += ScanWatchdog.REARM_PROBE_MS;
        assertEquals(ScanWatchdog.Action.FULL_RECOVERY, w.evaluate(t));
    }

    @Test
    public void ambientTrafficWithDeadMeshPathStillTriggers() {
        ScanWatchdog w = new ScanWatchdog(1);
        w.onScanCallback(T0);
        w.onMeshPacket(T0);
        w.onRearmed(T0);

        // The radio keeps delivering OTHER devices' adverts (so the raw
        // signal stays fed) but OUR mesh packets stop: the mesh-stall signal
        // must catch this even though the scanner looks alive.
        long t = T0;
        while (t - T0 < ScanWatchdog.MESH_STALL_MS - 5_000L) {
            t += 5_000L;
            w.onScanCallback(t); // ambient only
            assertEquals(ScanWatchdog.Action.NONE, w.evaluate(t + 1));
        }
        t = T0 + ScanWatchdog.MESH_STALL_MS + 1_000L;
        w.onScanCallback(t);
        assertEquals("mesh silence with live ambient traffic is caught",
                ScanWatchdog.Action.REARM_SCAN, w.evaluate(t));
    }

    @Test
    public void quietRoomDoesNotFireTheMeshSignal() {
        ScanWatchdog w = new ScanWatchdog(2);
        // A phone alone in a field: no callbacks, no mesh packets, ever.
        // The mesh-stall signal requires PRIOR mesh traffic — it must stay
        // quiet (only the raw path may eventually fire, which is harmless).
        assertEquals(ScanWatchdog.Action.NONE, w.evaluate(T0 + 10 * 60_000L));
    }

    @Test
    public void recoveryResetsTheEscalationLadder() {
        ScanWatchdog w = new ScanWatchdog(1);
        w.onScanCallback(T0);
        w.onMeshPacket(T0);

        long t = T0 + ScanWatchdog.RAW_STALL_MS;
        assertEquals(ScanWatchdog.Action.REARM_SCAN, w.evaluate(t));
        w.onRearmed(t);
        t += ScanWatchdog.REARM_PROBE_MS;
        assertEquals(ScanWatchdog.Action.FULL_RECOVERY, w.evaluate(t));

        // Recovery rebuilt the radio and traffic came back: the ladder must
        // reset, so the NEXT stall starts gently at REARM again.
        long healed = t + 1_000L;
        w.onScanCallback(healed);
        w.onMeshPacket(healed);
        assertEquals(ScanWatchdog.Action.NONE, w.evaluate(healed + 1));

        long t2 = healed + ScanWatchdog.RAW_STALL_MS;
        assertEquals("next stall escalates from the bottom again",
                ScanWatchdog.Action.REARM_SCAN, w.evaluate(t2));
    }
}
