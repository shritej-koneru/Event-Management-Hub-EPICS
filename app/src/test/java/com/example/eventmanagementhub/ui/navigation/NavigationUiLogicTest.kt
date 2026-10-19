package com.example.eventmanagementhub.ui.navigation

import com.example.eventmanagementhub.data.model.Venue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUiLogicTest {

    private val venues = listOf(
        Venue("main-entrance", "Main Entrance", emptyList()),
        Venue("room-204", "Room 204", emptyList()),
        Venue("auditorium", "Auditorium", emptyList()),
        Venue("library", "Main Library", emptyList())
    )

    // ---- searchVenues ----------------------------------------------------

    @Test
    fun `empty query returns no results`() {
        assertTrue(NavigationUiLogic.searchVenues(venues, "").isEmpty())
        assertTrue(NavigationUiLogic.searchVenues(venues, "   ").isEmpty())
    }

    @Test
    fun `name prefix ranks before name contains and id contains`() {
        val results = NavigationUiLogic.searchVenues(venues, "main")
        assertEquals(listOf("main-entrance", "library"), results.map { it.id })
    }

    @Test
    fun `matches venue id as fallback`() {
        val results = NavigationUiLogic.searchVenues(venues, "204")
        assertEquals(listOf("room-204"), results.map { it.id })
    }

    @Test
    fun `search is case-insensitive`() {
        val results = NavigationUiLogic.searchVenues(venues, "AUDITOR")
        assertEquals(listOf("auditorium"), results.map { it.id })
    }

    @Test
    fun `unknown query returns nothing`() {
        assertTrue(NavigationUiLogic.searchVenues(venues, "gymn").isEmpty())
    }

    // ---- walkingMinutes / walkingEtaLabel --------------------------------

    @Test
    fun `zero distance means arrived`() {
        assertEquals(0, NavigationUiLogic.walkingMinutes(0))
        assertEquals("Arrived", NavigationUiLogic.walkingEtaLabel(0))
    }

    @Test
    fun `short walk rounds to at least one minute`() {
        assertEquals(1, NavigationUiLogic.walkingMinutes(30))
        assertEquals("≈ 1 min walk (est.)", NavigationUiLogic.walkingEtaLabel(30))
    }

    @Test
    fun `minutes use backend metres at standard walking pace`() {
        // 150 m / 1.2 m/s = 125 s -> ~2 min
        assertEquals(2, NavigationUiLogic.walkingMinutes(150))
        // 600 m / 1.2 m/s = 500 s -> ~8 min
        assertEquals(8, NavigationUiLogic.walkingMinutes(600))
    }

    // ---- outdoorDirectionsUrl --------------------------------------------

    @Test
    fun `directions url encodes the destination name`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=Room+204",
            NavigationUiLogic.outdoorDirectionsUrl("Room 204")
        )
    }

    @Test
    fun `directions url trims surrounding whitespace`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=Auditorium",
            NavigationUiLogic.outdoorDirectionsUrl("  Auditorium  ")
        )
    }

    // ---- step progression ------------------------------------------------

    @Test
    fun `next step advances until the last step`() {
        assertEquals(1, NavigationUiLogic.nextStepIndex(0, 3))
        assertEquals(2, NavigationUiLogic.nextStepIndex(1, 3))
        assertNull(NavigationUiLogic.nextStepIndex(2, 3))
    }

    @Test
    fun `no steps yields no next`() {
        assertNull(NavigationUiLogic.nextStepIndex(0, 0))
    }

    @Test
    fun `progress label counts from one`() {
        assertEquals("Step 1 of 3", NavigationUiLogic.stepProgressLabel(0, 3))
        assertEquals("Step 3 of 3", NavigationUiLogic.stepProgressLabel(2, 3))
        assertEquals("—", NavigationUiLogic.stepProgressLabel(0, 0))
    }

    @Test
    fun `arrival is true only on the final step`() {
        assertTrue(NavigationUiLogic.hasArrived(2, 3))
        assertTrue(!NavigationUiLogic.hasArrived(1, 3))
        assertTrue(!NavigationUiLogic.hasArrived(0, 0))
    }
}
