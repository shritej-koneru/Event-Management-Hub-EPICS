package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The internet-ingest flow end to end: a server push enters one connected
 * phone and floods the BLE mesh with a stable id; phones that already saw
 * that id (including one that got the same event from its own internet
 * feed) ignore it; re-broadcasts stay inside the 5 s window; and the
 * ingest never turns a duplicate into a second flood.
 */
public class InternetIngestTest {

    /** Broadcast sink recording what left this node over the air. */
    private static class Sink implements Router.Sink {
        final List<MeshMessage> sent = new ArrayList<>();

        @Override
        public void onDelivered(MeshMessage message, int rssi) {
        }

        @Override
        public void onRelay(MeshMessage message, int rssi) {
            sent.add(message);
        }
    }

    /** Minimal offline transport for driving MeshNode directly. */
    private static class StubTransport implements MeshTransport {
        final List<MeshMessage> out = new ArrayList<>();

        @Override public void start() {
        }

        @Override public void stop() {
        }

        @Override public boolean isRunning() {
            return false;
        }

        @Override public void send(MeshMessage message) {
            out.add(message);
        }

        @Override public String getLocalNodeId() {
            return "GATEWAY";
        }

        @Override public List<Peer> getPeers() {
            return new ArrayList<>();
        }

        @Override public void setListener(Listener listener) {
        }
    }

    private static MeshMessage ingestMessage(String wireId, String src, String payload) {
        return MeshMessage.createWithId(wireId, src, "", MeshKind.ANNOUNCE, payload,
                Router.DEFAULT_TTL);
    }

    @Test
    public void sameExternalEventAlwaysMapsToSameWireId() {
        assertEquals(MeshNode.wireMsgIdFor("venue-change-4711"),
                MeshNode.wireMsgIdFor("venue-change-4711"));
        assertFalse(MeshNode.wireMsgIdFor("venue-change-4711")
                .equals(MeshNode.wireMsgIdFor("venue-change-4712")));
        assertEquals(3, MeshNode.wireMsgIdFor("anything").length());
    }

    @Test
    public void ingestedIdIsMarkedSeenSoEchoedCopiesAreIgnored() {
        MessageLog log = new MessageLog();
        Sink sink = new Sink();
        Router gateway = new Router("GATEWAY", log, sink);

        MeshMessage message = ingestMessage(
                MeshNode.wireMsgIdFor("evt-1"), "GATEWAY", "ROUTE:Hall2");
        gateway.onLocalSend(message);
        assertTrue(gateway.hasSeen(message.getMsgId()));

        // A neighbor relayed our own flood back to us — it was already seen
        // (marked TRUE at local send), so it is ignored silently: no dup row,
        // and crucially no second transmission.
        gateway.onReceive(relayedCopy(message), -60, "PEER1");
        int dupDrops = 0;
        for (MeshLogEntry entry : log.getEntries()) {
            if (entry.getStatus() == MeshStatus.DROPPED_DUP) {
                dupDrops++;
            }
        }
        assertEquals("echo of an already-relayed local send is silent", 0, dupDrops);
        assertEquals("no re-transmission after the echo", 0, sink.sent.size());
    }

    @Test
    public void phoneWithOwnInternetFeedDedupsSameEventId() {
        StubTransport transport = new StubTransport();
        MeshNode node = new MeshNode("RX", "SM-M156B", transport, new MessageLog());

        // The event arrives over the mesh from a neighbor; relaying it on is
        // correct flood behavior.
        node.onPacketReceived(ingestMessage(
                MeshNode.wireMsgIdFor("evt-2"), "OTHER", "ROUTE:Hall2"), -60, "OTHER");
        int afterFlood = transport.out.size();
        assertTrue("first copy is relayed onward", afterFlood >= 1);

        // Now this phone's OWN internet feed delivers the same server event:
        // the ingest must recognize the id and NOT re-flood the mesh.
        assertTrue(node.ingestFromInternet("evt-2", "ROUTE:Hall2"));
        node.rebroadcastTickForTest();
        assertEquals("same event id is never re-flooded",
                afterFlood, transport.out.size());
    }

    @Test
    public void receivingNodeRelaysFirstCopyAndDropsLaterOnes() {
        MessageLog log = new MessageLog();
        Sink sink = new Sink();
        Router receiver = new Router("RX", log, sink);

        MeshMessage first = ingestMessage(
                MeshNode.wireMsgIdFor("evt-3"), "GW", "ROUTE:Hall2");
        receiver.onReceive(first, -60, "GW");
        receiver.onReceive(first, -64, "OTHER");

        assertEquals(1, sink.sent.size());
        assertEquals(first.getMsgId(), sink.sent.get(0).getMsgId());
        assertEquals(1, sink.sent.get(0).getHops());
        assertEquals(Router.DEFAULT_TTL - 1, sink.sent.get(0).getTtl());
    }

    @Test
    public void ingestFloodsThenStopsInsideTheWindow() {
        StubTransport transport = new StubTransport();
        MeshNode node = new MeshNode("GATEWAY", "SM-E066B", transport, new MessageLog());
        ManualClock clock = new ManualClock();
        node.setRebroadcastWindow(new RebroadcastWindow(
                RebroadcastWindow.DEFAULT_WINDOW_MS, clock, 256));

        String externalId = "server-push-100";
        assertTrue(node.ingestFromInternet(externalId, "ROUTE:Hall2"));
        int total = transport.out.size();
        assertTrue("initial flood went out", total >= 1);

        // Drive the scheduler manually through the 5 s window. begin() counts
        // the initial flood as send #1, so re-broadcasts land at 1..4 s.
        long step = RebroadcastWindow.DEFAULT_WINDOW_MS / RebroadcastWindow.SENDS_PER_WINDOW;
        for (long t = step / 2; t <= RebroadcastWindow.DEFAULT_WINDOW_MS; t += step / 2) {
            clock.advanceMs(step / 2);
            node.rebroadcastTickForTest();
            total = transport.out.size();
        }
        // Total budget: initial + 4 re-broadcasts; nothing after the window.
        assertEquals(RebroadcastWindow.SENDS_PER_WINDOW, total);

        // Re-ingesting the same event id stays a no-op: the router's seen-set
        // is the long-term memory, the 5 s window is only the cadence.
        int afterWindow = transport.out.size();
        assertTrue(node.ingestFromInternet(externalId, "ROUTE:Hall2"));
        node.rebroadcastTickForTest();
        assertEquals("seen ids are never re-flooded", afterWindow, transport.out.size());
    }

