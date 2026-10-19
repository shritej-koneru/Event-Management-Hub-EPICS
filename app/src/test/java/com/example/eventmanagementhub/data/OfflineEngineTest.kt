package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The offline engine must behave like the server: same tokenizer, same scoring
 * weights, same personalized-reason rules. These tests pin that parity so the
 * two implementations cannot silently drift.
 */
class OfflineEngineTest {

    private fun ev(
        id: String,
        title: String = "Event $id",
        description: String = "",
        host: String = "",
        category: String = "tech",
        tags: List<String> = emptyList(),
        registered: Int = 0,
        capacity: Int = 100
    ) = Event(
        id = id,
        title = title,
        category = category,
        venue = "Lecture Hall 204",
        venueId = "room-204",
        date = "2026-06-01",
        time = "18:00",
        durationMin = 60,
        capacity = capacity,
        registered = registered,
        host = host,
        description = description,
        tags = tags
    )

    private fun user(interests: List<String> = emptyList(), bookmarks: List<String> = emptyList()) =
        UserProfile(
            id = "u1",
            name = "Alice",
            email = "alice@uni.edu",
            interests = interests,
            bookmarks = bookmarks
        )

    // ---- search ------------------------------------------------------------------

    @Test
    fun `query round trips through the response`() {
        assertEquals("AI/ML", OfflineEngine.searchOver("AI/ML", emptyList(), null).query)
    }

    @Test
    fun `search is case insensitive`() {
        val events = listOf(ev("e1", title = "Robotics Workshop"))
        val lower = OfflineEngine.searchOver("robotics", events, null)
        val upper = OfflineEngine.searchOver("ROBOTICS", events, null)
        assertTrue(upper.results.isNotEmpty())
        assertEquals(lower.results.map { it.event.id }, upper.results.map { it.event.id })
    }

    @Test
    fun `search ranks a matching title above an unrelated event`() {
        val events = listOf(
            ev("e1", title = "Robotics Workshop", description = "Build robots"),
            ev("e2", title = "Piano Concert", category = "music")
        )
        val res = OfflineEngine.searchOver("robotics", events, null)
        assertTrue(res.results.isNotEmpty())
        assertEquals("e1", res.results.first().event.id)
    }

    @Test
    fun `search matches tags as well as title and description`() {
        val events = listOf(
            ev("e1", title = "Unrelated Name", tags = listOf("rust", "systems")),
            ev("e2", title = "Another Event", description = "Nothing here")
        )
        val res = OfflineEngine.searchOver("rust", events, null)
        assertTrue(res.results.any { it.event.id == "e1" })
    }

    @Test
    fun `search matches a category synonym`() {
        val events = listOf(ev("e1", title = "Evening Session", category = "tech"))
        // "robotics" is a synonym for the tech category on the server too.
        assertTrue(OfflineEngine.searchOver("robotics", events, null).results.isNotEmpty())
    }

    @Test
    fun `search returns no results for an unrelated query`() {
        val events = listOf(ev("e1", title = "Robotics Workshop"))
        assertTrue(OfflineEngine.searchOver("underwater basket weaving", events, null).results.isEmpty())
    }

    @Test
    fun `stopwords alone match nothing`() {
        val events = listOf(ev("e1", title = "Robotics Workshop"))
        assertTrue(OfflineEngine.searchOver("the and for", events, null).results.isEmpty())
    }

    @Test
    fun `empty query yields no results`() {
        assertTrue(OfflineEngine.searchOver("", listOf(ev("e1")), null).results.isEmpty())
    }

    @Test
    fun `search explains why a result matched`() {
        val res = OfflineEngine.searchOver("robotics", listOf(ev("e1", title = "Robotics Workshop")), null)
        assertTrue(res.results.first().reason.isNotBlank())
    }

    @Test
    fun `limit is respected`() {
        val events = (1..20).map { ev("e$it", title = "Robotics Session $it") }
        assertEquals(3, OfflineEngine.searchOver("robotics", events, null, limit = 3).results.size)
    }

    // ---- recommendations ---------------------------------------------------------

    @Test
    fun `recommendations prefer events matching user interests`() {
        val events = listOf(
            ev("e1", title = "AI Summit", category = "tech", tags = listOf("ai")),
            ev("e2", title = "Guitar Night", category = "music")
        )
        val res = OfflineEngine.recommendationsFor(user(listOf("ai")), events, emptyList())
        assertTrue(res.recommendations.isNotEmpty())
        assertEquals("e1", res.recommendations.first().event.id)
    }

