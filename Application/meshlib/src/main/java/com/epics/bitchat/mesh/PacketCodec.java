package com.epics.bitchat.mesh;

import java.nio.charset.StandardCharsets;

/**
 * Compact binary codec for BLE advertisement payloads (Phase 6, wire v3).
 *
 * <pre>
 * byte 0       version = 0x03
 * byte 1       kind
 * byte 2       packed: high nibble ttl (1..15), low nibble hops (0..15)
 * byte 3       payload length n
 * byte 4-6     message id (3 raw ASCII bytes — enough to de-dup a flood;
 *              collisions only matter inside the seen-window)
 * byte 7-9     src id (3 ASCII chars, NUL padded — 5 bits/char over a
 *              32-char alphabet gives 32k distinct devices)
 * byte 10-12   dst id (3 ASCII chars, NUL padded)
 * byte 13..    payload (max {@value #MAX_PAYLOAD} ASCII chars)
 * </pre>
 *
 * Total at full payload = {@value #PACKET_SIZE} bytes, which exactly fills a
 * single BLE advertisement's manufacturer-data record (31 - 4 byte record
 * overhead). The old v2 format wasted 21 bytes on the header and capped
 * payloads at 6 chars ("meet at Hall 2" arrived as "meet a"); compressing
 * node ids to 3 chars, the msg id to 3 bytes and packing hops/ttl into one
 * nibble-pair bought the other 8.
 *
 * <p>On the mesh wire, src/dst are the first 3 chars of the device id
 * (device ids come from a 32-char alphabet — 3 chars = 32,768 distinct
 * devices), so identity adverts and message sources share one id. Payloads
 * that exceed {@link #MAX_PAYLOAD} are <b>rejected</b> ({@link
 * IllegalArgumentException} from {@link #encode}), never silently cut.
 *
 * <p>The version byte is deliberately distinct from {@link BeaconParser}'s
 * {@code 0x01}, so a receiver can tell a mesh message packet apart from a
 * phone identity advert without ambiguity.
 */
public final class PacketCodec {

    /** Alphabet for fragment group ids (no 0/O/1/I confusables, like ids). */
    private static final String FRAG_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** First frame element after the group id: fragment index. */
    private static final char FRAG_INDEX_A = 'A';
    /** Second frame element: total fragment count (also 1-based index encoding). */
    private static final char FRAG_TOTAL_A = 'A';
    /**
     * Headroom a frame spends on the wire: group id (3) + index (1) + total
     * (1) — the payload text of each frame gets MAX_PAYLOAD minus this.
     */
    public static final int FRAMING_OVERHEAD = 5;
    /** Per-frame text budget ({@value} chars). */
    public static final int FRAGMENT_TEXT_CHARS = PacketCodec.MAX_PAYLOAD - FRAMING_OVERHEAD;
    /** Hard cap on frames per message (the total element is 1 base-36 char). */
    public static final int MAX_FRAGMENTS = 36;
    /** Longest text auto-fragmentation can carry (frames × budget). */
    public static final int MAX_FRAGMENTED_TEXT = MAX_FRAGMENTS * FRAGMENT_TEXT_CHARS;

    private static final byte VERSION = 0x03;
    private static final int NODE_ID_WIRE = 3;
    private static final int MSG_ID_WIRE = 3;
    private static final int SANITIZE_LEN = 6;
    /** Wire-layout constants: ver(1) kind(1) ttl|hops(1) len(1) id(3) src(3) dst(3). */
    public static final int HEADER_SIZE = 4 + MSG_ID_WIRE + NODE_ID_WIRE * 2;
    public static final int MAX_PAYLOAD = 27 - HEADER_SIZE;
    public static final int PACKET_SIZE = HEADER_SIZE + MAX_PAYLOAD;

    private PacketCodec() {
    }

    private static byte kindByte(MeshKind kind) {
        switch (kind) {
            case PING:
                return 0x01;
            case ACK:
                return 0x02;
            case BEACON:
                return 0x03;
            case ANNOUNCE:
                return 0x04;
            case TICKET:
                return 0x05;
            case NACK:
                return 0x06;
            case CHAT:
            default:
                return 0x00;
        }
    }

    private static MeshKind kindFrom(byte b) {
        switch (b) {
            case 0x01:
                return MeshKind.PING;
            case 0x02:
                return MeshKind.ACK;
            case 0x03:
                return MeshKind.BEACON;
            case 0x04:
                return MeshKind.ANNOUNCE;
            case 0x05:
                return MeshKind.TICKET;
            case 0x06:
                return MeshKind.NACK;
            default:
                return MeshKind.CHAT;
        }
    }

