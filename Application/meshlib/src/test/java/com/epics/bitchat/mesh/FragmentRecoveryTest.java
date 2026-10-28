package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Lost-frame recovery, Meshtastic-style: a receiver that notices a gap in a
 * fragment group NACKs the sender (one round per retry window, capped), and
 * the sender re-floods exactly the missing frames with FRESH message ids so
 * dedup does not eat the recovery copies. Also covers the NACK wire payload:
 * it must round-trip through the codec and never parse as a fragment frame.
 */
public class FragmentRecoveryTest {

    // --- deterministic mini-mesh (same harness as MessageFragmentationTest) ---

    private static class StubTransport implements MeshTransport {
        final List<MeshMessage> sent = new ArrayList<>();

        @Override public void start() { }
        @Override public void stop() { }
        @Override public boolean isRunning() { return false; }
        @Override public void send(MeshMessage message) { sent.add(message); }
        @Override public String getLocalNodeId() { return ""; }
        @Override public List<Peer> getPeers() { return new ArrayList<>(); }
        @Override public void setListener(Listener listener) { }
    }

    private static class MiniMesh {
        final Map<String, MeshNode> nodes = new LinkedHashMap<>();
        final Map<String, StubTransport> transports = new LinkedHashMap<>();
        final Map<String, List<MeshMessage>> assembled = new LinkedHashMap<>();

        MiniMesh(String... names) {
            for (String name : names) {
                StubTransport transport = new StubTransport();
                MeshNode node = new MeshNode(name, name, transport, new MessageLog());
                final String id = name;
                node.addListener(new MeshEventListener() {
                    @Override public void onLogEntry(MeshLogEntry entry) { }
                    @Override public void onPeerDiscovered(Peer peer) { }
                    @Override public void onPeerLost(Peer peer) { }
                    @Override public void onVenueDirectoryChanged() { }
                    @Override public void onDeliveryStatus(String token, String dstId,
                                                           boolean delivered) { }
                    @Override public void onPositionUpdated(String graphNodeId) { }
                    @Override public void onMessageDelivered(MeshMessage message) {
                        assembled.computeIfAbsent(id, k -> new ArrayList<>()).add(message);
                    }
                });
                node.start();
                nodes.put(name, node);
                transports.put(name, transport);
            }
        }

        /** Deliver every outbox message to every OTHER node (full mesh). */
        void step() {
            Map<String, List<MeshMessage>> flights = new LinkedHashMap<>();
            for (Map.Entry<String, StubTransport> e : transports.entrySet()) {
                if (!e.getValue().sent.isEmpty()) {
                    flights.put(e.getKey(), new ArrayList<>(e.getValue().sent));
                    e.getValue().sent.clear();
                }
            }
            for (Map.Entry<String, List<MeshMessage>> flight : flights.entrySet()) {
                for (Map.Entry<String, MeshNode> target : nodes.entrySet()) {
                    if (target.getKey().equals(flight.getKey())) {
                        continue;
                    }
                    for (MeshMessage m : flight.getValue()) {
                        target.getValue().onPacketReceived(m, -55, flight.getKey());
                    }
                }
            }
        }
    }

    @Test
    public void nackTriggersResendAndCompletesTheText() {
        MiniMesh mesh = new MiniMesh("A", "B");
        MeshNode sender = mesh.nodes.get("A");
        sender.setFragmentIntervalForTest(0);
        String text = "alpha bravo charlie delta echo foxtrot"; // 38 chars -> 6 frames
        assertTrue(sender.send("", MeshKind.CHAT, text));

        // Frame 2 dies on air: deliver everything else.
        List<MeshMessage> flight = new ArrayList<>(mesh.transports.get("A").sent);
        assertTrue("a multi-frame group", flight.size() >= 3);
        mesh.transports.get("A").sent.clear();
        for (MeshMessage frame : flight) {
            if (PacketCodec.fragmentIndex(frame.getPayload()) != 2) {
                mesh.nodes.get("B").onPacketReceived(frame, -55, "A");
            }
        }
        assertTrue("no text before recovery", mesh.assembled.get("B") == null
                || mesh.assembled.get("B").isEmpty());

        // Past the grace window B asks for the missing frame...
        mesh.nodes.get("B").nackSweepForTest(
                System.currentTimeMillis() + FragmentAssembler.NACK_AFTER_MS + 1000);
        // B's outbox also carries its relays of the frames it heard — the
        // NACK is the one new kind it originated.
        List<MeshMessage> nacks = new ArrayList<>();
        for (MeshMessage m : mesh.transports.get("B").sent) {
            if (m.getKind() == MeshKind.NACK) {
                nacks.add(m);
            }
        }
        assertEquals(1, nacks.size());
        assertEquals("A", nacks.get(0).getDstId());

        // ...A re-sends exactly frame 2, with a FRESH flood id...
        mesh.step();
        List<MeshMessage> resends = mesh.transports.get("A").sent;
        assertEquals(1, resends.size());
        MeshMessage original = null;
        for (MeshMessage frame : flight) {
            if (PacketCodec.fragmentIndex(frame.getPayload()) == 2) {
                original = frame;
            }
        }
        assertNotNull(original);
        assertEquals(original.getPayload(), resends.get(0).getPayload());
        assertNotEquals("recovery copy must flood past dedup",
                original.getMsgId(), resends.get(0).getMsgId());

        // ...and B assembles the COMPLETE text.
        mesh.step();
        assertEquals(1, mesh.assembled.get("B").size());
        assertEquals(text, mesh.assembled.get("B").get(0).getPayload());
    }

