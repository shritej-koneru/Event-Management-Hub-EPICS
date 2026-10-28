package com.epics.bitchat.mesh;

/**
 * Callbacks the app observes from the local mesh node. All callbacks arrive on
 * mesh worker threads; the app is responsible for hopping back to the main
 * thread if it touches the UI.
 */
public interface MeshEventListener {
    /** A new row appeared in the traffic console. */
    void onLogEntry(MeshLogEntry entry);

    /** A new peer (phone or beacon) came within range. */
    void onPeerDiscovered(Peer peer);

    /** A previously known peer disappeared. */
    void onPeerLost(Peer peer);

    /** A message destined for this node was delivered. */
    void onMessageDelivered(MeshMessage message);

    /**
     * A locally sent message was acknowledged by its destination (delivered
     * true) or timed out waiting for the ACK (delivered false). The token is
     * the short id echoed by the ACK.
     */
    default void onDeliveryStatus(String token, String dstId, boolean delivered) {
    }

    /** The estimated graph position changed (beacon-based positioning). */
    void onPositionUpdated(String graphNodeId);

    /**
     * A venue advert was heard (possibly relayed multi-hop) and the venue
     * directory changed. Callbacks arrive on mesh worker threads.
     */
    default void onVenueDirectoryChanged() {
    }
}
