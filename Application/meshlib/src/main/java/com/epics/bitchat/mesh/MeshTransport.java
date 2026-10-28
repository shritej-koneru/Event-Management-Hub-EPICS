package com.epics.bitchat.mesh;

import java.util.List;

/**
 * Abstraction over a raw mesh medium. The {@link SimulatedTransport} runs an
 * in-process radio for demos and unit tests; {@code BleAdvertiserTransport}
 * (Phase 6) uses real BLE advertisements.
 */
public interface MeshTransport {

    interface Listener {
        /**
         * A packet reached this node from a neighbor.
         *
         * @param message the received message
         * @param rssi    received signal strength in dBm
         * @param from    node id of the neighbor that transmitted it
         */
        void onPacketReceived(MeshMessage message, int rssi, String from);

        void onTransportStarted();

        void onTransportStopped();
    }

    void start();

    void stop();

    boolean isRunning();

    /** Broadcast a message from the local node over the medium. */
    void send(MeshMessage message);

    /** Node id of this transport's local device. */
    String getLocalNodeId();

    /** Current view of peers in range. */
    List<Peer> getPeers();

    void setListener(Listener listener);
}
