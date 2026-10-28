package com.epics.bitchat.mesh;

import com.epics.bitchat.nav.DemoGraphFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The app-facing mesh stack for the local device. Owns the transport, the
 * flood-relay {@link Router}, the peer table and the traffic log, and pushes
 * everything to {@link MeshEventListener}s. Works with either the
 * {@link SimulatedTransport} (demos/tests) or a real BLE transport.
 */
public class MeshNode implements MeshTransport.Listener {

    /** Period between "I am at <venue>" BEACON adverts (mesh navigation). */
    public static final long VENUE_ADVERT_PERIOD_MS = 15_000L;

    /** How often the rebroadcast window is serviced (internet ingest). */
    static final long REBROADCAST_TICK_MS = 500L;

    /**
     * Gap between consecutive fragment frames of one long message. BLE
     * adverts from one phone issued back-to-back can overflow the receiver's
     * scan buffer — spacing them out keeps multi-frame texts lossless.
     */
    public static final long FRAGMENT_INTERVAL_MS = 400L;

    /** How often auto-announce re-evaluates the measured position. */
    public static final long AUTO_ANNOUNCE_PERIOD_MS = 5_000L;

    private final String nodeId;
    private final String displayName;
    private final MeshTransport transport;
    private final Router router;
    private final MeshNetwork network;
    private final MessageLog log;
    private final DeliveryTracker deliveryTracker = new DeliveryTracker();
    private final VenueDirectory venueDirectory = new VenueDirectory();
    /** Reassembles framed fragment groups on the receive side. */
    private final FragmentAssembler fragmentAssembler;
    /** Frames of long local messages not yet sent (staggered flush). */
    private final java.util.Deque<MeshMessage> pendingFragments = new java.util.ArrayDeque<>();
    /** Destinations ("" = broadcast) the pending fragment stream belongs to. */
    private volatile String pendingFragmentDst = "";
    /** Recently sent fragment groups, kept so a NACK can re-send them. */
    private final java.util.LinkedHashMap<String, CachedGroup> fragmentCache = new java.util.LinkedHashMap<>();
    /** Gap actually used between fragment flushes (test seam). */
    private volatile long fragmentIntervalMs = FRAGMENT_INTERVAL_MS;
    private volatile RebroadcastWindow rebroadcastWindow = new RebroadcastWindow();
    /** Payloads inside their 5 s rebroadcast window (wire msg id → message). */
    private final Map<String, MeshMessage> ingestedMessages = new ConcurrentHashMap<>();
    private final List<MeshEventListener> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService maintenance;
    private volatile PositionEstimator positionEstimator;
    private volatile boolean running = false;
    private volatile String lastPositionNodeId;
    private volatile AnchorSource lastPositionSource = AnchorSource.NONE;
    private volatile String advertisedVenue = "";

    // --- auto-announce: every phone becomes a navigation beacon ---------------

    /**
     * When enabled, the estimated position is announced automatically — but
     * only when it is <b>beacon-backed</b> (physically measured). Advert-only
     * positions are mesh hearsay and are never re-announced: rebroadcasting
     * hearsay would reset the hop count on every leg and the whole mesh would
     * converge on one venue (an echo chamber). The original flood already
     * propagates venue knowledge multi-hop with proper hop decay. A manual
     * pin (Settings/Hop screen) always takes precedence and may be anywhere,
     * hearsay included — the user is the highest authority.
     */
    private volatile boolean autoAnnounceEnabled = false;
    /** Manual pin wins over auto-announce until cleared (""). */
    private volatile String manualVenueOverride = "";
    private volatile String lastAutoAnnouncedVenue = "";

    /** How long sent fragment frames stay eligible for NACK re-send. */
    static final long FRAGMENT_CACHE_TTL_MS = 60_000L;
    /** Cap on concurrently cached fragment groups. */
    private static final int FRAGMENT_CACHE_MAX = 16;

    /** One cached fragment group: enough to re-send any requested frame. */
    private static final class CachedGroup {
        final String dstId;
        final MeshKind kind;
        final java.util.List<String> framePayloads;
        final long createdAtMs;

