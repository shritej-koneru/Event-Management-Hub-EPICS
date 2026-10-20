package com.example.eventmanagementhub.ui.navigation

import com.example.eventmanagementhub.data.model.Venue
import java.net.URLEncoder

/**
 * Pure logic behind the navigation screen UI. Kept free of Android imports so
 * every rule (search ranking, walking-time estimate, Google Maps hand-off URL,
 * step progression) is unit-testable on the JVM.
 *
 * Distances always come from the backend path response; nothing here invents
 * coordinates, distances or turn geometry.
 */
object NavigationUiLogic {

    /** Standard walking pace used to estimate minutes from the backend's metres. */
    const val WALKING_SPEED_M_PER_S = 1.2

    // ---- campus landmark: VR Siddhartha Engineering College (real coords) --
    // Provided by the campus (Kanuru, Vijayawada). Used as the outdoor map's
    // home centre and as a searchable destination. Not invented here.

    const val COLLEGE_NAME = "VR Siddhartha Engineering College"
    const val COLLEGE_ADDRESS = "Kanuru, Vijayawada, Andhra Pradesh 520007"
    const val COLLEGE_LAT = 16.485277
    const val COLLEGE_LNG = 80.692780

    /** Home zoom for the outdoor map: street-level around the college. */
    const val COLLEGE_ZOOM = 16.5

    /**
     * True when [query] should surface the college as an outdoor destination.
     * Matches the college name, address, or common short aliases
     * (case-insensitive substring; blank queries never match).
     */
    fun collegeMatches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return COLLEGE_NAME.lowercase().contains(q) ||
            COLLEGE_ADDRESS.lowercase().contains(q) ||
            listOf("vr siddhartha", "velagapudi", "siddhartha engineering")
                .any { it.contains(q) }
    }

    /**
     * Filters the backend venue list for the search box. Matches display name
     * or venue id (case-insensitive substring); results ranked name-prefix
     * first, then name-contains, then id-contains. An empty/blank query yields
     * no rows rather than the whole list, mirroring Maps-style search.
     */
    fun searchVenues(venues: List<Venue>, query: String): List<Venue> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return venues
            .mapNotNull { v ->
                val name = v.name.lowercase()
                val id = v.id.lowercase()
                val rank = when {
                    name.startsWith(q) -> 0
                    name.contains(q) -> 1
                    id.contains(q) -> 2
                    else -> null
                }
                rank?.let { it to v }
            }
            .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }))
            .map { it.second }
    }

    /** Walking time in whole minutes for [distanceM] at [WALKING_SPEED_M_PER_S]. */
    fun walkingMinutes(distanceM: Int): Int {
        if (distanceM <= 0) return 0
        return kotlin.math.round(distanceM / WALKING_SPEED_M_PER_S / 60.0).toInt().coerceAtLeast(1)
    }

    /**
     * Sheet label for the backend distance. The minutes are an estimate at a
     * standard walking pace, explicitly marked so they are never mistaken for
     * a live turn-by-turn ETA.
     */
    fun walkingEtaLabel(distanceM: Int): String {
        if (distanceM <= 0) return "Arrived"
        val minutes = walkingMinutes(distanceM)
        return "≈ $minutes min walk (est.)"
    }

    /**
     * Google Maps app deep link that hands outdoor directions off to the real
     * Google Maps (the venue name is resolved by Google — we hold no outdoor
     * coordinates for it).
     */
    fun outdoorDirectionsUrl(destinationName: String): String =
        "https://www.google.com/maps/dir/?api=1&destination=" +
            URLEncoder.encode(destinationName.trim(), "UTF-8")

    /**
     * Index of the step to show in navigation mode after tapping "Next", or
     * null when [currentIndex] was the last step (arrival).
     */
    fun nextStepIndex(currentIndex: Int, stepCount: Int): Int? {
        if (stepCount <= 0) return null
        val next = currentIndex + 1
        return if (next < stepCount) next else null
    }

    /** "Step 3 of 7", or the arrival label once every step is consumed. */
    fun stepProgressLabel(currentIndex: Int, stepCount: Int): String =
        if (stepCount <= 0) "—"
        else "Step ${currentIndex.coerceIn(0, stepCount - 1) + 1} of $stepCount"

    /** True when navigation mode has consumed every step. */
    fun hasArrived(currentIndex: Int, stepCount: Int): Boolean =
        stepCount > 0 && currentIndex >= stepCount - 1
}