    // --- fragmentation framing ----------------------------------------------

    /**
     * Frame a {@code text} fragment for the wire. Format: {@code <3-char group
     * id><index><total><payload>} — the frame elements are appended AFTER the
     * payload text of the original message, so the frame always stays inside
     * {@link #MAX_PAYLOAD}. Index runs {@code A, B, C, …} (letters first, then
     * digits for fragments 27+); total uses the same encoding so a receiver
     * can distinguish a 11-frame ({@code K}) from a 1-frame ({@code A}) stream.
     * Returns null when the frame would not fit (payload text too long or
     * empty) — callers then re-split / reject rather than mangle.
     */
    public static String makeFragmentFrame(String groupId, int index, int total, String payload) {
        if (groupId == null || groupId.length() < 3
                || payload == null || payload.isEmpty()
                || index < 1 || total < 1 || index > total) {
            return null;
        }
        String frame = groupId.substring(0, 3) + fragChar(index) + fragChar(total) + payload;
        return frame.length() <= MAX_PAYLOAD ? frame : null;
    }

    /** Keep printable ASCII (incl. spaces), drop noise — no length cap. */
    private static String sanitizeText(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= ' ' && c < 127) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 1-char wire token for a 1-based fragment index — the SAME letter-then-
     * digit encoding the frame uses. Senders suffix it to the group id to
     * mint each frame's deterministic message id
     * ({@code groupId + fragmentIndexToken(index)}).
     */
    public static String fragmentIndexToken(int oneBased) {
        return String.valueOf(fragChar(oneBased));
    }

    /** Letter-then-digit encoding shared by index and total elements. */
    private static char fragChar(int oneBased) {
        int v = oneBased - 1;
        return v < 26 ? (char) (FRAG_INDEX_A + v) : (char) ('0' + (v - 26));
    }

    private static int fragVal(char c) {
        return c >= 'A' && c <= 'Z' ? c - FRAG_INDEX_A + 1
                : c >= '0' && c <= '9' ? c - '0' + 27 : -1;
    }

    /**
     * True when the payload carries the frame {@code <group:3><index:1>
     * <total:1>…} — group id first, then index and total at offsets 3/4.
     */
    public static boolean isFragment(String payload) {
        if (payload == null || payload.length() < FRAMING_OVERHEAD) {
            return false;
        }
        return fragVal(payload.charAt(3)) > 0 && fragVal(payload.charAt(4)) > 0;
    }

    /** Frame group id (first 3 chars) — '' when not a fragment. */
    public static String fragmentGroup(String payload) {
        return isFragment(payload) ? payload.substring(0, 3) : "";
    }

    /** 1-based fragment index — 0 when not a fragment. */
    public static int fragmentIndex(String payload) {
        return isFragment(payload) ? fragVal(payload.charAt(3)) : 0;
    }

    /** Declared total fragment count — 0 when not a fragment. */
    public static int fragmentCount(String payload) {
        return isFragment(payload) ? fragVal(payload.charAt(4)) : 0;
    }

    /** The fragment's payload text (everything after the frame elements). */
    public static String fragmentText(String payload) {
        return isFragment(payload) ? payload.substring(FRAMING_OVERHEAD) : "";
    }

