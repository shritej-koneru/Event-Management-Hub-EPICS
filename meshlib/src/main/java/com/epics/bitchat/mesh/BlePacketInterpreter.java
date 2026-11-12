package com.epics.bitchat.mesh;

/**
 * Pure-Java decoder for one BLE manufacturer-data payload received in a scan
 * result. It is intentionally free of Android dependencies so the entire
 * discovery / messaging decision logic is unit-testable on the JVM.
 *
 * <p>{@code BleAdvertiserTransport} feeds it the raw bytes straight from
 * {@code ScanResult#getScanRecord()}; the class decides whether the packet is
 * an identity advert (new phone in range), a mesh message (route it), or
 * something to ignore (our own advert, a beacon-type advert, or garbage).
 */
public final class BlePacketInterpreter {

    public enum Reason {
        /** A phone identity advert from another app instance. */
        IDENTITY,
        /** A mesh message packet. */
        MESSAGE,
        /** Our own advert (or a packet we originated). */
        SELF,
        /** A beacon-type advert; indoor nodes are not part of the phone mesh. */
        BEACON,
        /** Not a valid identity or message packet. */
        UNKNOWN
    }

    /** The outcome of interpreting one payload. Only fields relevant to the
     *  {@link Reason} are non-null. */
    public static final class Hit {
        public final Reason reason;
        public final String nodeId;
        public final String displayName;
        public final boolean isBeacon;
        public final MeshMessage message;
        public final int rssi;

        private Hit(Reason reason, String nodeId, String displayName, boolean isBeacon,
                    MeshMessage message, int rssi) {
            this.reason = reason;
            this.nodeId = nodeId;
            this.displayName = displayName;
            this.isBeacon = isBeacon;
            this.message = message;
            this.rssi = rssi;
        }
    }

    private BlePacketInterpreter() {
    }

    public static Hit interpret(byte[] manufacturerData, int rssi, String localNodeId) {
        if (manufacturerData == null || manufacturerData.length == 0) {
            return new Hit(Reason.UNKNOWN, null, null, false, null, rssi);
        }

        BeaconParser.BeaconInfo beacon = BeaconParser.decode(manufacturerData);
        if (beacon != null) {
            if (beacon.isBeacon) {
                return new Hit(Reason.BEACON, beacon.nodeId, beacon.displayName, true, null, rssi);
            }
            if (beacon.nodeId == null || beacon.nodeId.isEmpty()
                    || beacon.nodeId.equals(localNodeId)) {
                return new Hit(Reason.SELF, beacon.nodeId, beacon.displayName, false, null, rssi);
            }
            return new Hit(Reason.IDENTITY, beacon.nodeId, beacon.displayName, false, null, rssi);
        }

        MeshMessage message = PacketCodec.decode(manufacturerData);
        if (message == null) {
            return new Hit(Reason.UNKNOWN, null, null, false, null, rssi);
        }
        if (message.getSrcId() == null || message.getSrcId().isEmpty()
                || message.getSrcId().equals(localNodeId)) {
            return new Hit(Reason.SELF, message.getSrcId(), null, false, null, rssi);
        }
        return new Hit(Reason.MESSAGE, message.getSrcId(), null, false, message, rssi);
    }
}
