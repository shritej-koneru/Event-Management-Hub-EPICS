package com.epics.bitchat.mesh;

/**
 * The kind of a mesh message. Mirrors the semantics of BitChat's short
 * Nostr-style control + chat messages, compressed to fit BLE advertisements.
 */
public enum MeshKind {
    CHAT,
    PING,
    ACK,
    /** Receiver → sender: these fragment frames never arrived (re-send). */
    NACK,
    BEACON,
    /** Host announcement flooded to every user (one-way broadcast). */
    ANNOUNCE,
    /** Ticket / credential pass shared from one user to another. */
    TICKET
}