    @Test
    fun `recommendations explain why an event was chosen`() {
        val events = listOf(ev("e1", title = "AI Summit", tags = listOf("ai")))
        val res = OfflineEngine.recommendationsFor(user(listOf("ai")), events, emptyList())
        assertTrue(res.recommendations.first().reason.contains("interest"))
    }

    @Test
    fun `bookmarks also drive recommendations`() {
        val events = listOf(ev("e1", title = "Bookmarked Talk", category = "music"))
        val res = OfflineEngine.recommendationsFor(user(bookmarks = listOf("e1")), events, emptyList())
        assertEquals(1, res.recommendations.size)
        assertEquals("e1", res.recommendations.first().event.id)
    }

    @Test
    fun `similar recent searches drive recommendations`() {
        val events = listOf(ev("e1", title = "Robotics Lab", tags = listOf("robotics")))
        val res = OfflineEngine.recommendationsFor(user(), events, listOf("robotics"))
        assertEquals(1, res.recommendations.size)
    }

    @Test
    fun `no signals at all yields no recommendations`() {
        val events = listOf(ev("e1", title = "Unrelated Talk", category = "music"))
        assertTrue(OfflineEngine.recommendationsFor(user(), events, emptyList()).recommendations.isEmpty())
    }

    @Test
    fun `popularity is counted once not twice`() {
        // Regression: crowd score used to be added on top of a popularity term
        // that already contained it, inflating every score.
        val events = listOf(ev("e1", title = "Solo Event", registered = 50, capacity = 100))
        val scored = OfflineEngine.recommendationsFor(user(bookmarks = listOf("e1")), events, emptyList())
            .recommendations.first().score
        // bookmark boost 1.5 + popularity 0.25 normalised; double counting crowd
        // would push this past the single-count ceiling.
        assertTrue("score was $scored", scored <= 0.5)
        assertTrue("score was $scored", scored > 0.1)
    }

    // ---- details -----------------------------------------------------------------

    @Test
    fun `details resolves the event venue`() {
        val details = OfflineEngine.details(ev("e1", title = "Robotics Workshop"), "entrance", emptyList())
        assertNotNull(details.venue)
        assertEquals("room-204", details.venue!!.id)
        assertEquals("Lecture Hall 204", details.venue!!.name)
    }

    @Test
    fun `details computes derived statistics`() {
        val stats = OfflineEngine.details(ev("e1", registered = 40, capacity = 100), null, emptyList()).statistics
        assertEquals(100, stats.capacity)
        assertEquals(40, stats.registered)
        assertEquals(60, stats.seatsRemaining)
        assertEquals(40.0, stats.occupancyPercent, 1e-9)
    }

    @Test
    fun `details never reports negative seats`() {
        val stats = OfflineEngine.details(ev("e1", registered = 150, capacity = 100), null, emptyList()).statistics
        assertEquals(0, stats.seatsRemaining)
    }

    @Test
    fun `details only counts announcements for this event`() {
        val mine = Announcement(id = 1, room = "204", message = "Starts on time", eventId = "e1")
        val other = Announcement(id = 2, room = "101", message = "Cancelled", eventId = "e2")
        val details = OfflineEngine.details(ev("e1"), null, listOf(mine, other))
        assertEquals(1, details.statistics.announcementCount)
        assertEquals(1, details.announcements.size)
        assertEquals("Starts on time", details.announcements.first().message)
    }

    @Test
    fun `details builds charts with totals`() {
        val charts = OfflineEngine.details(ev("e1", registered = 30, capacity = 60), null, emptyList()).charts
        assertTrue(charts.isNotEmpty())
        charts.forEach { assertTrue("chart ${it.id} had no total", it.total > 0) }
    }

    @Test
    fun `details includes a navigation path when a starting point is known`() {
        val navigation = OfflineEngine.details(ev("e1"), "entrance", emptyList()).navigation
        assertNotNull(navigation)
        assertTrue(navigation!!.steps.isNotEmpty())
    }

    // ---- path --------------------------------------------------------------------

    @Test
    fun `path returns steps from entrance to a venue`() {
        val res = OfflineEngine.path("entrance", "room-204")
        assertTrue(res.steps.isNotEmpty())
        assertTrue(res.totalDistanceM > 0)
    }

    @Test
    fun `path to an unknown node throws`() {
        // The UI calls this behind a try/catch, so an unknown node must fail
        // loudly rather than return a plausible-looking empty path.
        try {
            OfflineEngine.path("entrance", "does-not-exist")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does-not-exist"))
        }
    }

    @Test
    fun `path names each step`() {
        val res = OfflineEngine.path("entrance", "room-204")
        assertTrue(res.steps.all { it.name.isNotBlank() })
    }
}