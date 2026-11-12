package com.epics.bitchat.mesh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Code-level tests for the BLE connectivity logic, without any phone or
 * emulator: two app instances discover each other, exchange messages, and
 * ignore everything that is not a phone running the app.
 */
public class BlePacketInterpreterTest {

    // Device ids are 3 chars on the v3 mesh wire (PacketCodec NODE_ID_WIRE)
    // and MeshManager generates 3-char ids — these mirror real deployment.
    private static final String PHONE_A = "A1";
    private static final String PHONE_B = "B2";

    @Test
    public void phoneAIdentityDiscoveredByPhoneB() {
        byte[] advert = BeaconParser.encode(PHONE_A, "Phone A", 0, false);
        BlePacketInterpreter.Hit hit =
                BlePacketInterpreter.interpret(advert, -55, PHONE_B);

        assertEquals(BlePacketInterpreter.Reason.IDENTITY, hit.reason);
        assertEquals(PHONE_A, hit.nodeId);
        assertEquals("Phone", hit.displayName);
        assertFalse(hit.isBeacon);
        assertEquals(-55, hit.rssi);
    }

    @Test
    public void ownIdentityAdvertIsIgnoredAsSelf() {
        byte[] advert = BeaconParser.encode(PHONE_A, "Phone A", 0, false);
        BlePacketInterpreter.Hit hit =
                BlePacketInterpreter.interpret(advert, -60, PHONE_A);

        assertEquals(BlePacketInterpreter.Reason.SELF, hit.reason);
    }

    @Test
    public void beaconAdvertIsIgnored() {
        byte[] advert = BeaconParser.encode("Hall 1", 1, true);
        BlePacketInterpreter.Hit hit =
                BlePacketInterpreter.interpret(advert, -70, PHONE_B);

        assertEquals(BlePacketInterpreter.Reason.BEACON, hit.reason);
        assertTrue(hit.isBeacon);
    }

    @Test
    public void twoPhonesWithSameDisplayNameAreDistinct() {
        byte[] a = BeaconParser.encode(PHONE_A, "Phone A", 0, false);
        byte[] b = BeaconParser.encode(PHONE_B, "Phone A", 0, false);

        BlePacketInterpreter.Hit hitA = BlePacketInterpreter.interpret(a, -50, PHONE_B);
        BlePacketInterpreter.Hit hitB = BlePacketInterpreter.interpret(b, -51, PHONE_A);

        assertEquals(PHONE_A, hitA.nodeId);
        assertEquals(PHONE_B, hitB.nodeId);
        assertEquals(hitA.displayName, hitB.displayName);
        assertFalse(hitA.nodeId.equals(hitB.nodeId));
    }

    @Test
    public void chatMessageFromAIsDeliveredToB() {
        MeshMessage sent = MeshMessage.create(PHONE_A, PHONE_B, MeshKind.CHAT, "hello");
        byte[] packet = PacketCodec.encode(sent);

        BlePacketInterpreter.Hit hit = BlePacketInterpreter.interpret(packet, -65, PHONE_B);

        assertEquals(BlePacketInterpreter.Reason.MESSAGE, hit.reason);
        assertNotNull(hit.message);
        assertEquals(MeshKind.CHAT, hit.message.getKind());
        assertEquals("hello", hit.message.getPayload());
        assertEquals(PHONE_A, hit.message.getSrcId());
        assertEquals(PHONE_B, hit.message.getDstId());
    }

    @Test
    public void ownMessagePacketIsIgnored() {
        MeshMessage sent = MeshMessage.create(PHONE_A, PHONE_B, MeshKind.CHAT, "hello");
        byte[] packet = PacketCodec.encode(sent);

        BlePacketInterpreter.Hit hit = BlePacketInterpreter.interpret(packet, -65, PHONE_A);

        assertEquals(BlePacketInterpreter.Reason.SELF, hit.reason);
    }

    @Test
    public void garbageAndNullPayloadsAreIgnored() {
        assertEquals(BlePacketInterpreter.Reason.UNKNOWN,
                BlePacketInterpreter.interpret(new byte[]{0x55, 0x55, 0x55, 0x55}, -80, PHONE_B).reason);
        assertEquals(BlePacketInterpreter.Reason.UNKNOWN,
                BlePacketInterpreter.interpret(null, -80, PHONE_B).reason);
        assertEquals(BlePacketInterpreter.Reason.UNKNOWN,
                BlePacketInterpreter.interpret(new byte[0], -80, PHONE_B).reason);
    }

    @Test
    public void fullLoop_discoveryThenMessageThenRelay() {
        // A and B are in range; C is not (message must be relayed by B).
        String phoneC = "C3";

        // Step 1: A discovers B's identity advert.
        byte[] bAdvert = BeaconParser.encode(PHONE_B, "Phone B", 0, false);
        BlePacketInterpreter.Hit discoverB =
                BlePacketInterpreter.interpret(bAdvert, -60, PHONE_A);
        assertEquals(BlePacketInterpreter.Reason.IDENTITY, discoverB.reason);

        // Step 2: A sends a chat to C; B receives and relays the same bytes.
        MeshMessage msg = MeshMessage.create(PHONE_A, phoneC, MeshKind.CHAT, "route");
        byte[] packet = PacketCodec.encode(msg);
        BlePacketInterpreter.Hit atB = BlePacketInterpreter.interpret(packet, -64, PHONE_B);
        assertEquals(BlePacketInterpreter.Reason.MESSAGE, atB.reason);

        // B relays: same wire bytes re-advertised, so C sees the identical packet.
        BlePacketInterpreter.Hit atC = BlePacketInterpreter.interpret(packet, -58, phoneC);
        assertEquals(BlePacketInterpreter.Reason.MESSAGE, atC.reason);
        assertEquals(PHONE_A, atC.message.getSrcId());
        assertEquals(phoneC, atC.message.getDstId());
        assertEquals("route", atC.message.getPayload());

        // Step 3: C's relay echoes back; B dedups because the id is already seen
        // by the Router (not the interpreter's job), and A ignores its own src.
        BlePacketInterpreter.Hit echoAtA = BlePacketInterpreter.interpret(packet, -70, PHONE_A);
        assertEquals(BlePacketInterpreter.Reason.SELF, echoAtA.reason);
        assertNull(echoAtA.message);
    }
}
