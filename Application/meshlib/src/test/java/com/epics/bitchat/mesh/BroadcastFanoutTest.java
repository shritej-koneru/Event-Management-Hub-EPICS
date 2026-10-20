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
import static org.junit.Assert.assertTrue;

/**
 * One-to-many sharing over the mesh (the "A and B also send to their nearby
 * devices" scenario): a broadcast floods to every phone in range, each
 * receiving phone relays it ONCE, every further copy of the same message id
 * is ignored, and a phone that pushed the same event from its own internet
 * feed does not double-flood the mesh. Runs on a deterministic in-process
 * radio — no sleeps, no real Bluetooth.
 */
public class BroadcastFanoutTest {

    // --- deterministic mini-mesh ----------------------------------------------

    /** Offline transport capturing what a node transmits. */
    private static class StubTransport implements MeshTransport {
        final List<MeshMessage> out = new ArrayList<>();
        /** Cumulative transmission count — out is drained by the radio. */
        int sentCount = 0;
        /** Every transmitted msg id, cumulative (out is drained by the radio). */
        final List<String> transmittedIds = new ArrayList<>();

        @Override public void start() {
        }

        @Override public void stop() {
        }

        @Override public boolean isRunning() {
            return false;
        }

        @Override public void send(MeshMessage message) {
            out.add(message);
            sentCount++;
            transmittedIds.add(message.getMsgId());
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

    /**
     * N MeshNodes on a synchronous "radio": each {@link #step()} delivers
     * every queued transmission to the linked neighbors exactly once. With
     * no links declared the radio is fully connected (everyone hears
     * everyone); explicit links build multi-hop topologies.
     */
    private static class MiniMesh {
        final Map<String, MeshNode> nodes = new LinkedHashMap<>();
        final Map<String, StubTransport> transports = new LinkedHashMap<>();
        final Map<String, List<MeshMessage>> outboxes = new LinkedHashMap<>();
        final Map<String, Integer> dupDrops = new HashMap<>();
        final Map<String, Set<String>> links = new HashMap<>();

        MiniMesh(String... names) {
            for (String name : names) {
                StubTransport transport = new StubTransport();
                MessageLog log = new MessageLog();
                MeshNode node = new MeshNode(name, name, transport, log);
                final String id = name;
                node.addListener(new MeshEventListener() {
                    @Override public void onLogEntry(MeshLogEntry entry) {
                        if (entry.getStatus() == MeshStatus.DROPPED_DUP) {
                            dupDrops.merge(id, 1, Integer::sum);
                        }
                    }

                    @Override public void onPeerDiscovered(Peer peer) {
                    }

                    @Override public void onPeerLost(Peer peer) {
                    }

                    @Override public void onMessageDelivered(MeshMessage message) {
                    }

                    @Override public void onPositionUpdated(String graphNodeId) {
                    }
                });
                nodes.put(name, node);
                transports.put(name, transport);
                outboxes.put(name, transport.out);
            }
        }

        /** Declare a bidirectional radio link (limits who hears whom). */
        void link(String a, String b) {
            links.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            links.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        /** Deliver every queued transmission to the linked neighbors. */
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

        int totalTransmissions() {
            int total = 0;
            for (StubTransport transport : transports.values()) {
                total += transport.sentCount;
            }
            return total;
        }

        int deliveredRows(String nodeId) {
            int count = 0;
            for (MeshLogEntry entry : nodes.get(nodeId).getLog().getEntries()) {
                if (entry.getStatus() == MeshStatus.DELIVERED) {
                    count++;
                }
            }
            return count;
        }
    }

    // --- tests -----------------------------------------------------------------

    @Test
    public void broadcastReachesEveryNodeExactlyOnce() {
        MiniMesh mesh = new MiniMesh("SRC", "A", "B");

        // Broadcast primitive against a stopped stack (established test seam,
        // same onLocalSend + transport path as MeshNode.broadcast).
        mesh.nodes.get("SRC").sendVenueAdvert("Hall 2");
        mesh.step(); // SRC's flood goes out; A and B relay it
        mesh.step(); // relay copies criss-cross; duplicates ignored
        mesh.step(); // nothing new may leave the mesh

        assertEquals("flood settles: exactly one transmission per phone",
                3, mesh.totalTransmissions());
        assertEquals(1, mesh.deliveredRows("A"));
        assertEquals(1, mesh.deliveredRows("B"));
        assertEquals("each relay-first receiver logs one dup row",
                Integer.valueOf(1), mesh.dupDrops.get("A"));
        assertEquals(Integer.valueOf(1), mesh.dupDrops.get("B"));
    }

    @Test
    public void broadcastFansOutToFiveDevicesAtOnce() {
        MiniMesh mesh = new MiniMesh("SRC", "R1", "R2", "R3", "R4", "R5");

        mesh.nodes.get("SRC").sendVenueAdvert("Hall 2");
        mesh.step();
        mesh.step();
        mesh.step();

        assertEquals("one flood transmission per phone, no storm",
                6, mesh.totalTransmissions());
        for (String receiver : new String[]{"R1", "R2", "R3", "R4", "R5"}) {
            assertEquals(receiver + " got the broadcast",
                    1, mesh.deliveredRows(receiver));
            assertEquals(receiver + " relayed exactly once",
                    1, mesh.transports.get(receiver).sentCount);
        }
    }

    @Test
    public void broadcastPropagatesMultiHopAcrossALineTopology() {
        MiniMesh mesh = new MiniMesh("S", "M", "E");
        mesh.link("S", "M");
        mesh.link("M", "E"); // E is out of S's direct radio range

        mesh.nodes.get("S").sendVenueAdvert("Cafe");
        mesh.step(); // S → M
        mesh.step(); // M → E
        mesh.step(); // settled

        assertEquals("end of the line still received it (1 hop)",
                1, mesh.deliveredRows("E"));
        assertEquals("flood ends after covering the line",
                3, mesh.totalTransmissions());
        assertEquals("M relayed S's message onward with hops=1",
                1, mesh.deliveredRows("M"));
    }

    @Test
    public void twoInternetIngestsOfSameEventFloodOneIdOnly() {
        MiniMesh mesh = new MiniMesh("SRC", "ONLINE", "R");
        // Both phones receive the same server event over the internet and
        // both push it into the mesh: identical wire ids must collapse into
        // a single flood, and the receivers must not relay it twice.
        String externalId = "venue-change-4711";
        assertTrue(mesh.nodes.get("SRC").ingestFromInternet(externalId, "ROUTE:Hall2"));
        assertTrue(mesh.nodes.get("ONLINE").ingestFromInternet(externalId, "ROUTE:Hall2"));

        mesh.step();
        mesh.step();
        mesh.step();

        assertEquals("two sources, one id: SRC + ONLINE + one R relay",
                3, mesh.totalTransmissions());
        assertEquals("R applied the event exactly once",
                1, mesh.deliveredRows("R"));
        assertEquals("R ignored the second copy of the same id",
                Integer.valueOf(1), mesh.dupDrops.get("R"));
        assertEquals(MeshNode.wireMsgIdFor(externalId),
                mesh.transports.get("SRC").transmittedIds.get(0));
        assertEquals(MeshNode.wireMsgIdFor(externalId),
                mesh.transports.get("ONLINE").transmittedIds.get(0));
    }
}