        CachedGroup(String dstId, MeshKind kind, java.util.List<String> framePayloads,
                    long createdAtMs) {
            this.dstId = dstId;
            this.kind = kind;
            this.framePayloads = framePayloads;
            this.createdAtMs = createdAtMs;
        }
    }

    public MeshNode(String nodeId, String displayName, MeshTransport transport, MessageLog log) {
        this.nodeId = nodeId;
        this.displayName = displayName;
        this.transport = transport;
        this.log = log;
        this.network = new MeshNetwork();
        this.router = new Router(nodeId, log, new Router.Sink() {
            @Override
            public void onDelivered(MeshMessage message, int rssi) {
                if (message.getKind() == MeshKind.NACK) {
                    // A receiver is missing frames of a group we recently
                    // sent — re-flood exactly those frames (Meshtastic-style
                    // explicit retransmission).
                    resendMissingFrames(message);
                    return;
                }
                if (message.getKind() == MeshKind.BEACON
                        && VenueDirectory.isVenueAdvert(message.getPayload())) {
                    String venue = VenueDirectory.venueOf(message.getPayload());
                    if (venueDirectory.observe(venue, message.getSrcId(), message.getHops())) {
                        // New venue knowledge is also positioning input: the
                        // announcing phone anchors us near that venue.
                        updatePosition();
                        fireVenueDirectoryChanged();
                    }
                    return; // venue adverts need no ACK
                }
                if (message.getKind() == MeshKind.ACK) {
                    // The matched token travels in the ACK's PAYLOAD (see
                    // sendAck) — NOT in the ACK's own wire id, which is
                    // randomly minted per ACK. Only report a delivery when
                    // the token actually completes a pending send (#16).
                    String token = message.getPayload();
                    boolean matched = token != null
                            && deliveryTracker.ack(token, System.currentTimeMillis());
                    if (matched) {
                        fireDeliveryStatus(token, message.getSrcId(), true);
                    }
                    return;
                }
                if (PacketCodec.isFragment(message.getPayload())) {
                    // A fragment frame is reassembly bookkeeping — never push
                    // partial text to the app, and never ACK it per frame: the
                    // assembler ACKs the GROUP token once the complete text
                    // reassembles, which is what "delivered" should mean.
                    return;
                }
                fireDelivered(message);
                if (message.getKind() == MeshKind.CHAT) {
                    sendAck(message);
                }
            }

            @Override
            public void onRelay(MeshMessage relayed, int rssi) {
                transport.send(relayed);
            }
        });
        this.maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-maintenance");
            t.setDaemon(true);
            return t;
        });
        // Fan every traffic-log row out to listeners — without this the app's
        // Console tab (MeshManager.onLogEntry → ViewModel) never sees anything.
        log.addListener(entry -> {
            for (MeshEventListener listener : listeners) {
                listener.onLogEntry(entry);
            }
        });
        this.fragmentAssembler = new FragmentAssembler(new FragmentAssembler.Callback() {
            @Override
            public void onTextAssembled(String sender, String groupId, MeshKind kind,
                                        String text) {
                // Console row for the COMPLETE text — the fragment frames above
                // it show the individual packets, this one the real message.
                log.addEntry(new MeshLogEntry(
                        Direction.RX, sender, nodeId, text, kind,
                        0, 0, nodeId, MeshStatus.DELIVERED, groupId));
                if (kind == MeshKind.CHAT) {
                    // Group-level ACK: "delivered" means the COMPLETE text
                    // reassembled here — not merely that one frame landed.
                    sendAck(new MeshMessage(groupId, sender, nodeId, kind, text,
                            Router.DEFAULT_TTL, 0, System.currentTimeMillis()));
                }
                fireDelivered(new MeshMessage(groupId, sender, nodeId, kind,
                        text, 0, 0, System.currentTimeMillis()));
            }

            @Override
            public void onGroupExpired(String sender, String groupId,
                                       int received, int total) {
                log.addEntry(new MeshLogEntry(
                        Direction.RX, sender, nodeId,
                        "announcement incomplete: " + received + "/" + total
                                + " packets - text dropped",
                        MeshKind.CHAT, 0, 0, nodeId, MeshStatus.DROPPED_LOSS, groupId));
            }

            @Override
            public void onGroupIncomplete(String sender, String groupId,
                                          java.util.List<Integer> missing,
                                          int received, int total) {
                if (!running) {
                    return;
                }
                long mask = 0;
                for (int index : missing) {
                    if (index >= 1 && index <= PacketCodec.NACK_MASK_BITS) {
                        mask |= 1L << (index - 1);
                    }
                }
                String payload = PacketCodec.makeNackPayload(groupId, mask);
                if (payload == null) {
                    return;
                }
                MeshMessage nack = MeshMessage.create(nodeId, sender, MeshKind.NACK, payload);
                router.onLocalSend(nack);
                transport.send(nack);
                log.addEntry(new MeshLogEntry(
                        Direction.TX, nodeId, sender,
                        "missing " + missing.size() + "/" + total
                                + " packets - requesting re-send",
                        MeshKind.NACK, 0, 0, nodeId, MeshStatus.IN_FLIGHT, groupId));
            }
        });
        transport.setListener(this);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        transport.start();
        refreshPeers();
        maintenance.scheduleWithFixedDelay(this::refreshPeers, 2000, 2000, TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(this::sweepDeliveries, 2000, 2000, TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(this::nackSweepTick, 2000, 2000, TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(this::advertiseVenue,
                VENUE_ADVERT_PERIOD_MS, VENUE_ADVERT_PERIOD_MS, TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(this::autoAnnounceTick,
                AUTO_ANNOUNCE_PERIOD_MS, AUTO_ANNOUNCE_PERIOD_MS, TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(this::rebroadcastTick,
                REBROADCAST_TICK_MS, REBROADCAST_TICK_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        maintenance.shutdownNow();
        transport.stop();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Send a message into the mesh. Texts longer than one BLE advert are
     * AUTO-FRAGMENTED: split into ≤{@value PacketCodec#MAX_PAYLOAD}-char
     * framed chunks ({@link PacketCodec#fragment}) that are sent one after
     * the other ({@link #FRAGMENT_INTERVAL_MS} apart) and reassembled by
     * every receiver into the complete text. Returns false only when the
     * node is stopped or the text cannot even be framed (empty).*/
    public boolean send(String dstId, MeshKind kind, String payload) {
        if (!running) {
            return false;
        }
        if (payload != null && payload.length() > PacketCodec.MAX_PAYLOAD) {
            return sendFragments(dstId, kind, payload, Router.DEFAULT_TTL,
                    System.currentTimeMillis());
        }
        MeshMessage message = MeshMessage.create(nodeId, dstId, kind, payload);
        router.onLocalSend(message);
        transport.send(message);
        deliveryTracker.track(PacketCodec.ackToken(message), dstId, System.currentTimeMillis());
        return true;
    }

    /**
     * Broadcast a message to no specific destination with a custom TTL —
     * the flood-relay primitive that venue adverts use. Every receiving
     * phone relays it (users acting as nodes) until the TTL runs out.
     */
    public boolean broadcast(String payload, int ttl) {
        if (!running) {
            return false;
        }
        if (payload.length() > PacketCodec.MAX_PAYLOAD) {
            return sendFragments("", MeshKind.BEACON, payload, ttl,
                    System.currentTimeMillis());
        }
        MeshMessage message = MeshMessage.create(nodeId, "", MeshKind.BEACON, payload, ttl);
        router.onLocalSend(message);
        transport.send(message);
        return true;
    }

    /**
     * Ingest a message that arrived from OUTSIDE the mesh (internet
     * backhaul): a server push — a venue change, a route update, an alert —
     * is flooded to every nearby phone over BLE.
     *
     * <p>{@code externalId} is the server-side event id. It is mapped to a
     * stable wire id, so a phone that received the SAME server event over
     * its own internet connection derives the same id and the flood-router
     * dedup drops the second copy instead of re-flooding the mesh.
     *
     * <p>The message carries its id for {@link RebroadcastWindow#DEFAULT_WINDOW_MS}
     * (5 s): re-broadcasts at a fixed cadence cover phones that enter range
     * late or lost the first copy; after the window the flooding stops —
     * a message with no receivers nearby simply expires. Receiving phones
     * relay the first copy onward per the standard flood rules and ignore
     * every later copy of the same id.
     *
     * @return true when the flood started (or this id was already flooding)
     */
    public boolean ingestFromInternet(String externalId, String payload) {
        return ingestFromInternet(externalId, payload, Router.DEFAULT_TTL);
    }

    public boolean ingestFromInternet(String externalId, String payload, int ttl) {
        if (payload == null) {
            return false;
        }
        if (payload.length() > PacketCodec.MAX_PAYLOAD) {
            final String groupId = externalId == null || externalId.isEmpty()
                    ? UUID.randomUUID().toString()
                    : wireMsgIdFor(externalId);
            return sendFragmentsWithGroup("", MeshKind.ANNOUNCE, payload, ttl, groupId,
                    index -> wireMsgIdFor(groupId + "#" + index));
        }
        String msgId = externalId == null || externalId.isEmpty()
                ? UUID.randomUUID().toString()
                : wireMsgIdFor(externalId);
        MeshMessage message = MeshMessage.createWithId(
                msgId, nodeId, "", MeshKind.ANNOUNCE, payload, ttl);
        if (router.hasSeen(msgId)) {
            // Same event already flowed through this phone (a neighbor's
            // flood, or its own earlier ingest): ignore — do not re-flood.
            return true;
        }
        if (!rebroadcastWindow.begin(message.getMsgId())) {
            return true; // this id is already inside its window
        }
        ingestedMessages.put(message.getMsgId(), message);
        router.onLocalSend(message);
        transport.send(message);
        return true;
    }

    /**
     * Stop re-broadcasting an ingested id early (e.g. this phone's own
     * internet feed later delivered the same event and the app decided the
     * neighborhood has it). The mesh-side dedup is unaffected.
     */
    public void cancelInternetRebroadcast(String externalId) {
        if (externalId == null) {
            return;
        }
        String msgId = wireMsgIdFor(externalId);
        rebroadcastWindow.close(msgId);
        ingestedMessages.remove(msgId);
    }

    /** Service the rebroadcast windows of ingested messages. */
    private void rebroadcastTick() {
        for (Map.Entry<String, MeshMessage> entry : ingestedMessages.entrySet()) {
            String msgId = entry.getKey();
            if (rebroadcastWindow.shouldRebroadcast(msgId)) {
                MeshMessage message = entry.getValue();
                transport.send(message);
                log.addEntry(new MeshLogEntry(
                        Direction.TX, nodeId, message.getDstId(), message.getPayload(),
                        message.getKind(), 0, 0, nodeId, MeshStatus.RELAYED, msgId));
            } else if (!rebroadcastWindow.isOpen(msgId)) {
                ingestedMessages.remove(msgId);
            }
        }
    }

    /** True when this wire id already flowed through this node (send or
     *  receive) — lets callers distinguish "flooded" from "deduped" without
     *  racing the ingest return value. */
    public boolean hasSeenWireId(String wireId) {
        return router.hasSeen(wireId);
    }

    /**
     * Deterministic 3-char wire id for a server-side event id (FNV-1a over
     * the 32-char id alphabet) — every phone ingesting the same event
     * derives the same wire id, which is what makes the dedup work.
     */
    public static String wireMsgIdFor(String externalId) {
        int hash = 0x811c9dc5;
        for (byte b : externalId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(3);
        int h = hash;
        for (int i = 0; i < 3; i++) {
            sb.append(alphabet.charAt(Math.abs(h) % alphabet.length()));
            h /= alphabet.length();
            if (h == 0) {
                h = 0x811c9dc5 + i; // keep mixing for short hashes
            }
        }
        return sb.toString();
    }

    /** Announce this user's venue over the mesh (MeshKind.BEACON, "V:<venue>"). */
    public boolean announceVenue(String venue) {
        if (venue == null) {
            return false;
        }
        advertisedVenue = venue;
        if (venue.isEmpty()) {
            return true;
        }
        return broadcast(VenueDirectory.venuePrefix() + venue, Router.DEFAULT_TTL);
    }

    /** Enable/disable auto-announcing the measured position as a venue. */
    public void setAutoAnnounceEnabled(boolean enabled) {
        this.autoAnnounceEnabled = enabled;
        if (!enabled) {
            lastAutoAnnouncedVenue = "";
        }
    }

    public boolean isAutoAnnounceEnabled() {
        return autoAnnounceEnabled;
    }

    /**
     * Manual venue pin — announced verbatim and kept until cleared ("") or
     * superseded by another pin. While set it wins over auto-announce.
     */
    public void setAutoAnnounceOverride(String venue) {
        this.manualVenueOverride = venue == null ? "" : venue.trim();
    }

    /**
     * Auto-announce tick: announce the venue implied by the strongest
     * anchor, subject to three gates (see {@link #autoAnnounceEnabled}).
     */
    private void autoAnnounceTick() {
        if (!running) {
            return;
        }
        autoAnnounceNow();
    }

    /** One evaluation of the auto-announce gates; package-private for tests. */
    void autoAnnounceNow() {
        if (!autoAnnounceEnabled) {
            return;
        }
        // Gate 1: a manual pin outranks the automatic estimate.
        String pinned = manualVenueOverride;
        if (pinned != null && !pinned.isEmpty()) {
            if (!pinned.equals(advertisedVenue)) {
                advertisedVenue = pinned;
                sendVenueAdvert(pinned);
                lastAutoAnnouncedVenue = "";
            }
            return;
        }
        // Gate 2: announce only physically measured positions, never mesh
        // hearsay — otherwise relayed adverts would hop-reset and echo.
        // Uses the beacon-only view: loud hearsay cannot silence a phone
        // that knows where it is.
        PositionEstimator.Anchor anchor =
                positionEstimator == null ? null : positionEstimator.strongestBeaconAnchor();
        if (anchor == null) {
            return;
        }
        // Gate 3: transition nodes (stairs/elevator/ramp) are not venues —
        // they carry no useful navigation meaning for other users.
        if (DemoGraphFactory.isTransitionNode(anchor.nodeId)) {
            return;
        }
        // Announce on change only; staying put re-announces via the 15 s cycle.
        if (!anchor.nodeId.equals(lastAutoAnnouncedVenue)) {
            lastAutoAnnouncedVenue = anchor.nodeId;
            advertisedVenue = anchor.nodeId;
            sendVenueAdvert(anchor.nodeId);
        }
    }

    /**
     * Floods a venue advert. The running gate belongs to the callers (the
     * scheduled tick and {@link #broadcast}); tests invoke this directly
     * against a stopped stack.
     */
    void sendVenueAdvert(String venue) {
        MeshMessage message = MeshMessage.create(nodeId, "", MeshKind.BEACON,
                VenueDirectory.venuePrefix() + venue, Router.DEFAULT_TTL);
        if (!PacketCodec.fitsOnWire(message)) {
            // Venue names longer than the advert budget cannot propagate;
            // say so instead of crashing the encoder or shipping a mangled
            // name that no receiver can resolve on the graph.
            log.addEntry(new MeshLogEntry(
                    Direction.TX, nodeId, "",
                    "dropped: venue \"" + venue + "\" too long for one advert",
                    MeshKind.BEACON, 0, 0, nodeId, MeshStatus.DROPPED_TOO_LONG, "-"));
            return;
        }
        router.onLocalSend(message);
        transport.send(message);
    }

    /** The venue this device currently advertises ("" = none). */
    public String getAdvertisedVenue() {
        return advertisedVenue;
    }

    /** Where the last position estimate came from (see {@link AnchorSource}). */
    public AnchorSource getLastPositionSource() {
        return lastPositionSource;
    }

    /** Provenance of a position estimate: measured or heard over the mesh. */
    public enum AnchorSource {
        /** Strongest anchor is a direct BLE beacon (physically measured). */
        BEACON,
        /** Strongest anchor is a venue advert heard over the mesh. */
        MESH,
        /** No usable anchor in range. */
        NONE
    }

    /** Periodic re-announcement so new/roaming nodes learn the venue. */
    private void advertiseVenue() {
        String venue = advertisedVenue;
        if (running && venue != null && !venue.isEmpty()) {
            broadcast(VenueDirectory.venuePrefix() + venue, Router.DEFAULT_TTL);
        }
    }

    /** Venue directory built from heard adverts (incl. multi-hop ones). */
    public VenueDirectory getVenueDirectory() {
        return venueDirectory;
    }

    /** True when this node is acting as a relay hop for other users' data. */
    public boolean isRelayEnabled() {
        return router.isRelayEnabled();
    }

    /** Toggle this node's relay participation ("users as nodes"). */
    public void setRelayEnabled(boolean enabled) {
        router.setRelayEnabled(enabled);
    }

    public RelayStats getRelayStats() {
        return router.getStats();
    }

    // --- long-message fragmentation (send side) ------------------------------

    /**
     * Split a too-long text into framed fragments and start the staggered
     * send. Each frame becomes its own flood message with a deterministic
     * id (group id + index), so relay dedup and receiver reassembly work
     * unchanged. Returns false only when nothing frameable remains.
     */
    /** Per-frame wire id source (ingest uses deterministic event-derived ids). */
    private interface FrameIdSource {
        String idFor(int oneBasedIndex);
    }

    private boolean sendFragments(String dstId, MeshKind kind, String text, int ttl, long nowMs) {
        return sendFragmentsWithGroup(dstId, kind, text, ttl,
                PacketCodec.fragmentGroupId(nodeId, text, nowMs), null);
    }

    /**
     * Fragment flush with an explicit group id. {@code frameIds} may be null
     * (random per-frame ids, fine for user sends); the ingest path passes
     * event-derived ids so two phones pushing the same server event flood
     * IDENTICAL frames and the dedup collapses the copies.
     */
    private boolean sendFragmentsWithGroup(String dstId, MeshKind kind, String text, int ttl,
                                           String groupId, FrameIdSource frameIds) {
        java.util.List<String> frames = PacketCodec.fragment(text, groupId);
        if (frames == null || frames.isEmpty()) {
            log.addEntry(new MeshLogEntry(
                    Direction.TX, nodeId, dstId,
                    "dropped: " + (text == null ? 0 : text.length())
                            + " chars cannot be framed",
                    kind, 0, 0, nodeId, MeshStatus.DROPPED_TOO_LONG, "-"));
            return false;
        }
        pendingFragments.clear();
        for (int i = 0; i < frames.size(); i++) {
            MeshMessage frame = frameIds == null
                    ? MeshMessage.create(nodeId, dstId, kind, frames.get(i))
                    : MeshMessage.createWithId(frameIds.idFor(i + 1),
                            nodeId, dstId, kind, frames.get(i), ttl);
            pendingFragments.add(frame);
        }
        pendingFragmentDst = dstId == null ? "" : dstId;
        if (!pendingFragmentDst.isEmpty()) {
            // ONE token for the whole stream: the destination ACKs the group
            // id once the COMPLETE text reassembles — the delivery status
            // then means what it says instead of "first frame landed".
            deliveryTracker.track(groupId, pendingFragmentDst, System.currentTimeMillis());
        }
        cacheFragments(groupId, pendingFragmentDst, kind, frames, System.currentTimeMillis());
        log.addEntry(new MeshLogEntry(
                Direction.TX, nodeId, pendingFragmentDst,
                "fragmenting: " + text.length() + " chars -> " + frames.size()
                        + " packets",
                kind, 0, 0, nodeId, MeshStatus.IN_FLIGHT, groupId));
        flushFragments();
        return true;
    }

    /** Remember a just-sent group's frames so a NACK can re-send them. */
    private void cacheFragments(String groupId, String dstId, MeshKind kind,
                                java.util.List<String> frames, long nowMs) {
        fragmentCache.values().removeIf(c -> nowMs - c.createdAtMs > FRAGMENT_CACHE_TTL_MS);
        while (fragmentCache.size() >= FRAGMENT_CACHE_MAX) {
            java.util.Iterator<String> oldest = fragmentCache.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        fragmentCache.put(groupId, new CachedGroup(dstId == null ? "" : dstId, kind,
                new java.util.ArrayList<>(frames), nowMs));
    }

    /**
     * Re-send the frames a receiver asked for via {@link MeshKind#NACK}.
     * Recovery copies get FRESH message ids on purpose: relays that already
     * deduped the originals would otherwise drop the re-send before it can
     * reach the receiver that missed them.
     */
    private void resendMissingFrames(MeshMessage nack) {
        String groupId = PacketCodec.nackGroupId(nack.getPayload());
        long mask = PacketCodec.nackMissingMask(nack.getPayload());
        if (groupId.isEmpty() || mask == 0) {
            return;
        }
        CachedGroup cached = fragmentCache.get(groupId);
        if (cached == null) {
            return; // never sent by us (or aged out): nothing to re-send
        }
        int resent = 0;
        for (String framePayload : cached.framePayloads) {
            int index = PacketCodec.fragmentIndex(framePayload);
            if (index >= 1 && (mask & (1L << (index - 1))) != 0) {
                MeshMessage resend = MeshMessage.create(
                        nodeId, cached.dstId, cached.kind, framePayload);
                router.onLocalSend(resend);
                transport.send(resend);
                resent++;
            }
        }
        if (resent > 0) {
            log.addEntry(new MeshLogEntry(
                    Direction.TX, nodeId, cached.dstId,
                    "resending " + resent + " missing packet(s) of group " + groupId,
                    MeshKind.NACK, 0, 0, nodeId, MeshStatus.RELAYED, groupId));
        }
    }

    /** Periodic NACK sweep over incomplete fragment groups. */
    private void nackSweepTick() {
        fragmentAssembler.nackSweep(System.currentTimeMillis());
    }

    /** Runs one NACK sweep at the given time; package-private for tests. */
    void nackSweepForTest(long nowMs) {
        fragmentAssembler.nackSweep(nowMs);
    }

    /** Send the next pending fragment frame; re-schedule while frames remain. */
    private void flushFragments() {
        while (!pendingFragments.isEmpty()) {
            MeshMessage frame = pendingFragments.poll();
            router.onLocalSend(frame);
            transport.send(frame);
            if (pendingFragments.isEmpty()) {
                return;
            }
            if (fragmentIntervalMs <= 0) {
                continue; // synchronous mode (tests)
            }
            maintenance.schedule(this::flushFragments, fragmentIntervalMs,
                    TimeUnit.MILLISECONDS);
            return;
        }
    }

    /** Test seam: gap between fragment frames (0 = synchronous flush). */
    void setFragmentIntervalForTest(long intervalMs) {
        this.fragmentIntervalMs = intervalMs;
    }

    /** Runs one fragment-flush cycle synchronously; package-private for tests. */
    void flushFragmentsForTest() {
        flushFragments();
    }

    private void sendAck(MeshMessage message) {
        if (!running) {
            return;
        }
        MeshMessage ack = MeshMessage.create(nodeId, message.getSrcId(), MeshKind.ACK,
                PacketCodec.ackToken(message));
        router.onLocalSend(ack);
        transport.send(ack);
    }

    @Override
    public void onPacketReceived(MeshMessage message, int rssi, String from) {
        // Fragment frames are offered to the assembler first: it buffers and
        // fires the COMPLETE text exactly once per group. The router still
        // floods/dedups the frame itself so every phone receives the group.
        fragmentAssembler.offer(message.getSrcId(), message.getKind(), message.getPayload(),
                System.currentTimeMillis());
        router.onReceive(message, rssi, from);
    }

    @Override
    public void onTransportStarted() {
        refreshPeers();
    }

    @Override
    public void onTransportStopped() {
        // nothing to clean up here; peers will expire naturally
    }

    private void refreshPeers() {
        for (Peer peer : transport.getPeers()) {
            boolean isNew = network.upsert(peer);
            if (isNew) {
                firePeerDiscovered(peer);
            }
        }
        for (Peer lost : network.expire()) {
            firePeerLost(lost);
        }
        updatePosition();
    }

    private void sweepDeliveries() {
        List<DeliveryTracker.Expired> expired =
                deliveryTracker.sweep(System.currentTimeMillis());
        for (DeliveryTracker.Expired e : expired) {
            fireDeliveryStatus(e.token, e.dstId, false);
        }
    }

    /**
     * Re-evaluate the position estimate and remember where it came from —
     * measured beacon anchor ({@link AnchorSource#BEACON}), strongest mesh
     * venue advert ({@link AnchorSource#MESH}) or nothing ({@link
     * AnchorSource#NONE}). Fires {@code onPositionUpdated} on change.
     */
    private void updatePosition() {
        PositionEstimator estimator = positionEstimator;
        if (estimator == null) {
            return;
        }
        estimator.feed(network.getAll());
        syncVenueAnchors(estimator);
        // Weighted-centroid estimate over every fresh anchor (falls back to
        // the single strongest anchor when only one is in range).
        PositionEstimator.Position weighted = estimator.estimatePosition();
        String nodeId = weighted == null ? null : weighted.nodeId;
        PositionEstimator.Anchor strongest = estimator.strongestAnchor();
        if (strongest == null) {
            lastPositionSource = AnchorSource.NONE;
        } else {
            lastPositionSource = strongest.fromBeacon ? AnchorSource.BEACON : AnchorSource.MESH;
        }
        if (nodeId != null && !nodeId.equals(lastPositionNodeId)) {
            lastPositionNodeId = nodeId;
            firePositionUpdated(nodeId);
        } else if (nodeId == null && lastPositionNodeId != null) {
            // All anchors expired: report the position as lost.
            lastPositionNodeId = null;
            firePositionUpdated(null);
        }
    }

    /**
     * Keep venue-advert anchors in step with the venue directory: adverts
     * whose directory entry expired are dropped, live ones (including
     * multi-hop) are applied as anchors.
     */
    private void syncVenueAnchors(PositionEstimator estimator) {
        java.util.Set<String> liveVenues = new java.util.HashSet<>();
        for (VenueDirectory.Entry entry : venueDirectory.entries()) {
            liveVenues.add(entry.venue);
            estimator.observeVenueAdvert(entry.venue, entry.hops);
        }
        estimator.retainVenueAnchors(liveVenues);
    }

    public void setPositionEstimator(PositionEstimator positionEstimator) {
        this.positionEstimator = positionEstimator;
    }

    public void addListener(MeshEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MeshEventListener listener) {
        listeners.remove(listener);
    }

    private void fireDelivered(MeshMessage message) {
        for (MeshEventListener listener : listeners) {
            listener.onMessageDelivered(message);
        }
    }

    private void firePeerDiscovered(Peer peer) {
        for (MeshEventListener listener : listeners) {
            listener.onPeerDiscovered(peer);
        }
    }

    private void firePeerLost(Peer peer) {
        for (MeshEventListener listener : listeners) {
            listener.onPeerLost(peer);
        }
    }

    private void firePositionUpdated(String nodeId) {
        for (MeshEventListener listener : listeners) {
            listener.onPositionUpdated(nodeId);
        }
    }

    private void fireVenueDirectoryChanged() {
        for (MeshEventListener listener : listeners) {
            listener.onVenueDirectoryChanged();
        }
    }

    private void fireDeliveryStatus(String token, String dstId, boolean delivered) {
        for (MeshEventListener listener : listeners) {
            listener.onDeliveryStatus(token, dstId, delivered);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public MeshTransport getTransport() {
        return transport;
    }

    public MessageLog getLog() {
        return log;
    }

    public MeshNetwork getNetwork() {
        return network;
    }

    public List<Peer> getPeers() {
        return network.getAll();
    }

    public String getLastPositionNodeId() {
        return lastPositionNodeId;
    }

    /** Runs one peer-refresh + position-update cycle; package-private for tests. */
    void refreshPeersForTest() {
        refreshPeers();
    }

    /** Test seam: inject a rebroadcast window with a deterministic clock. */
    void setRebroadcastWindow(RebroadcastWindow window) {
        this.rebroadcastWindow = window;
    }

    /** Runs one rebroadcast-window service cycle; package-private for tests. */
    void rebroadcastTickForTest() {
        rebroadcastTick();
    }
}
