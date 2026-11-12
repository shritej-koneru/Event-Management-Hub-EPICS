package com.epics.bitchat.mesh;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PacketCodecTest {

    @Test
    public void roundTripsChatMessage() {
        MeshMessage msg = MeshMessage.create("node_0", "node_3", MeshKind.CHAT, "hello");
        byte[] packet = PacketCodec.encode(msg);
        MeshMessage decoded = PacketCodec.decode(packet);

        assertEquals(MeshKind.CHAT, decoded.getKind());
        assertEquals("hello", decoded.getPayload());
        assertEquals(0, decoded.getHops());
        assertEquals(msg.getTtl(), decoded.getTtl());
        // msg id is reconstructed as the first 3 raw ASCII bytes of the UUID
        assertEquals(msg.getMsgId().substring(0, 3), decoded.getMsgId());
    }

    @Test
    public void sanitizesNodeIdsToSixChars() {
        assertEquals("abcdef", PacketCodec.sanitizeNodeId("abcdefghij"));
        assertEquals("abcdef", PacketCodec.sanitizeNodeId("abcdef"));
        assertEquals("??????", PacketCodec.sanitizeNodeId(null));
    }

    @Test
    public void longVenuePayloadsRoundTrip() {
        // The 6-char cap was the headline bug: venue names and sentences died.
        String[] payloads = {"meet at Hall 2", "V:Hall 1", "V:Cafeteria", "12345678901234"};
        for (String payload : payloads) {
            MeshMessage msg = MeshMessage.create("A1", "B2", MeshKind.CHAT, payload);
            assertTrue(payload + " must fit", PacketCodec.fitsOnWire(msg));
            MeshMessage decoded = PacketCodec.decode(PacketCodec.encode(msg));
            assertEquals(payload, decoded.getPayload());
        }
    }

    @Test
    public void oversizePayloadIsRejectedNotTruncated() {
        MeshMessage msg = MeshMessage.create("a", "b", MeshKind.CHAT,
                "this is way too long for an advertisement");
        assertFalse(PacketCodec.fitsOnWire(msg));
        try {
            PacketCodec.encode(msg);
            throw new AssertionError("encode must reject oversize payloads");
        } catch (IllegalArgumentException expected) {
            // expected — silent truncation is what mangled venue names before
        }
    }

    @Test
    public void packedTtlAndHopsSurviveTheWire() {
        MeshMessage msg = new MeshMessage("XYZ", "A1", "B2", MeshKind.CHAT, "hey", 7, 4,
                System.currentTimeMillis());
        MeshMessage decoded = PacketCodec.decode(PacketCodec.encode(msg));
        assertEquals(7, decoded.getTtl());
        assertEquals(4, decoded.getHops());

        // Nibble clamping: ttl 0 cannot exist on the wire (a relayed packet
        // is never re-advertised), 16+ saturates to 15.
        MeshMessage zeroTtl = new MeshMessage("XYZ", "A1", "B2", MeshKind.CHAT, "hey", 0, 20,
                System.currentTimeMillis());
        MeshMessage decodedZero = PacketCodec.decode(PacketCodec.encode(zeroTtl));
        assertEquals(1, decodedZero.getTtl());
        assertEquals(15, decodedZero.getHops());
    }

    @Test
    public void packetSizeMatchesConstant() {
        MeshMessage msg = MeshMessage.create("A1", "B2", MeshKind.CHAT,
                new String(new char[PacketCodec.MAX_PAYLOAD]).replace('\0', 'x'));
        byte[] packet = PacketCodec.encode(msg);
        assertEquals(PacketCodec.PACKET_SIZE, packet.length);
        // Must exactly fill the 27-byte manufacturer-data record budget.
        assertEquals(27, PacketCodec.PACKET_SIZE);
    }

    @Test
    public void invalidBufferDecodesToNull() {
        assertNull(PacketCodec.decode(null));
        assertNull(PacketCodec.decode(new byte[]{0x00, 0x01, 0x02}));
        // Wrong version and a length byte beyond the buffer must not decode.
        assertNull(PacketCodec.decode(new byte[]{0x02, 0x00, 0x51, 0x05, 65, 66, 67, 68, 69, 70,
                71, 72, 73}));
        assertNull(PacketCodec.decode(new byte[]{0x03, 0x00, 0x51, 0x0E, 65, 66, 67, 68, 69, 70,
                71, 72, 73}));
    }

    @Test
    public void differentMessagesProduceDifferentPackets() {
        MeshMessage a = MeshMessage.create("node_0", "node_1", MeshKind.CHAT, "hi");
        MeshMessage b = MeshMessage.create("node_0", "node_1", MeshKind.CHAT, "bye");
        byte[] pa = PacketCodec.encode(a);
        byte[] pb = PacketCodec.encode(b);
        assertNotEquals(new String(pa), new String(pb));
    }

    @Test
    public void ackTokenMatchesBetweenSenderAndDestination() {
        MeshMessage msg = MeshMessage.create("node_0", "node_3", MeshKind.CHAT, "hello");
        MeshMessage decoded = PacketCodec.decode(PacketCodec.encode(msg));

        assertEquals(PacketCodec.ackToken(msg), PacketCodec.ackToken(decoded));
        assertTrue(PacketCodec.ackToken(msg).length() <= 3);
    }

    @Test
    public void shortMsgIdsAreUsedVerbatim() {
        MeshMessage msg = new MeshMessage("MSG", "A1", "B2", MeshKind.CHAT, "x", 8, 0,
                System.currentTimeMillis());
        assertEquals("MSG", PacketCodec.ackToken(msg));
        MeshMessage decoded = PacketCodec.decode(PacketCodec.encode(msg));
        assertEquals("MSG", decoded.getMsgId());
        assertEquals("MSG", PacketCodec.ackToken(decoded));
    }

    @Test
    public void shortNodeIdsRoundTripWithNullPadding() {
        MeshMessage msg = new MeshMessage("MSG", "AB", "C", MeshKind.CHAT, "hey", 8, 0,
                System.currentTimeMillis());
        MeshMessage decoded = PacketCodec.decode(PacketCodec.encode(msg));
        assertEquals("AB", decoded.getSrcId());
        assertEquals("C", decoded.getDstId());
    }

    /**
     * Regression for the field-test headline bug (#1/#16): the sender tracks
     * {@code ackToken(message)}, the destination's ACK carries that token in
     * its PAYLOAD, and the sender's decode + ack must match it exactly. The
     * old code derived the token from the ACK's own (randomly minted) wire id
     * and never matched anything.
     */
    @Test
    public void ackPayloadMatchesTheTrackedToken() {
        MeshNode sender = new MeshNode("A1", "Phone A",
                new StubTransport("A1"), new MessageLog());
        MeshNode receiver = new MeshNode("B2", "Phone B",
                new StubTransport("B2"), new MessageLog());

        List<String> deliveredTokens = new ArrayList<>();
        sender.addListener(new MeshEventListener() {
            @Override public void onLogEntry(MeshLogEntry entry) { }
            @Override public void onPeerDiscovered(Peer peer) { }
            @Override public void onPeerLost(Peer peer) { }
            @Override public void onMessageDelivered(MeshMessage message) { }
            @Override public void onPositionUpdated(String graphNodeId) { }
            @Override public void onDeliveryStatus(String token, String dstId, boolean ok) {
                if (ok) {
                    deliveredTokens.add(token);
                }
            }
        });

        MeshMessage chat = MeshMessage.create(sender.getNodeId(), receiver.getNodeId(),
                MeshKind.CHAT, "ping");
        String trackedToken = PacketCodec.ackToken(chat);

        // Receiver side: decode, deliver, build the ACK exactly like MeshNode.
        MeshMessage decoded = PacketCodec.decode(PacketCodec.encode(chat));
        MeshMessage ack = MeshMessage.create(receiver.getNodeId(), decoded.getSrcId(),
                MeshKind.ACK, PacketCodec.ackToken(decoded));
        // Sender side: decode the ACK and pull the token from its PAYLOAD —
        // the ONLY correct source. ackToken() of the ACK itself derives the
        // ACK's own randomly minted id, which never matches (the original
        // bug #1), so it is deliberately not asserted here.
        MeshMessage ackOnWire = PacketCodec.decode(PacketCodec.encode(ack));

        assertEquals(trackedToken, ackOnWire.getPayload());
    }

    /** Minimal transport for node-construction in tests; nothing runs. */
    private static final class StubTransport implements MeshTransport {
        private final String id;

        StubTransport(String id) {
            this.id = id;
        }

        @Override public void start() { }
        @Override public void stop() { }
        @Override public boolean isRunning() { return false; }
        @Override public void send(MeshMessage message) { }
        @Override public String getLocalNodeId() { return id; }
        @Override public List<Peer> getPeers() { return new ArrayList<>(); }
        @Override public void setListener(Listener listener) { }
    }
}
