package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the Router dedup identity defect found while measuring B5 (see
 * docs/ROUTER_DEDUP_AND_MESSAGE_IDENTITY.md).
 *
 * <p>Original defect: the wire message id is 3 hex chars (4,096 values) and the Router keyed its
 * seen-set on that id alone with an LRU that could never evict, so after 4,096 distinct messages every
 * new message was dropped as a duplicate. Fixed in {@link Router} (key = source + id + kind +
 * destination + payload, sliding expiry). The tests below that used to be {@code knownDefect_*} now
 * assert the FIXED behaviour; the ones that describe limits the Router fix does not address
 * (wire-id width, twin source ids, own-echo handling) are kept as characterization tests.
 *
 * <p>JVM tests only. They prove the Router logic, not BLE behaviour on phones.
 */
public class RouterIdentityLossTest {

    private static final int WIRE_ID_SPACE = 16 * 16 * 16; // 3 hex chars
    private static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final class Harness {
        final List<MeshMessage> delivered = new ArrayList<>();
        final Router router = new Router("ME1", new MessageLog(10), new Router.Sink() {
            @Override
            public void onDelivered(MeshMessage message, int rssi) {
                delivered.add(message);
            }

            @Override
            public void onRelay(MeshMessage message, int rssi) {
            }
        });
    }

    /** A message exactly as a receiver sees it: encoded to the wire and decoded again. */
    private static MeshMessage heard(String src, String payload) {
        return PacketCodec.decode(PacketCodec.encode(
                MeshMessage.create(src, "", MeshKind.CHAT, payload)));
    }

    private static String src(int i) {
        int a = i % 32, b = (i / 32) % 32, c = (i / 1024) % 32;
        return "" + ALPHA.charAt(a) + ALPHA.charAt(b) + ALPHA.charAt(c);
    }

    // ---- characterization: limits the Router fix does NOT address ---------------------------

    @Test
    public void characterization_wireIdSpaceIsStillOnly4096() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200_000; i++) {
            ids.add(heard("ABC", "x").getMsgId());
        }
        assertTrue("wire ids must stay within 16^3", ids.size() <= WIRE_ID_SPACE);
        assertTrue("200k draws should cover nearly the whole space", ids.size() > 4000);
    }

    @Test
    public void characterization_twoDevicesWithTheSameSourceIdCannotHearEachOther() {
        // Unresolved protocol issue (R6), NOT fixed by the Router change: p = 1/32768 per pair of
        // installs, 14% at 100 installs. Needs a wire change plus device verification.
        byte[] fromTwin = PacketCodec.encode(
                MeshMessage.create("QQ7", "", MeshKind.CHAT, "hello"));
        BlePacketInterpreter.Hit hit = BlePacketInterpreter.interpret(fromTwin, -50, "QQ7");
        assertEquals("a twin's packet is classified as our own and discarded",
                BlePacketInterpreter.Reason.SELF, hit.reason);
    }

    @Test
    public void characterization_ownSendIsStoredUnderTheFullUuidAndItsEchoIsStoppedByTheSourceFilter() {
        Harness h = new Harness();
        MeshMessage sent = MeshMessage.create("ME1", "", MeshKind.CHAT, "yo");
        h.router.onLocalSend(sent);
        MeshMessage echo = PacketCodec.decode(PacketCodec.encode(sent));
        assertEquals(36, sent.getMsgId().length());
        assertEquals(3, echo.getMsgId().length());
        assertFalse("Router.hasSeen(wireId) is false for our own send", h.router.hasSeen(echo.getMsgId()));
        byte[] wire = PacketCodec.encode(sent);
        assertEquals(BlePacketInterpreter.Reason.SELF,
                BlePacketInterpreter.interpret(wire, -50, "ME1").reason);
    }

    // ---- guards that must stay true --------------------------------------------------------

    @Test
    public void echoOfTheSameMessageStillDedups() {
        Harness h = new Harness();
        MeshMessage m = heard("AAA", "hi");
        h.router.onReceive(m, -60, "N1");
        h.router.onReceive(m.relayed(), -60, "N2"); // same identity after a relay hop
        assertEquals("a relayed copy is the same message", 1, h.delivered.size());
    }

    @Test
    public void recoveryRebuildingTheRouterStillStartsClean() {
        Harness first = new Harness();
        first.router.onReceive(heard("AAA", "a"), -60, "N");
        Harness second = new Harness(); // what MeshManager does on every mesh (re)start
        second.router.onReceive(heard("ZZZ", "fresh"), -60, "N");
        assertEquals(1, second.delivered.size());
    }

    // ---- the fixed defect ------------------------------------------------------------------

    @Test
    public void fixed_deliveriesAreNoLongerCappedAtTheIdSpace() {
        Harness h = new Harness();
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            h.router.onReceive(heard(src(i), "m" + i), -60, "N");
        }
        assertTrue("delivered " + h.delivered.size() + " of " + n + " (4,096 was the old ceiling)",
                h.delivered.size() > WIRE_ID_SPACE);
    }

    @Test
    public void fixed_deliveryContinuesAfterTheOldSaturationPoint() {
        Harness h = new Harness();
        for (int i = 0; i < 60_000; i++) {
            h.router.onReceive(heard(src(i), "warm" + i), -60, "N");
        }
        int before = h.delivered.size();
        for (int i = 0; i < 500; i++) {
            h.router.onReceive(heard(src(70_000 + i), "late" + i), -60, "N");
        }
        assertTrue("500 further distinct messages must still be delivered, got "
                + (h.delivered.size() - before), h.delivered.size() - before >= 495);
    }

    @Test
    public void fixed_sameWireIdFromDifferentSendersIsDeliveredTwice() {
        Harness h = new Harness();
        MeshMessage a = MeshMessage.createWithId("abc", "AAA", "", MeshKind.CHAT, "hello", 7);
        MeshMessage b = MeshMessage.createWithId("abc", "BBB", "", MeshKind.CHAT, "different", 7);
        h.router.onReceive(a, -60, "AAA");
        h.router.onReceive(b, -60, "BBB");
        assertEquals("different sender and payload are different messages", 2, h.delivered.size());
    }

    @Test
    public void desired_distinctMessagesAreAllDelivered() {
        Harness h = new Harness();
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            h.router.onReceive(heard(src(i), "m" + i), -60, "N");
        }
        // Distinct (src,payload) pairs; a 12-bit id clash on top of an identical src+payload cannot
        // occur here because every payload differs, so nothing may be lost.
        assertEquals("every distinct message must be delivered exactly once", n, h.delivered.size());
    }
}
