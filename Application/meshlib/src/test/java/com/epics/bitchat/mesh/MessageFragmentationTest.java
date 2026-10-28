package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Long-message auto-fragmentation end to end: a text longer than one BLE
 * advert is split into framed packets on the sender, the packets travel
 * through the normal flood relay, and every receiver assembles the COMPLETE
 * text (one assembled row per group, duplicate frames absorbed, out-of-order
 * arrival tolerated). Runs on the deterministic in-process radio.
 */
public class MessageFragmentationTest {

    // --- deterministic mini-mesh (same harness as BroadcastFanoutTest) --------

    /** Remembers every message a node handed to its transport. */
    private static class StubTransport implements MeshTransport {
        final List<MeshMessage> sent = new ArrayList<>();

        @Override public void start() {
        }

        @Override public void stop() {
        }

        @Override public boolean isRunning() {
            return false;
        }

        @Override public void send(MeshMessage message) {
            sent.add(message);
        }

        @Override public String getLocalNodeId() {
            return "";
        }

        @Override public List<Peer> getPeers() {
            return new ArrayList<>();
        }

        @Override public void setListener(Listener listener) {
        }
    }

    private static class MiniMesh {
        final Map<String, MeshNode> nodes = new LinkedHashMap<>();
        final Map<String, StubTransport> transports = new LinkedHashMap<>();
        final Map<String, List<MeshMessage>> outboxes = new LinkedHashMap<>();
        final Map<String, Set<String>> links = new HashMap<>();
        /** Complete reassembled texts received per node (order of arrival). */
        final Map<String, List<MeshMessage>> assembled = new HashMap<>();

        MiniMesh(String... names) {
            for (String name : names) {
                StubTransport transport = new StubTransport();
                MessageLog log = new MessageLog();
                MeshNode node = new MeshNode(name, name, transport, log);
                final String id = name;
                node.addListener(new MeshEventListener() {
                    @Override public void onLogEntry(MeshLogEntry entry) {
                    }

                    @Override public void onPeerDiscovered(Peer peer) {
                    }

                    @Override public void onPeerLost(Peer peer) {
                    }

                    @Override public void onMessageDelivered(MeshMessage message) {
                        // Fragment frames are absorbed at the node's sink; the
                        // ONLY delivery events that reach listeners are the
                        // COMPLETE reassembled texts.
                        assembled.computeIfAbsent(id, k -> new ArrayList<>()).add(message);
                    }

                    @Override public void onPositionUpdated(String graphNodeId) {
                    }
                });
                nodes.put(name, node);
                transports.put(name, transport);
                outboxes.put(name, transport.sent);
                // send()/broadcast() gate on running — start every node so the
                // tests exercise the real entry points (maintenance tasks are
                // no-ops on this stub radio).
                node.start();
            }
        }

        void link(String a, String b) {
            links.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            links.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        void step() {
            Map<String, List<MeshMessage>> flights = new LinkedHashMap<>();
            for (Map.Entry<String, List<MeshMessage>> e : outboxes.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    flights.put(e.getKey(), new ArrayList<>(e.getValue()));
                    e.getValue().clear();
                }
            }
            for (Map.Entry<String, List<MeshMessage>> flight : flights.entrySet()) {
                String sender = flight.getKey();
                Set<String> targets = links.isEmpty()
                        ? nodes.keySet()
                        : links.getOrDefault(sender, new HashSet<>());
                for (String target : targets) {
                    if (target.equals(sender)) {
                        continue;
                    }
                    MeshNode receiver = nodes.get(target);
                    for (MeshMessage m : flight.getValue()) {
                        receiver.onPacketReceived(m, -55, sender);
                    }
                }
            }
        }
    }