    @Test
    public void cancelInternetRebroadcastStopsTheWindowEarly() {
        StubTransport transport = new StubTransport();
        MeshNode node = new MeshNode("GATEWAY", "SM-E066B", transport, new MessageLog());
        ManualClock clock = new ManualClock();
        node.setRebroadcastWindow(new RebroadcastWindow(
                RebroadcastWindow.DEFAULT_WINDOW_MS, clock, 256));

        assertTrue(node.ingestFromInternet("server-push-200", "ROUTE:Hall2"));
        node.cancelInternetRebroadcast("server-push-200");

        clock.advanceMs(1000);
        node.rebroadcastTickForTest();
        assertEquals("no sends after explicit cancel", 1, transport.out.size());
    }

    @Test
    public void rebroadcastsLandOnTheIntervalCadence() {
        StubTransport transport = new StubTransport();
        MeshNode node = new MeshNode("GATEWAY", "SM-E066B", transport, new MessageLog());
        ManualClock clock = new ManualClock();
        node.setRebroadcastWindow(new RebroadcastWindow(10_000, clock, 256));

        node.ingestFromInternet("cadence-check", "ROUTE:Hall2");
        int afterInitial = transport.out.size();
        assertEquals(1, afterInitial);

        // Halfway into the first interval: still silent.
        clock.advanceMs(1000);
        node.rebroadcastTickForTest();
        assertEquals("no send before the interval elapses", 1, transport.out.size());

        // At the interval boundary: the second send goes out.
        clock.advanceMs(1000);
        node.rebroadcastTickForTest();
        assertEquals(2, transport.out.size());
    }

    @Test
    public void oversizeIngestIsFragmentedWithDeterministicIds() {
        // A server push longer than one advert is SPLIT into framed packets
        // (auto-fragmentation) — two phones ingesting the same event derive
        // the same frame ids, so the flood still collapses to one copy.
        StubTransport transport = new StubTransport();
        MessageLog log = new MessageLog();
        MeshNode node = new MeshNode("GATEWAY", "SM-E066B", transport, log);
        node.setFragmentIntervalForTest(0); // synchronous frame flush
        String payload = new String(new char[PacketCodec.MAX_PAYLOAD + 7]).replace('\0', 'x');
        // 9-char text budget per frame after the 5-char frame prefix.
        int expectedFrames = (payload.length() + 8) / 9;

        assertTrue(node.ingestFromInternet("evt-big", payload));
        assertEquals(expectedFrames, transport.out.size());
        for (MeshMessage frame : transport.out) {
            assertTrue("frame fits the wire",
                    frame.getPayload().length() <= PacketCodec.MAX_PAYLOAD);
            assertEquals(MeshKind.ANNOUNCE, frame.getKind());
            assertTrue(PacketCodec.isFragment(frame.getPayload()));
        }
        // Deterministic ids: a second phone ingesting the SAME event id
        // derives identical frame ids (so the dedup collapses the copies).
        StubTransport transport2 = new StubTransport();
        MeshNode node2 = new MeshNode("ONLINE", "SM-M476B", transport2, new MessageLog());
        node2.setFragmentIntervalForTest(0); // synchronous frame flush
        assertTrue(node2.ingestFromInternet("evt-big", payload));
        assertEquals(expectedFrames, transport2.out.size());
        for (int i = 0; i < expectedFrames; i++) {
            assertEquals("same event -> same frame id",
                    transport.out.get(i).getMsgId(), transport2.out.get(i).getMsgId());
            assertEquals("same event -> same frame text",
                    transport.out.get(i).getPayload(), transport2.out.get(i).getPayload());
        }
        boolean foundFragmenting = false;
        for (MeshLogEntry entry : log.getEntries()) {
            foundFragmenting |= entry.getPayload().startsWith("fragmenting:");
        }
        assertTrue("the split is visible in the console", foundFragmenting);
    }

    @Test
    public void nullPayloadIngestIsRejected() {
        StubTransport transport = new StubTransport();
        MeshNode node = new MeshNode("GATEWAY", "SM-E066B", transport, new MessageLog());
        assertFalse(node.ingestFromInternet("evt-null", null));
        assertEquals(0, transport.out.size());
    }

    /** Deterministic clock for MeshNode-level ingest tests. */
    private static class ManualClock implements RebroadcastWindow.Clock {
        private long nowMs = 0;

        @Override
        public long nowMs() {
            return nowMs;
        }

        void advanceMs(long ms) {
            nowMs += ms;
        }
    }

    /** Copy of a flooded message as a neighbor would relay it onward. */
    private static MeshMessage relayedCopy(MeshMessage message) {
        return new MeshMessage(message.getMsgId(), message.getSrcId(), message.getDstId(),
                message.getKind(), message.getPayload(),
                message.getTtl() - 1, message.getHops() + 1,
                message.getCreatedAtEpochMs());
    }
}
