package com.epics.bitchat.mesh;

/**
 * Lifecycle state of a message as it travels through the mesh.
 */
public enum MeshStatus {
    /** Message created locally and handed to the transport. */
    IN_FLIGHT,
    /** Reached its intended destination. */
    DELIVERED,
    /** Dropped because TTL reached zero before reaching the destination. */
    DROPPED_TTL,
    /** Dropped because this node already relayed the same message id. */
    DROPPED_DUP,
    /** Dropped by the (simulated) radio link, e.g. packet loss. */
    DROPPED_LOSS,
    /** Dropped locally: the payload cannot fit in one BLE advert. */
    DROPPED_TOO_LONG,
    /** Received from a neighbor and re-broadcast towards the destination. */
    RELAYED,
    /** Flood stopped: this node has relaying disabled (not acting as a hop). */
    DROPPED_NO_RELAY
}