    /**
     * Split {@code text} into wire-ready fragment frames.
     *
     * <p>Each fragment keeps the whole text's words when possible (splits on
     * spaces first), because receivers reassemble the parts verbatim. The
     * {@code groupId} is a stable 3+ char id shared by all frames of one
     * message (the sender derives it from the content — every phone that
     * fragments the same text computes the same id, so dedup works). Returns
     * an empty list when the text is empty; returns null only when a single
     * fragment cannot even be framed (callers should reject rather than send
     * garbage).
     */
    public static java.util.List<String> fragment(String text, String groupId) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) {
            return out;
        }
        String sanitized = sanitizeText(text);
        if (sanitized.isEmpty()) {
            return out;
        }
        // Greedy split at spaces, falling back to hard cuts. The budget per
        // fragment is MAX_PAYLOAD minus the 5-char frame prefix
        // (3-char group id + index + total).
        int budget = MAX_PAYLOAD - FRAMING_OVERHEAD;
        java.util.List<String> parts = new java.util.ArrayList<>();
        int start = 0;
        while (start < sanitized.length()) {
            int end = Math.min(start + budget, sanitized.length());
            if (end < sanitized.length()) {
                int space = sanitized.lastIndexOf(' ', end);
                if (space > start) {
                    // Cut BEFORE the space: the next part re-owns it, so no
                    // part ever exceeds the budget (end = space + 1 would).
                    end = space;
                }
            }
            parts.add(sanitized.substring(start, end));
            start = end;
        }
        int total = parts.size();
        if (total > MAX_FRAGMENTS) {
            return null; // beyond the frame cap — reject, not truncate
        }
        for (int i = 0; i < total; i++) {
            String frame = makeFragmentFrame(groupId, i + 1, total, parts.get(i));
            if (frame == null) {
                return null;
            }
            out.add(frame);
        }
        return out;
    }

    /**
     * Deterministic 3-char group id for a fragmented message (FNV-1a over
     * {@code srcId|text|epochMs}). The epoch salt means every user action gets
     * a fresh id (a repeat announce 5 min later re-floods instead of being
     * deduped); the src|text prefix keeps the id stable across phones that
     * fragment the same text at the same millisecond (rare ingest race).
     */
    public static String fragmentGroupId(String srcId, String text, long epochMs) {
        String seed = (srcId == null ? "" : srcId) + "|" + (text == null ? "" : text)
                + "|" + epochMs;
        int hash = 0x811c9dc5;
        for (byte b : seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        StringBuilder sb = new StringBuilder(3);
        int h = hash;
        for (int i = 0; i < 3; i++) {
            sb.append(FRAG_ALPHABET.charAt(Math.abs(h) % FRAG_ALPHABET.length()));
            h /= FRAG_ALPHABET.length();
            if (h == 0) {
                h = 0x811c9dc5 + i; // keep mixing for short hashes
            }
        }
        return sb.toString();
    }

    // --- NACK: request re-send of missing fragment frames -------------------

    /** Marker char at offset 3 of a NACK payload (a frame's index slot). */
    public static final char NACK_MARKER = 'N';
    /** Lowercase base-26 chars carrying the missing-frames bitmask. */
    public static final int NACK_MASK_CHARS = 8;
    /** Largest fragment count the bitmask can represent (matches {@link #MAX_FRAGMENTS}). */
    public static final int NACK_MASK_BITS = 36;

    /**
     * Build a NACK payload: {@code <group:3>N<8 lowercase base-26 chars>} —
     * 12 chars, inside one advert budget. Bit {@code i-1} set = frame
     * {@code i} missing. The mask alphabet is deliberately lowercase:
     * {@link #isFragment} reads offsets 3/4, and a lowercase char at offset 4
     * can never parse as a frame element, so a NACK can never be mistaken for
     * a fragment frame by any receiver.
     */
    public static String makeNackPayload(String groupId, long missingMask) {
        if (groupId == null || groupId.length() < 3) {
            return null;
        }
        long v = missingMask & ((1L << NACK_MASK_BITS) - 1);
        StringBuilder sb = new StringBuilder(3 + 1 + NACK_MASK_CHARS);
        sb.append(groupId.substring(0, 3)).append(NACK_MARKER);
        for (int i = 0; i < NACK_MASK_CHARS; i++) {
            sb.append((char) ('a' + (int) (v % 26)));
            v /= 26;
        }
        return sb.toString();
    }

    /** True when the payload is a NACK request (group id + 'N' + mask). */
    public static boolean isNack(String payload) {
        if (payload == null || payload.length() != 3 + 1 + NACK_MASK_CHARS
                || payload.charAt(3) != NACK_MARKER) {
            return false;
        }
        for (int i = 4; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    /** Group id carried by a NACK payload — '' when this is not a NACK. */
    public static String nackGroupId(String payload) {
        return isNack(payload) ? payload.substring(0, 3) : "";
    }

    /** Missing-frames bitmask of a NACK payload — 0 when this is not a NACK. */
    public static long nackMissingMask(String payload) {
        if (!isNack(payload)) {
            return 0L;
        }
        long v = 0;
        for (int i = NACK_MASK_CHARS - 1; i >= 0; i--) {
            v = v * 26 + (payload.charAt(4 + i) - 'a');
        }
        return v;
    }

    /**
     * Sanitize a node id to {@value #SANITIZE_LEN} printable ASCII chars —
     * the identity-advert width (see {@link BeaconParser}). The mesh wire
     * carries only the first {@value #NODE_ID_WIRE} chars of it.
     */
    public static String sanitizeNodeId(String nodeId) {
        if (nodeId == null) {
            return "??????";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : nodeId.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            }
        }
        while (sb.length() < SANITIZE_LEN) {
            sb.append('\0');
        }
        if (sb.length() > SANITIZE_LEN) {
            return sb.substring(0, SANITIZE_LEN);
        }
        return sb.toString();
    }

    /**
     * True when the message fits on the wire unmodified. {@link #encode}
     * throws {@link IllegalArgumentException} for oversize payloads, so
     * senders that want to reject early (rather than catch) check this.
     */
    public static boolean fitsOnWire(MeshMessage message) {
        String payload = message.getPayload();
        return payload == null || payload.length() <= MAX_PAYLOAD;
    }

    /**
     * Encode a message. Throws {@link IllegalArgumentException} when the
     * payload exceeds {@link #MAX_PAYLOAD} — truncation is never silent;
     * callers either pre-check with {@link #fitsOnWire} or catch and surface
     * the failure to the user.
     */
    public static byte[] encode(MeshMessage message) {
        String payloadStr = message.getPayload();
        byte[] payload = payloadStr == null
                ? new byte[0]
                : payloadStr.getBytes(StandardCharsets.US_ASCII);
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                    "payload too long for one advert: " + payload.length
                            + " > " + MAX_PAYLOAD + " chars");
        }

        byte[] out = new byte[HEADER_SIZE + payload.length];
        out[0] = VERSION;
        out[1] = kindByte(message.getKind());
        // Clamp: a relayed copy decrements ttl and could reach 0; the flood
        // rule needs a re-advertised packet to still carry ttl ≥ 1.
        int ttl = Math.max(1, Math.min(0xF, message.getTtl()));
        int hops = Math.min(0xF, message.getHops());
        out[2] = (byte) ((ttl << 4) | hops);
        out[3] = (byte) payload.length;

        writePrefix(out, 4, message.getMsgId(), MSG_ID_WIRE);
        writePrefix(out, 7, message.getSrcId(), NODE_ID_WIRE);
        writePrefix(out, 10, message.getDstId(), NODE_ID_WIRE);

        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.length);
        return out;
    }

    private static void writePrefix(byte[] out, int offset, String s, int len) {
        byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) {
            out[offset + i] = (byte) (i < bytes.length ? bytes[i] : '\0');
        }
    }

    /**
     * Decode a packet back into a {@link MeshMessage}. Returns null for
     * unknown versions or too-short buffers. The message id is reconstructed
     * from the 3-byte prefix.
     */
    public static MeshMessage decode(byte[] data) {
        if (data == null || data.length < HEADER_SIZE || data[0] != VERSION) {
            return null;
        }
        MeshKind kind = kindFrom(data[1]);
        int packed = data[2] & 0xFF;
        int ttl = (packed >> 4) & 0xF;
        int hops = packed & 0xF;

        int n = Math.min(data[3] & 0xFF, MAX_PAYLOAD);
        if (data.length < HEADER_SIZE + n) {
            return null; // truncated transport record
        }
        String payload = n > 0
                ? new String(data, HEADER_SIZE, n, StandardCharsets.US_ASCII)
                : "";

        String msgId = readFixed(data, 4, MSG_ID_WIRE);
        String src = readFixed(data, 7, NODE_ID_WIRE);
        String dst = readFixed(data, 10, NODE_ID_WIRE);

        return new MeshMessage(msgId, src, dst, kind, payload, ttl, hops, System.currentTimeMillis());
    }

    private static String readFixed(byte[] data, int offset, int len) {
        int end = offset;
        while (end < offset + len && data[end] != '\0') {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    /**
     * The 8-hex message id reconstructed by the OLD v2 codec from a UUID's
     * first ASCII bytes. Kept for legacy callers/tests; on the v3 wire the
     * message id is simply the first {@value #MSG_ID_WIRE} chars of the
     * original id (see {@link #ackToken}).
     */
    public static String wireMsgId(MeshMessage message) {
        byte[] bytes = message.getMsgId().getBytes(StandardCharsets.US_ASCII);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    /**
     * Short token (at most {@value #MSG_ID_WIRE} chars) echoed in an ACK so
     * the sender can match it to the original message. For a locally created
     * message this is the first chars of its raw id; for a decoded one the
     * same chars, so sender and destination derive identical tokens.
     */
    public static String ackToken(MeshMessage message) {
        String id = message.getMsgId();
        if (id == null) {
            return "000";
        }
        if (id.length() > 8) {
            // Long (UUID-style) id: use the same ASCII prefix the wire
            // carries, so the destination echoes what the sender tracked.
            id = id.substring(0, MSG_ID_WIRE);
        }
        return id.length() <= MSG_ID_WIRE ? id : id.substring(0, MSG_ID_WIRE);
    }
}
