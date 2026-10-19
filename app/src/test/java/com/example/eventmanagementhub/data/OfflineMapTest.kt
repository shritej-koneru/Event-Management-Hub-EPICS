package com.example.eventmanagementhub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline map must mirror the backend (venues.js MAP_POSITIONS + GRAPH)
 * exactly, so a device with no network draws the same campus as the server
 * serves. These tests pin that parity.
 */
class OfflineMapTest {

    @Test
    fun `offline campus map covers every venue with a position`() {
        val map = offlineCampusMap()
        assertEquals(offlineVenueOrder.size, map.venues.size)
        for (venue in map.venues) {
            assertTrue("missing position for ${venue.id}", offlineMapPositions.containsKey(venue.id))
            assertTrue(venue.x >= 0.0 && venue.x <= 1.0)
            assertTrue(venue.y >= 0.0 && venue.y <= 1.0)
            assertEquals(offlineVenueNames[venue.id], venue.name)
        }
    }

    @Test
    fun `offline campus map stores each undirected edge once`() {
        val map = offlineCampusMap()
        val expected = offlineGraph.values.sumOf { it.size } / 2
        assertEquals(expected, map.edges.size)

        for (edge in map.edges) {
            assertTrue("${edge.from} < ${edge.to} expected", edge.from < edge.to)
            assertEquals(offlineGraph.getValue(edge.from).getValue(edge.to), edge.distanceM)
            assertTrue(map.venues.any { it.id == edge.from })
            assertTrue(map.venues.any { it.id == edge.to })
        }
    }

    @Test
    fun `offline venue map resolves an id and a display name`() {
        val byId = offlineVenueMap("room-204")
        assertNotNull(byId)
        assertEquals("room-204", byId!!.venue.id)
        assertEquals(offlineMapPositions["room-204"]!!.first, byId.venue.x, 1e-9)
        assertEquals(3, byId.neighbors.size)

        val byName = offlineVenueMap("Room 204")
        assertNotNull(byName)
        assertEquals("room-204", byName!!.venue.id)

        val neighbour = byId.neighbors.firstOrNull { it.id == "lab-a" }
        assertNotNull(neighbour)
        assertEquals(10, neighbour!!.distanceM)
        assertEquals(offlineVenueNames["lab-a"], neighbour.name)
    }

    @Test
    fun `offline venue map refuses unknown venues instead of guessing`() {
        assertNull(offlineVenueMap("dormitory"))
        assertNull(offlineVenueMap("room 204"))
    }
}
