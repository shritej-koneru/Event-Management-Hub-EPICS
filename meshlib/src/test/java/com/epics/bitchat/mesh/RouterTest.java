package com.epics.bitchat.mesh;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RouterTest {

    private MessageLog log;
    private List<MeshMessage> delivered;
    private List<MeshMessage> relayed;

    @Before
    public void setUp() {
        log = new MessageLog();
        delivered = new ArrayList<>();
        relayed = new ArrayList<>();
    }

    private Router router(String nodeId) {
        return new Router(nodeId, log, new Router.Sink() {
            @Override
            public void onDelivered(MeshMessage message, int rssi) {
                delivered.add(message);
            }

            @Override
            public void onRelay(MeshMessage message, int rssi) {
                relayed.add(message);
            }
        });
    }

    @Test
    public void deliversWhenThisNodeIsDestination() {
        Router router = router("B");
        MeshMessage msg = MeshMessage.create("A", "B", MeshKind.CHAT, "hello");
        router.onReceive(msg, -60, "A");

        assertEquals(1, delivered.size());
        assertEquals("hello", delivered.get(0).getPayload());
        assertTrue(hasStatus(MeshStatus.DELIVERED, "B"));
    }

    @Test
    public void relaysWithDecrementedTtlAndIncrementedHops() {
        Router router = router("B");
        MeshMessage msg = MeshMessage.create("A", "C", MeshKind.CHAT, "hi", 4);
        router.onReceive(msg, -60, "A");

        assertEquals(1, relayed.size());
        assertEquals(3, relayed.get(0).getTtl());
        assertEquals(1, relayed.get(0).getHops());
        assertTrue(hasStatus(MeshStatus.RELAYED, "B"));
    }

    @Test
    public void dropsWhenTtlExpired() {
        Router router = router("B");
        MeshMessage msg = MeshMessage.create("A", "C", MeshKind.CHAT, "hi", 0);
        router.onReceive(msg, -60, "A");

        assertEquals(0, relayed.size());
        assertTrue(hasStatus(MeshStatus.DROPPED_TTL, "B"));
    }

    @Test
    public void dropsDuplicateAndNeverRelaysTwice() {
        Router router = router("B");
        MeshMessage msg = MeshMessage.create("A", "C", MeshKind.CHAT, "hi", 3);
        router.onReceive(msg, -60, "A");
        router.onReceive(msg, -62, "A");

        assertEquals(1, relayed.size());
        assertTrue(hasStatus(MeshStatus.DROPPED_DUP, "B"));
    }

    @Test
    public void relayedCopyKeepsMessageId() {
        Router router = router("B");
        MeshMessage msg = MeshMessage.create("A", "C", MeshKind.CHAT, "hi", 3);
        router.onReceive(msg, -60, "A");

        assertEquals(msg.getMsgId(), relayed.get(0).getMsgId());
    }

    @Test
    public void logsDuplicateDropOnlyOncePerMessageId() {
        Router router = router("B");
        MeshMessage msg = MeshMessage.create("A", "C", MeshKind.CHAT, "hi", 3);
        router.onReceive(msg, -60, "A");
        for (int i = 0; i < 16; i++) {
            router.onReceive(msg, -61, "A");
        }

        assertEquals(1, relayed.size());
        int dupCount = 0;
        for (MeshLogEntry entry : log.getEntries()) {
            if (entry.getStatus() == MeshStatus.DROPPED_DUP && entry.getRelayBy().equals("B")) {
                dupCount++;
            }
        }
        assertEquals(1, dupCount);
    }

    @Test
    public void messageHopsThroughRelayToFinalDestination() {
        Router a = router("A");
        Router b = router("B");
        Router c = router("C");

        MeshMessage msg = MeshMessage.create("A", "C", MeshKind.CHAT, "hey");
        a.onLocalSend(msg);

        b.onReceive(msg, -70, "A");
        assertEquals(1, relayed.size());
        MeshMessage relayedCopy = relayed.get(0);
        assertEquals("C", relayedCopy.getDstId());
        assertEquals(1, relayedCopy.getHops());
        assertEquals(Router.DEFAULT_TTL - 1, relayedCopy.getTtl());

        relayed.clear();
        c.onReceive(relayedCopy, -71, "B");
        assertEquals(0, relayed.size());
        assertEquals(1, delivered.size());
        assertEquals("hey", delivered.get(0).getPayload());
        assertEquals(1, delivered.get(0).getHops());
        assertTrue(hasStatus(MeshStatus.DELIVERED, "C"));

        c.onReceive(relayedCopy, -72, "B");
        int dupCount = 0;
        for (MeshLogEntry entry : log.getEntries()) {
            if (entry.getStatus() == MeshStatus.DROPPED_DUP && entry.getRelayBy().equals("C")) {
                dupCount++;
            }
        }
        assertEquals(1, dupCount);
    }

    private boolean hasStatus(MeshStatus status, String nodeId) {
        for (MeshLogEntry entry : log.getEntries()) {
            if (entry.getStatus() == status && entry.getRelayBy().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }
}
