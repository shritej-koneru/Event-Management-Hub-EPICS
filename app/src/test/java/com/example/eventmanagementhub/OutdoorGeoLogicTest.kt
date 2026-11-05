package com.example.eventmanagementhub

import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.MapBounds
import com.example.eventmanagementhub.data.model.MapEdge
import com.example.eventmanagementhub.data.model.MapVenue
import com.example.eventmanagementhub.ui.navigation.OutdoorGeoLogic
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdoorGeoLogicTest {
    private val map = CampusMapResponse(
        venues = listOf(
            MapVenue("gate", "Gate", lat = 16.4850, lng = 80.6920, kind = "Gate", geoPlaced = true),
            MapVenue("lab", "", lat = 16.4855, lng = 80.6930, description = "CS lab"),
            MapVenue("old", "Old", 0.5, 0.5) // no coordinates
        ),
        edges = listOf(MapEdge("gate", "lab", 40), MapEdge("gate", "old", 10))
    )

    @Test fun onlyVenuesWithCoordsProduceMarkers() {
        assertEquals(listOf("gate", "lab"), OutdoorGeoLogic.markers(map).map { it.id })
    }

    @Test fun placedVsDerivedStyleAndBlankNameFallback() {
        val m = OutdoorGeoLogic.markers(map).associateBy { it.id }
        assertTrue(m.getValue("gate").placed)
        assertFalse(m.getValue("lab").placed)
        assertEquals("lab", m.getValue("lab").name)
    }

    @Test fun invalidCoordinatesAreSkipped() {
        val bad = CampusMapResponse(venues = listOf(MapVenue("a", "A", lat = 200.0, lng = 80.0)))
        assertTrue(OutdoorGeoLogic.markers(bad).isEmpty())
    }

    @Test fun edgeLinesUseVenueCoordsAndSkipUnlocatedEndpoints() {
        val lines = OutdoorGeoLogic.edgeLines(map)
        assertEquals(1, lines.size)
        val l = lines.single()
        assertEquals(16.4850, l.fromLat, 0.0)
        assertEquals(80.6930, l.toLng, 0.0)
    }

    @Test fun boundsMustBeWellFormed() {
        val ok = MapBounds(16.487077, 16.483477, 80.690280, 80.695280)
        assertEquals(ok, OutdoorGeoLogic.drawableBounds(ok))
        assertNull(OutdoorGeoLogic.drawableBounds(MapBounds()))
        assertNull(OutdoorGeoLogic.drawableBounds(null))
    }

    @Test fun detailLineDropsBlanks() {
        assertEquals("Hall - Big", OutdoorGeoLogic.detailLine("Hall", " Big "))
        assertEquals("Lab", OutdoorGeoLogic.detailLine("Lab", ""))
        assertEquals("", OutdoorGeoLogic.detailLine("", ""))
    }

    @Test fun jsonToleratesAbsentAndNullGeoFields() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        val old = json.decodeFromString<CampusMapResponse>(
            """{"venues":[{"id":"a","name":"A","x":0.1,"y":0.2}],"edges":[]}"""
        )
        assertNull(old.bounds)
        assertNull(old.venues.single().lat)
        val new = json.decodeFromString<CampusMapResponse>(
            """{"bounds":{"north":2,"south":1,"west":1,"east":2,"center":{"lat":1.5,"lng":1.5}},
               "venues":[{"id":"a","name":"A","x":0.1,"y":0.2,"lat":1.5,"lng":1.6,"kind":null,"description":"d","geoPlaced":true}],
               "edges":[]}"""
        )
        assertEquals(1.5, new.bounds!!.center!!.lat, 0.0)
        assertEquals("", new.venues.single().kind)
        assertTrue(new.venues.single().geoPlaced)
    }
}