    @Test
    public void nackRoundsAreCappedThenTheGroupExpiresVisibly() {
        List<String> nacks = new ArrayList<>();
        int[] expired = {0};
        FragmentAssembler assembler = new FragmentAssembler(new FragmentAssembler.Callback() {
            @Override public void onTextAssembled(String sender, String groupId,
                                                  MeshKind kind, String text) { }
            @Override public void onGroupIncomplete(String sender, String groupId,
                                                    List<Integer> missing,
                                                    int received, int total) {
                nacks.add(groupId + ":" + missing);
            }
            @Override public void onGroupExpired(String sender, String groupId,
                                                 int received, int total) {
                expired[0]++;
            }
        });
        long t0 = 1_000_000L;
        String group = "G01";
        assembler.offer("SRC", MeshKind.CHAT, PacketCodec.makeFragmentFrame(group, 1, 4, "aa"), t0);
        assembler.offer("SRC", MeshKind.CHAT, PacketCodec.makeFragmentFrame(group, 3, 4, "cc"), t0);
        assembler.offer("SRC", MeshKind.CHAT, PacketCodec.makeFragmentFrame(group, 4, 4, "dd"), t0);

        assembler.nackSweep(t0 + FragmentAssembler.NACK_AFTER_MS); // round 1
        assertEquals(1, nacks.size());
        assertEquals(group + ":[2]", nacks.get(0));

        assembler.nackSweep(t0 + FragmentAssembler.NACK_AFTER_MS
                + FragmentAssembler.NACK_RETRY_MS - 1); // inside retry window
        assertEquals(1, nacks.size());

        assembler.nackSweep(t0 + FragmentAssembler.NACK_AFTER_MS
                + FragmentAssembler.NACK_RETRY_MS); // round 2
        assertEquals(2, nacks.size());
        assembler.nackSweep(t0 + FragmentAssembler.NACK_AFTER_MS
                + 2 * FragmentAssembler.NACK_RETRY_MS); // round 3
        assertEquals(3, nacks.size());
        assembler.nackSweep(t0 + FragmentAssembler.NACK_AFTER_MS
                + 3 * FragmentAssembler.NACK_RETRY_MS); // cap reached
        assertEquals(3, nacks.size());

        // The TTL expiry still surfaces the drop exactly once.
        assembler.nackSweep(t0 + FragmentAssembler.GROUP_TTL_MS + 1);
        assertEquals(1, expired[0]);
        assembler.nackSweep(t0 + FragmentAssembler.GROUP_TTL_MS + 2);
        assertEquals(1, expired[0]);
    }

    @Test
    public void completedGroupNeverNacksAndNeverExpires() {
        List<String> nacks = new ArrayList<>();
        int[] expired = {0};
        List<String> assembled = new ArrayList<>();
        FragmentAssembler assembler = new FragmentAssembler(new FragmentAssembler.Callback() {
            @Override public void onTextAssembled(String sender, String groupId,
                                                  MeshKind kind, String text) {
                assembled.add(text);
            }
            @Override public void onGroupIncomplete(String sender, String groupId,
                                                    List<Integer> missing,
                                                    int received, int total) {
                nacks.add(groupId);
            }
            @Override public void onGroupExpired(String sender, String groupId,
                                                 int received, int total) {
                expired[0]++;
            }
        });
        long t0 = 1_000_000L;
        String group = "G02";
        for (int i = 1; i <= 3; i++) {
            assembler.offer("SRC", MeshKind.CHAT,
                    PacketCodec.makeFragmentFrame(group, i, 3, "p" + i), t0);
        }
        assertEquals(1, assembled.size());
        assembler.nackSweep(t0 + FragmentAssembler.NACK_AFTER_MS);
        assembler.nackSweep(t0 + FragmentAssembler.GROUP_TTL_MS + 1);
        assertTrue("complete text needs no rescue", nacks.isEmpty());
        assertEquals("completed text is never reported lost", 0, expired[0]);
    }

    @Test
    public void nackPayloadRoundTripsAndNeverLooksLikeAFragment() {
        long mask = (1L << 1) | (1L << 3) | (1L << 35); // frames 2, 4, 36 missing
        String payload = PacketCodec.makeNackPayload("G7X", mask);

        assertEquals(3 + 1 + PacketCodec.NACK_MASK_CHARS, payload.length());
        assertFalse("NACK must not parse as a fragment frame",
                PacketCodec.isFragment(payload));
        assertTrue(PacketCodec.isNack(payload));
        assertEquals("G7X", PacketCodec.nackGroupId(payload));
        assertEquals(mask, PacketCodec.nackMissingMask(payload));

        MeshMessage message = MeshMessage.create("SRC", "DST", MeshKind.NACK, payload);
        MeshMessage back = PacketCodec.decode(PacketCodec.encode(message));
        assertNotNull(back);
        assertEquals(MeshKind.NACK, back.getKind());
        assertEquals(payload, back.getPayload());
        assertFalse(PacketCodec.isNack("G7XNabc")); // wrong length: not a NACK
        assertEquals("", PacketCodec.nackGroupId("G7XNabc"));
    }
}
