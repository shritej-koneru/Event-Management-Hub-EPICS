package com.example.eventmanagementhub.ui.navigation

import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.MapBounds

/** One outdoor map pin. [placed] = a manager put it on the real map (solid); else derived (hollow). */
data class GeoMarker(
    val id: String,
    val name: String,
    val kind: String,
    val description: String,
    val lat: Double,
    val lng: Double,
    val placed: Boolean
)

/** A walking edge as a two-point polyline. */
data class GeoLine(
    val fromId: String,
    val toId: String,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double
)

/** Pure outdoor-map derivations (JVM-testable; no osmdroid types). */
object OutdoorGeoLogic {

    private fun validCoord(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0

    /** Every venue with usable lat/lng; venues without coordinates (old server/offline) are skipped. */
    fun markers(map: CampusMapResponse): List<GeoMarker> =
        map.venues.mapNotNull { v ->
            val lat = v.lat
            val lng = v.lng
            if (lat == null || lng == null || !validCoord(lat, lng)) null
            else GeoMarker(
                id = v.id,
                name = v.name.ifBlank { v.id },
                kind = v.kind,
                description = v.description,
                lat = lat,
                lng = lng,
                placed = v.geoPlaced
            )
        }

    /** Edges whose both endpoints have coordinates. */
    fun edgeLines(map: CampusMapResponse): List<GeoLine> {
        val byId = markers(map).associateBy { it.id }
        return map.edges.mapNotNull { e ->
            val a = byId[e.from] ?: return@mapNotNull null
            val b = byId[e.to] ?: return@mapNotNull null
            GeoLine(a.id, b.id, a.lat, a.lng, b.lat, b.lng)
        }
    }

    /** Bounds usable for drawing (north > south, east > west), else null. */
    fun drawableBounds(b: MapBounds?): MapBounds? =
        b?.takeIf {
            it.north > it.south && it.east > it.west &&
                validCoord(it.north, it.west) && validCoord(it.south, it.east)
        }

    /** Info-card subtitle: kind and/or description, blanks dropped. */
    fun detailLine(kind: String, description: String): String =
        listOf(kind.trim(), description.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
}
