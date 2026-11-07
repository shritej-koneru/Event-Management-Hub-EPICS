package com.example.eventmanagementhub.ui.screens

import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogicTest {
    private val now = LocalDateTime.of(2026, 11, 7, 12, 0)
    private val today = now.toLocalDate()

    private fun ev(id: String, date: String, time: String = "10:00", cat: String = "Tech", dur: Int = 60) =
        Event(id, "T$id", cat, "Hall", "v1", date, time, durationMin = dur)

    @Test fun dayLabels() {
        assertEquals("Today", EventLogic.dayLabel("2026-11-07", today))
        assertEquals("Tomorrow", EventLogic.dayLabel("2026-11-08", today))
        assertEquals("Mon 9 Nov", EventLogic.dayLabel("2026-11-09", today))
        assertEquals("soon", EventLogic.dayLabel("soon", today))
        assertEquals("Sat 7 Nov", EventLogic.todayLabel(today))
    }

    @Test fun sectionsGroupAndSplitPast() {
        val s = EventLogic.sections(
            listOf(
                ev("a", "2026-11-09"), ev("b", "2026-11-07", "18:00"), ev("c", "2026-11-07", "09:00"),
                ev("d", "2026-11-08"), ev("old", "2026-11-01")
            ), now
        )
        assertEquals(listOf("Today", "Tomorrow", "Mon 9 Nov"), s.upcoming.map { it.label })
        assertEquals(listOf("b"), s.upcoming[0].events.map { it.id })
        assertEquals(listOf("c", "old"), s.past.map { it.id })
    }

    @Test fun inProgressEventIsNotPast() {
        assertFalse(EventLogic.isPast(ev("x", "2026-11-07", "11:30", dur = 60), now))
        assertTrue(EventLogic.isPast(ev("x", "2026-11-07", "10:00", dur = 60), now))
        assertFalse(EventLogic.isPast(ev("x", "bad"), now))
    }

    @Test fun nextUpPicksSoonestRegisteredUpcoming() {
        val list = listOf(ev("a", "2026-11-10"), ev("b", "2026-11-08"), ev("c", "2026-11-01"), ev("d", "2026-11-07", "20:00"))
        assertEquals("b", EventLogic.nextUp(list, listOf("a", "b", "c"), now)?.id)
        assertEquals("d", EventLogic.nextUp(list, listOf("a", "d"), now)?.id)
        assertNull(EventLogic.nextUp(list, listOf("c"), now))
        assertNull(EventLogic.nextUp(list, emptyList(), now))
    }

    @Test fun upcomingThisWeekLimitsAndCaps() {
        val list = (0..9).map { ev("e$it", "2026-11-%02d".format(8 + it % 2)) } + ev("far", "2026-12-01")
        val r = EventLogic.upcomingThisWeek(list, now)
        assertEquals(5, r.size)
        assertFalse(r.any { it.id == "far" })
    }

    @Test fun categoriesAreDistinctAndSorted() {
        val c = EventLogic.categories(
            listOf(
                ev("1", "2026-11-08", cat = "Tech"), ev("2", "2026-11-08", cat = "arts"),
                ev("3", "2026-11-08", cat = "tech"), ev("4", "2026-11-08", cat = " ")
            )
        )
        assertEquals(listOf("arts", "Tech"), c)
        val three = listOf(ev("1", "d", cat = "Tech"), ev("2", "d", cat = "tech"), ev("3", "d", cat = "x"))
        assertEquals(2, EventLogic.filterByCategory(three, "Tech").size)
        assertEquals(3, EventLogic.filterByCategory(three, null).size)
    }

    @Test fun capacityMath() {
        assertEquals(40, EventLogic.capacityPercent(20, 50))
        assertEquals(100, EventLogic.capacityPercent(80, 50))
        assertEquals(0, EventLogic.capacityPercent(5, 0))
        assertEquals(30, EventLogic.seatsLeft(20, 50))
        assertEquals(0, EventLogic.seatsLeft(80, 50))
        assertEquals(0.4f, EventLogic.capacityFraction(20, 50), 0.001f)
    }

    @Test fun greetingDurationAndDateParts() {
        assertEquals("Hi, Alice", EventLogic.greeting("Alice Smith"))
        assertEquals("Hi", EventLogic.greeting(" "))
        assertEquals("Hi", EventLogic.greeting(null))
        assertEquals("1 h 30 min", EventLogic.duration(90))
        assertEquals("2 h", EventLogic.duration(120))
        assertEquals("45 min", EventLogic.duration(45))
        assertEquals("7", EventLogic.dayNumber("2026-11-07"))
        assertEquals("Sat", EventLogic.weekday("2026-11-07"))
    }

    private val nowI = Instant.parse("2026-11-07T12:00:00Z")
    private val utc = ZoneOffset.UTC

    @Test fun relativeTimeBuckets() {
        assertEquals("Just now", EventLogic.relativeTime("2026-11-07T11:59:40Z", nowI, utc))
        assertEquals("5 min ago", EventLogic.relativeTime("2026-11-07T11:55:00Z", nowI, utc))
        assertEquals("3 h ago", EventLogic.relativeTime("2026-11-07T09:00:00Z", nowI, utc))
        assertEquals("Yesterday", EventLogic.relativeTime("2026-11-06T23:00:00Z", nowI, utc))
        assertEquals("1 Nov", EventLogic.relativeTime("2026-11-01T10:00:00Z", nowI, utc))
        assertEquals("", EventLogic.relativeTime("junk", nowI, utc))
    }

    @Test fun daySeparators() {
        assertEquals("Today", EventLogic.separatorLabel("2026-11-07T01:00:00Z", nowI, utc))
        assertEquals("Yesterday", EventLogic.separatorLabel("2026-11-06T01:00:00Z", nowI, utc))
        assertEquals("Mon 2 Nov", EventLogic.separatorLabel("2026-11-02T01:00:00Z", nowI, utc))
        assertTrue(EventLogic.startsNewDay(null, "2026-11-07T01:00:00Z", utc))
        assertFalse(EventLogic.startsNewDay("2026-11-07T01:00:00Z", "2026-11-07T23:00:00Z", utc))
        assertTrue(EventLogic.startsNewDay("2026-11-06T23:00:00Z", "2026-11-07T00:10:00Z", utc))
    }

    @Test fun unreadRule() {
        assertTrue(ChatReadState.isUnread(true, 2000, null))
        assertTrue(ChatReadState.isUnread(true, 2000, 1000))
        assertFalse(ChatReadState.isUnread(true, 1000, 2000))
        assertFalse(ChatReadState.isUnread(false, 2000, null))
        assertFalse(ChatReadState.isUnread(true, null, null))
    }

    @Test fun latestAnnouncementPerEvent() {
        val m = EventLogic.latestAnnouncementByEvent(
            listOf(
                Announcement(1, at = "2026-11-06T10:00:00Z", eventId = "a"),
                Announcement(2, at = "2026-11-07T10:00:00Z", eventId = "a"),
                Announcement(3, at = "2026-11-05T10:00:00Z", eventId = null),
                Announcement(4, at = "2026-11-04T10:00:00Z", eventId = "b")
            )
        )
        assertEquals("2026-11-07T10:00:00Z", m["a"])
        assertEquals(setOf("a", "b"), m.keys)
    }
}
