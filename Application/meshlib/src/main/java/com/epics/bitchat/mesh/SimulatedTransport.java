package com.epics.bitchat.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process radio that simulates a multi-node BLE mesh. Each simulated node
 * runs its own {@link Router} (except the local node, which is driven by the
 * app's {@link MeshNode}); all nodes write to a single shared
 * {@link MessageLog} so the console shows the whole network story:
 * TX, multi-hop RX, relays, dedup drops, TTL expiry and lossy links.
 */
public class SimulatedTransport implements MeshTransport {

    public enum Topology { RING, STAR, MESH }

    public static class Config {
        public int nodeCount = 6;
        public double packetLossPct = 0.10;
        public long latencyMs = 40;
        public Topology topology = Topology.MESH;
        public long seed = System.currentTimeMillis();
        public boolean demoTraffic = true;
        public int beaconCount = 3;
        /**
         * Venue names used to label the beacon nodes — and as their wire ids,
         * matching the real-beacon advert contract so the position estimator
         * resolves them on the navigation graph.
         */
        public List<String> beaconNames = new ArrayList<>(
                java.util.Arrays.asList("Hall 1", "Hall 2", "Cafeteria"));
    }

    private static final double RANGE = 30.0;
    private static final double SPACING = 16.0;
    private static final double RSSI_AT_1M = -41.0;
    private static final double RSSI_EXPONENT = 3.0;

    private static final class SimNode {
        final String id;
        final String displayName;
        final boolean isBeacon;
        final double x;
        final double y;
        Router router;

        SimNode(String id, String displayName, boolean isBeacon, double x, double y, Router router) {
            this.id = id;
            this.displayName = displayName;
            this.isBeacon = isBeacon;
            this.x = x;
            this.y = y;
            this.router = router;
        }
    }

    private final Config config;
    private final MessageLog log;
    private final String localNodeId;
    private final List<SimNode> nodes = new ArrayList<>();
    private final Map<String, SimNode> nodesById = new HashMap<>();
    private final Map<String, Set<String>> adjacency = new HashMap<>();
    /** Unordered node pairs whose radio link is cut (TEST 6 link-loss staging). */
    private final Set<String> severedLinks = new HashSet<>();
    private final Random random;
    private final MeshNetwork network = new MeshNetwork();
    private final ExecutorService executor;
    private final ScheduledExecutorService demo;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    private volatile Listener listener;
    private volatile boolean running = false;

    public SimulatedTransport(Config config) {
        this.config = config;
        this.log = new MessageLog();
        this.localNodeId = "node_0";
        this.random = new Random(config.seed);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sim-radio");
            t.setDaemon(true);
            return t;
        });
        this.demo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-demo");
            t.setDaemon(true);
            return t;
        });
        buildNodes();
    }

    private void buildNodes() {
        int side = (int) Math.ceil(Math.sqrt(config.nodeCount));
        for (int i = 0; i < config.nodeCount; i++) {
            boolean isBeacon = i > 0 && i <= config.beaconCount;
            int beaconIdx = i - 1;
            // Beacon nodes carry the venue name as their wire id — the same
            // contract as real beacon adverts — so the position estimator can
            // resolve them against the navigation graph.
            String id = isBeacon && beaconIdx < config.beaconNames.size()
                    ? config.beaconNames.get(beaconIdx)
                    : "node_" + i;
            String name;
            if (i == 0) {
                name = "Me (sim)";
            } else if (isBeacon) {
                name = beaconIdx < config.beaconNames.size()
                        ? config.beaconNames.get(beaconIdx) + " Beacon"
                        : "Beacon " + i;
            } else {
                name = "Node " + i;
            }
            double x = (i % side) * SPACING;
            double y = (i / side) * SPACING;
            nodes.add(new SimNode(id, name, isBeacon, x, y, null));
        }
        for (SimNode node : nodes) {
            nodesById.put(node.id, node);
        }
        for (SimNode node : nodes) {
            adjacency.put(node.id, new LinkedHashSet<>());
        }
        buildTopology();
        for (SimNode node : nodes) {
            if (!node.id.equals(localNodeId)) {
                node.router = new Router(node.id, log, new Router.Sink() {
                    @Override
                    public void onDelivered(MeshMessage message, int rssi) {
                        // arrival already logged by the router
                        // Simulated phones behave like real ones: they answer
                        // CHAT with an ACK so the sender's delivery tracking
                        // (send → deliver → ACK) completes end to end.
                        if (message.getKind() == MeshKind.CHAT) {
                            MeshMessage ack = MeshMessage.create(node.id, message.getSrcId(),
                                    MeshKind.ACK, PacketCodec.ackToken(message));
                            broadcastFrom(node.id, ack);
                        }
                    }

                    @Override
                    public void onRelay(MeshMessage relayed, int rssi) {
                        broadcastFrom(node.id, relayed);
                    }
                });
            }
        }
    }

    private void buildTopology() {
        switch (config.topology) {
            case RING:
                for (int i = 0; i < nodes.size(); i++) {
                    link(i, (i + 1) % nodes.size());
                }
                break;
            case STAR:
                for (int i = 1; i < nodes.size(); i++) {
                    link(0, i);
                }
                break;
            case MESH:
            default:
                for (int i = 0; i < nodes.size(); i++) {
                    boolean connected = false;
                    for (int j = 0; j < nodes.size(); j++) {
                        if (i == j) {
                            continue;
                        }
                        if (dist(nodes.get(i), nodes.get(j)) <= RANGE) {
                            link(i, j);
                            connected = true;
                        }
                    }
                    if (!connected && i > 0) {
                        link(i, i - 1);
                    }
                }
                break;
        }
    }

    private void link(int i, int j) {
        adjacency.get(nodes.get(i).id).add(nodes.get(j).id);
        adjacency.get(nodes.get(j).id).add(nodes.get(i).id);
    }

    private static double dist(SimNode a, SimNode b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private Set<String> inRangeOf(String nodeId) {
        return new LinkedHashSet<>(adjacency.get(nodeId));
    }

    private int rssiBetween(String fromId, String toId) {
        SimNode a = nodesById.get(fromId);
        SimNode b = nodesById.get(toId);
        double d = Math.max(1.0, dist(a, b));
        double rssi = RSSI_AT_1M - 10 * RSSI_EXPONENT * Math.log10(d);
        return (int) Math.round(rssi + (random.nextGaussian() * 2.0));
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Listener l = listener;
        if (l != null) {
            l.onTransportStarted();
        }
        if (config.demoTraffic) {
            demo.scheduleWithFixedDelay(this::demoTick, 500, 2500, TimeUnit.MILLISECONDS);
        }
    }

    private void demoTick() {
        List<String> targets = new ArrayList<>();
        for (SimNode node : nodes) {
            if (!node.id.equals(localNodeId)) {
                targets.add(node.id);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        String target = targets.get(random.nextInt(targets.size()));
        MeshMessage msg = MeshMessage.create(localNodeId, target,
                random.nextBoolean() ? MeshKind.CHAT : MeshKind.PING,
                demoPayload());
        send(msg);
    }

    private String demoPayload() {
        String[] payloads = {"hi", "where are you?", "meet at Hall 2", "ping", "on my way", "see you soon"};
        return payloads[random.nextInt(payloads.length)];
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        demo.shutdownNow();
        executor.shutdownNow();
        Listener l = listener;
        if (l != null) {
            l.onTransportStopped();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void send(MeshMessage message) {
        if (!running) {
            return;
        }
        broadcastFrom(localNodeId, message);
    }

    private void broadcastFrom(String sourceId, MeshMessage message) {
        if (!running) {
            return;
        }
        for (String neighborId : inRangeOf(sourceId)) {
            if (message.getSrcId().equals(neighborId)) {
                continue;
            }
            if (isLinkSevered(sourceId, neighborId)) {
                log.addEntry(new MeshLogEntry(
                        Direction.RX, sourceId, neighborId, message.getPayload(),
                        message.getKind(), message.getHops(), rssiBetween(sourceId, neighborId),
                        sourceId, MeshStatus.DROPPED_LOSS, message.getMsgId()));
                continue;
            }
            if (random.nextDouble() < config.packetLossPct) {
                log.addEntry(new MeshLogEntry(
                        Direction.RX, sourceId, neighborId, message.getPayload(),
                        message.getKind(), message.getHops(), rssiBetween(sourceId, neighborId),
                        sourceId, MeshStatus.DROPPED_LOSS, message.getMsgId()));
                continue;
            }
            inFlight.incrementAndGet();
            final String from = sourceId;
            final int rssi = rssiBetween(sourceId, neighborId);
            final SimNode neighbor = nodesById.get(neighborId);
            final MeshMessage toDeliver = message;
            executor.submit(() -> {
                try {
                    if (running) {
                        if (neighbor.id.equals(localNodeId)) {
                            Listener l = listener;
                            if (l != null) {
                                l.onPacketReceived(toDeliver, rssi, from);
                            }
                        } else if (neighbor.router != null) {
                            neighbor.router.onReceive(toDeliver, rssi, from);
                        }
                    }
                } finally {
                    inFlight.decrementAndGet();
                    synchronized (inFlight) {
                        inFlight.notifyAll();
                    }
                }
            });
        }
    }

    @Override
    public String getLocalNodeId() {
        return localNodeId;
    }

    @Override
    public List<Peer> getPeers() {
        network.clear();
        for (String neighborId : inRangeOf(localNodeId)) {
            if (isLinkSevered(localNodeId, neighborId)) {
                continue; // cut link: the phone "left range"
            }
            SimNode neighbor = nodesById.get(neighborId);
            Peer peer = new Peer(neighbor.id, neighbor.displayName, "sim:" + neighbor.id, neighbor.isBeacon);
            peer.setRssi(network.smooth(neighbor.id, rssiBetween(localNodeId, neighborId)));
            network.upsert(peer);
        }
        return network.getAll();
    }

    /** Canonical key for an unordered node pair. */
    private static String linkKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * Cut or restore the simulated radio link between any two nodes — the
     * per-peer "simulate link loss" knob for TEST 6: it makes "relayed 0"
     * distinguishable from "relay disabled", and cutting local↔X stages
     * "X walked out of range" while cutting X↔Y stages a true multi-hop path.
     */
    public synchronized void setLinkSevered(String a, String b, boolean severed) {
        if (severed) {
            severedLinks.add(linkKey(a, b));
        } else {
            severedLinks.remove(linkKey(a, b));
        }
    }

    /** True when the direct radio link between the two sim nodes is cut. */
    public synchronized boolean isLinkSevered(String a, String b) {
        return severedLinks.contains(linkKey(a, b));
    }

    /** Ids of all simulated nodes except the local one (link-toggle UI). */
    public List<String> getSimNodeIds() {
        List<String> ids = new ArrayList<>();
        for (SimNode node : nodes) {
            if (!node.id.equals(localNodeId)) {
                ids.add(node.id);
            }
        }
        return ids;
    }

    public MessageLog getLog() {
        return log;
    }

    /** Node ids in the whole simulated network (used by the Send destination picker). */
    public List<String> getReachableNodeIds() {
        List<String> result = new ArrayList<>();
        for (SimNode node : nodes) {
            if (!node.id.equals(localNodeId)) {
                result.add(node.id);
            }
        }
        return result;
    }

    public String displayNameFor(String nodeId) {
        SimNode node = nodesById.get(nodeId);
        return node == null ? nodeId : node.displayName;
    }

    /** Wire ids of the simulated beacon nodes (venue names — graph contract). */
    public List<String> getBeaconNodeIds() {
        List<String> ids = new ArrayList<>();
        for (SimNode node : nodes) {
            if (node.isBeacon) {
                ids.add(node.id);
            }
        }
        return ids;
    }

    /** Wire ids of the simulated phone nodes ("node_N"). */
    public List<String> getPhoneNodeIds() {
        List<String> ids = new ArrayList<>();
        for (SimNode node : nodes) {
            if (!node.isBeacon && !node.id.equals(localNodeId)) {
                ids.add(node.id);
            }
        }
        return ids;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Block until all in-flight simulated deliveries have settled. */
    public void awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (inFlight) {
            while (inFlight.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return;
                }
                inFlight.wait(Math.min(remaining, 50));
            }
        }
    }
}
