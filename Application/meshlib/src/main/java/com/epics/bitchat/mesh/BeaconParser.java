package com.epics.bitchat.mesh;

import java.nio.charset.StandardCharsets;

/**
 * Wire codec for the payload carried inside a BLE advertisement (Phase 6).
 *
 * <pre>
 * byte 0       : version (0x01)
 * byte 1       : type (0x01 phone, 0x02 beacon)
 * byte 2       : floor (signed)
 * bytes 3..8   : node id, 6 ASCII chars, NUL padded
 * bytes 9..14  : display name, 6 ASCII chars, NUL padded
 * </pre>
 *
 * <p>The phone identity advert carries both the stable wire id (used for
 * routing and dedup) and the user-visible display name, so two phones named
 * "Phone A" still get distinct wire ids while showing friendly labels in the
 * peers list. Older adverts without the display-name field decode with the
 * display name falling back to the node id.
 */
public final class BeaconParser {

    public static final byte TYPE_PHONE = 0x01;
    public static final byte TYPE_BEACON = 0x02;
    private static final byte VERSION = 0x01;
    private static final int MAX_NODE_ID_BYTES = 6;
    private static final int MAX_NAME_BYTES = 6;

    public static class BeaconInfo {
        public final String nodeId;
        public final String displayName;
        public final int floor;
        public final boolean isBeacon;

        BeaconInfo(String nodeId, String displayName, int floor, boolean isBeacon) {
            this.nodeId = nodeId == null ? "" : nodeId.trim();
            String name = displayName == null || displayName.isEmpty() ? nodeId : displayName.trim();
            this.displayName = name.isEmpty() ? this.nodeId : name;
            this.floor = floor;
            this.isBeacon = isBeacon;
        }
    }

    private BeaconParser() {
    }

    /** Backwards-compatible encode where the display name equals the node id. */
    public static byte[] encode(String nodeId, int floor, boolean isBeacon) {
        return encode(nodeId, nodeId, floor, isBeacon);
    }

    public static byte[] encode(String nodeId, String displayName, int floor, boolean isBeacon) {
        String id = PacketCodec.sanitizeNodeId(nodeId);
        String name = PacketCodec.sanitizeNodeId(displayName == null ? nodeId : displayName);
        byte[] out = new byte[3 + MAX_NODE_ID_BYTES + MAX_NAME_BYTES];
        out[0] = VERSION;
        out[1] = isBeacon ? TYPE_BEACON : TYPE_PHONE;
        out[2] = (byte) floor;
        writeFixed(out, 3, id, MAX_NODE_ID_BYTES);
        writeFixed(out, 3 + MAX_NODE_ID_BYTES, name, MAX_NAME_BYTES);
        return out;
    }

    private static void writeFixed(byte[] out, int offset, String s, int len) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) {
            out[offset + i] = (byte) (i < bytes.length ? bytes[i] : '\0');
        }
    }

    public static BeaconInfo decode(byte[] data) {
        if (data == null || data.length < 3 || data[0] != VERSION) {
            return null;
        }
        boolean isBeacon = data[1] == TYPE_BEACON;
        int floor = data[2];
        String nodeId = readFixed(data, 3, MAX_NODE_ID_BYTES);
        String displayName = data.length >= 3 + MAX_NODE_ID_BYTES + 1
                ? readFixed(data, 3 + MAX_NODE_ID_BYTES, MAX_NAME_BYTES)
                : nodeId;
        return new BeaconInfo(nodeId, displayName, floor, isBeacon);
    }

    private static String readFixed(byte[] data, int offset, int len) {
        int end = offset;
        while (end < offset + len && end < data.length && data[end] != '\0') {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
