package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.MapEdge
import com.example.eventmanagementhub.data.model.MapVenue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerMapFallbackTest {
    private val map = CampusMapResponse(
        venues = listOf(
            MapVenue("entrance", "Entrance", 0.1, 0.9, listOf("new-wing")),
            MapVenue("new-wing", "New Wing", 0.7, 0.3, listOf("entrance")),
            MapVenue("blank", "", 0.5, 0.5)
        ),
        edges = listOf(MapEdge("entrance", "new-wing", 42))
    )

    @Test fun unknownVenueKeepsServerNameAndFallsBackToIdWhenBlank() {
        val v = venuesFromMap(map)
        assertEquals("New Wing", v.first { it.id == "new-wing" }.name)
        assertEquals("blank", v.first { it.id == "blank" }.name)
    }

    @Test fun venueMapFromServerMapUsesServerCoordsAndEdges() {
        val vm = venueMapFromMap(map, "New Wing")
        assertNotNull(vm)
        assertEquals("new-wing", vm!!.venue.id)
        assertEquals(0.7, vm.venue.x, 0.0)
        assertEquals(42, vm.neighbors.single().distanceM)
        assertEquals("Entrance", vm.neighbors.single().name)
    }

    @Test fun unknownRefReturnsNull() {
        assertNull(venueMapFromMap(map, "nope"))
    }

    @Test fun cacheIgnoresEmptyMapAndStoresServerMap() {
        OfflineStore.cacheCampusMap(CampusMapResponse())
        OfflineStore.cacheCampusMap(map)
        assertEquals(3, OfflineStore.cachedCampusMap()!!.venues.size)
    }
}