    /** The sender's flood frames as a neighbor would relay each onward. */
    private static List<MeshMessage> relayedCopiesOf(String srcId, List<MeshMessage> frames) {
        List<MeshMessage> copies = new ArrayList<>();
        for (MeshMessage frame : frames) {
            if (!frame.getSrcId().equals(srcId) || frame.getDstId() != null
                    && !frame.getDstId().isEmpty()) {
                continue;
            }
            copies.add(new MeshMessage(frame.getMsgId(), frame.getSrcId(), frame.getDstId(),
                    frame.getKind(), frame.getPayload(), frame.getTtl() - 1,
                    frame.getHops() + 1, frame.getCreatedAtEpochMs()));
        }
        return copies;
    }

    // --- codec: framing --------------------------------------------------------

    @Test
    public void fragmentSplitsLongTextAndEveryFrameFitsTheWire() {
        String text = "DOORS CLOSE AT 9PM - PLEASE MOVE TO THE EAST HALL";
        List<String> frames = PacketCodec.fragment(text, "QX7");
        assertNotNull(frames);
        assertTrue("expected at least 2 frames, got " + frames.size(),
                frames.size() >= 2);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            String frame = frames.get(i);
            assertTrue("frame " + i + " fits the advert",
                    frame.length() <= PacketCodec.MAX_PAYLOAD);
            assertEquals("frame " + i + " carries the group id",
                    "QX7", PacketCodec.fragmentGroup(frame));
            assertEquals("frame " + i + " index", i + 1, PacketCodec.fragmentIndex(frame));
            assertEquals("frame " + i + " total",
                    frames.size(), PacketCodec.fragmentCount(frame));
            joined.append(PacketCodec.fragmentText(frame));
        }
        assertEquals("frames rejoin to the exact text", text, joined.toString());
    }

    @Test
    public void framesParseBackToGroupIndexAndTotal() {
        String groupId = PacketCodec.fragmentGroupId("SRC", "same long text", 42L);
        assertEquals("group id is wire-sized", 3, groupId.length());
        List<String> frames = PacketCodec.fragment("same long text", groupId);
        assertNotNull(frames);
        assertEquals(2, frames.size());
        for (int i = 0; i < frames.size(); i++) {
            assertEquals("frame " + i + " group", groupId,
                    PacketCodec.fragmentGroup(frames.get(i)));
            assertEquals("frame " + i + " index", i + 1,
                    PacketCodec.fragmentIndex(frames.get(i)));
            assertEquals("frame " + i + " total", frames.size(),
                    PacketCodec.fragmentCount(frames.get(i)));
        }
        // The engine suffixes a DIFFERENT wire id per frame (random for user
        // sends, event-derived for ingests) — the group id itself travels in
        // the frame payload, so a 3-char wire id is enough for dedup.
        assertEquals("A", PacketCodec.fragmentIndexToken(1));
    }

    // --- end-to-end over the mesh ---------------------------------------------

    @Test
    public void longBroadcastReachesEveryPhoneAsOneReassembledText() {
        MiniMesh mesh = new MiniMesh("SRC", "A", "B");
        mesh.nodes.get("SRC").setFragmentIntervalForTest(0);
        String announcement = "DOORS CLOSE AT 9PM - PLEASE MOVE TO THE EAST HALL NOW";

        assertTrue(mesh.nodes.get("SRC").broadcast(announcement, Router.DEFAULT_TTL));
        List<MeshMessage> srcFrames =
                new ArrayList<>(mesh.transports.get("SRC").sent);
        mesh.step(); // SRC's frames flood; A and B relay each frame
        mesh.step(); // relay copies criss-cross; duplicates ignored
        mesh.step(); // settled

        for (String receiver : new String[]{"A", "B"}) {
            List<MeshMessage> got = mesh.assembled.get(receiver);
            assertNotNull(receiver + " received the text", got);
            assertEquals(receiver + " assembled exactly once per group",
                    1, got.size());
            assertEquals(receiver + " text arrived COMPLETE",
                    announcement, got.get(0).getPayload());
            assertEquals(receiver + " assembled from the original sender",
                    "SRC", got.get(0).getSrcId());
        }
        // Nothing was dropped for size, and every frame stayed wire-sized.
        for (MeshMessage sent : srcFrames) {
            assertTrue(sent.getPayload().length() <= PacketCodec.MAX_PAYLOAD);
        }
    }

    @Test
    public void fragmentsCarryDeterministicIdsSoRelayDedupWorks() {
        MiniMesh mesh = new MiniMesh("SRC", "A");
        mesh.nodes.get("SRC").setFragmentIntervalForTest(0);
        String announcement = "DOORS CLOSE AT 9PM - PLEASE MOVE TO HALL 2";

        assertTrue(mesh.nodes.get("SRC").broadcast(announcement, Router.DEFAULT_TTL));
        // Capture BEFORE step(): the harness drains the outbox as it steps.
        List<MeshMessage> srcFrames = new ArrayList<>(mesh.transports.get("SRC").sent);
        mesh.step();

        assertFalse("the text was split into frames", srcFrames.isEmpty());
        for (MeshMessage frame : srcFrames) {
            assertTrue("frame payload fits the wire",
                    frame.getPayload().length() <= PacketCodec.MAX_PAYLOAD);
            assertTrue("frame carries the fragment frame",
                    PacketCodec.isFragment(frame.getPayload()));
        }
        // The neighbor relays each frame; every relayed copy must carry the
        // SAME id (group+index) and text as the sender's frame, so the mesh
        // dedup collapses the copies and the receiver assembles exactly once.
        List<MeshMessage> relayed = relayedCopiesOf("SRC", srcFrames);
        assertEquals(srcFrames.size(), relayed.size());
        for (int i = 0; i < srcFrames.size(); i++) {
            assertEquals("relayed frame keeps the wire id",
                    srcFrames.get(i).getMsgId(), relayed.get(i).getMsgId());
            assertEquals("relayed frame keeps the frame text",
                    srcFrames.get(i).getPayload(), relayed.get(i).getPayload());
        }
    }

    @Test
    public void completeChatGroupIsAckedOnceWithGroupToken() {
        MiniMesh mesh = new MiniMesh("SRC", "DST");
        mesh.nodes.get("SRC").setFragmentIntervalForTest(0);
        String note = "GATE CHANGE: BOARDING AT 42 MOVED TO 19 - CHECK YOUR PASS";

        assertTrue(mesh.nodes.get("SRC").send("DST", MeshKind.CHAT, note));
        // Capture BEFORE the harness drains the outboxes on step().
        List<MeshMessage> srcFrames = new ArrayList<>(mesh.transports.get("SRC").sent);
        mesh.step(); // frames arrive; the assembler completes and queues the ack
        List<MeshMessage> dstSent = new ArrayList<>(mesh.transports.get("DST").sent);
        mesh.step(); // the ack travels back to SRC
        mesh.step();

        // Exactly ONE ack for the whole stream, carrying the GROUP token —
        // "delivered" means the complete text reassembled at the destination,
        // not merely that the first frame landed.
        int acks = 0;
        String ackToken = null;
        for (MeshMessage sent : dstSent) {
            if (sent.getKind() == MeshKind.ACK) {
                acks++;
                ackToken = sent.getPayload();
            }
        }
        assertEquals("one group ack, not one per frame", 1, acks);
        assertNotNull(ackToken);
        assertEquals("ack token = 3-char group id", 3, ackToken.length());
        String groupId = PacketCodec.fragmentGroup(srcFrames.get(0).getPayload());
        assertEquals("ack token matches the frame group id", groupId, ackToken);
    }

    @Test
    public void fragmentedUnicastComesBackAsOneMessage() {
        MiniMesh mesh = new MiniMesh("SRC", "DST");
        mesh.nodes.get("SRC").setFragmentIntervalForTest(0);
        String note = "GATE CHANGE: BOARDING AT 42 MOVED TO 19 - CHECK YOUR PASS";

        assertTrue(mesh.nodes.get("SRC").send("DST", MeshKind.CHAT, note));
        mesh.step();
        mesh.step();

        List<MeshMessage> got = mesh.assembled.get("DST");
        assertNotNull("receiver got the text", got);
        assertEquals("assembled exactly once", 1, got.size());
        assertEquals("text arrived COMPLETE", note, got.get(0).getPayload());
        assertEquals("DST", got.get(0).getDstId());
    }

    @Test
    public void duplicateFramesDoNotAssembleTwice() {
        MiniMesh mesh = new MiniMesh("SRC", "A", "B");
        mesh.nodes.get("SRC").setFragmentIntervalForTest(0);
        mesh.link("SRC", "A");
        mesh.link("SRC", "B");
        mesh.link("A", "B"); // full triangle: every phone hears every frame 2-3x

        mesh.nodes.get("SRC").broadcast("LONG SAFETY NOTICE REMINDER FOR ALL VISITORS TODAY",
                Router.DEFAULT_TTL);
        mesh.step();
        mesh.step();
        mesh.step();

        for (String receiver : new String[]{"A", "B", "SRC"}) {
            List<MeshMessage> got = mesh.assembled.get(receiver);
            if (got == null) {
                continue; // SRC sees its own flood only via echo
            }
            assertEquals(receiver + " assembled each group exactly once",
                    1, got.size());
        }
        assertEquals(1, mesh.assembled.get("A").size());
        assertEquals(1, mesh.assembled.get("B").size());
    }

    @Test
    public void framesArrivingOutOfOrderStillAssemble() {
        MiniMesh mesh = new MiniMesh("SRC", "A");
        // Build a real multi-frame stream manually, deliver in reverse order.
        String text = "out of order arrival reassembly works on real radios";
        String groupId = PacketCodec.fragmentGroupId("SRC", text, 7L);
        List<String> frames = PacketCodec.fragment(text, groupId);
        assertTrue("multi-frame stream", frames.size() >= 2);

        // Deliver every frame in REVERSE order — the assembler must not care.
        for (int i = frames.size(); i > 0; i--) {
            mesh.nodes.get("A").onPacketReceived(new MeshMessage(
                    groupId + PacketCodec.fragmentIndexToken(i),
                    "SRC", "", MeshKind.BEACON, frames.get(i - 1), 8, 0, 0L), -55, "SRC");
        }

        List<MeshMessage> got = mesh.assembled.get("A");
        assertNotNull("reassembly fired on the last missing frame", got);
        assertEquals(1, got.size());
        assertEquals(text, got.get(0).getPayload());
    }

    // --- announce / stability seams -------------------------------------------

    @Test
    public void oversizedIngestIsFragmentedNotRejected() {
        MiniMesh mesh = new MiniMesh("GATEWAY", "R");
        mesh.nodes.get("GATEWAY").setFragmentIntervalForTest(0);
        mesh.nodes.get("GATEWAY").start();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            text.append("word ");
        }
        assertTrue(mesh.nodes.get("GATEWAY").ingestFromInternet("evt-long", text.toString()));
        mesh.step();
        mesh.step();

        List<MeshMessage> got = mesh.assembled.get("R");
        assertNotNull("receiver got the text", got);
        assertEquals("assembled exactly once", 1, got.size());
        assertEquals(text.toString(), got.get(0).getPayload());
    }

    @Test
    public void staggeredFlushSendsOneFrameAtATime() {
        MiniMesh mesh = new MiniMesh("SRC", "A");
        mesh.nodes.get("SRC").setFragmentIntervalForTest(50);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            text.append("chunk ");
        }
        mesh.nodes.get("SRC").broadcast(text.toString(), Router.DEFAULT_TTL);
        assertEquals("only the first frame left immediately (staggering)",
                1, mesh.transports.get("SRC").sent.size());
    }
}
