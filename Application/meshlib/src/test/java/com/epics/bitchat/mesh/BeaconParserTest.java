package com.epics.bitchat.mesh;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BeaconParserTest {

    @Test
    public void roundTripsBeacon() {
        byte[] encoded = BeaconParser.encode("Hall 1", 1, true);
        BeaconParser.BeaconInfo info = BeaconParser.decode(encoded);
        assertEquals("Hall 1", info.nodeId);
        assertEquals(1, info.floor);
        assertTrue(info.isBeacon);
    }

    @Test
    public void roundTripsPhone() {
        byte[] encoded = BeaconParser.encode("node_7", 0, false);
        BeaconParser.BeaconInfo info = BeaconParser.decode(encoded);
        assertEquals("node_7", info.nodeId);
        assertEquals(0, info.floor);
        assertFalse(info.isBeacon);
    }

    @Test
    public void roundTripsPhoneWithDisplayName() {
        byte[] encoded = BeaconParser.encode("AB12CD", "Phone A", 0, false);
        BeaconParser.BeaconInfo info = BeaconParser.decode(encoded);
        assertEquals("AB12CD", info.nodeId);
        assertEquals("Phone", info.displayName);
        assertFalse(info.isBeacon);
    }

    @Test
    public void displayNameFallsBackToNodeIdForLegacyAdverts() {
        byte[] legacy = BeaconParser.encode("Hall 1", 1, true);
        BeaconParser.BeaconInfo info = BeaconParser.decode(legacy);
        assertEquals("Hall 1", info.nodeId);
        assertEquals("Hall 1", info.displayName);
        assertTrue(info.isBeacon);
    }

    @Test
    public void longNamesAreTruncatedToSixChars() {
        byte[] encoded = BeaconParser.encode("ABC", "Glorious Name", 0, false);
        BeaconParser.BeaconInfo info = BeaconParser.decode(encoded);
        assertEquals("ABC", info.nodeId);
        assertEquals("Glorio", info.displayName);
    }

    @Test
    public void invalidPayloadDecodesToNull() {
        assertEquals(null, BeaconParser.decode(new byte[]{0x00, 0x01}));
        assertEquals(null, BeaconParser.decode(null));
    }
}
